package dev.mytv.remote

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.PixelCopy
import dev.mytv.BuildConfig
import dev.mytv.data.network.VpnRequiredException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aide à distance : la personne qui aide voit l'écran de MyTV sur son PC et pilote l'appli
 * comme avec la télécommande (flèches, OK, Retour, chiffres, texte), depuis n'importe où.
 *
 * Fonctionnement :
 * - la TV affiche un code ; la TV et la page web se retrouvent dans une « salle » du relais
 *   MyTV (Cloudflare), dont le nom est un hachage du code ;
 * - la box n'accepte aucune connexion entrante : c'est elle qui se connecte au relais ;
 * - tout est chiffré de bout en bout (AES-GCM, clé tirée du code) : le relais ne voit rien.
 *
 * Sobriété (petites box) : rien ne tourne tant que l'aide n'est pas démarrée. Une fois la
 * personne connectée, l'écran est copié une fois par seconde en 960 px de large, et l'image
 * n'est envoyée que si elle a changé. Tout le travail se fait sur un seul thread dédié.
 *
 * L'aide reste active pendant qu'on navigue dans MyTV (bandeau rouge affiché partout), et
 * s'arrête quand le client l'arrête, quitte l'appli, ou après 30 min sans personne de connecté.
 */
@Singleton
class RemoteControl @Inject constructor(baseClient: OkHttpClient) {

    enum class Status { OFF, CONNECTING, WAITING, CONNECTED, OFFLINE, VPN_REQUIRED }

    data class State(
        val code: String? = null,
        val status: Status = Status.OFF,
        /** Dernier événement à afficher sur la TV (ex. « La personne qui t'aide est connectée »). */
        val event: String? = null,
    )

    /** Demandes de navigation venues du PC, exécutées par le NavHost. */
    enum class Navigation { HOME }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _navigation = MutableSharedFlow<Navigation>(extraBufferCapacity = 4)
    val navigation: SharedFlow<Navigation> = _navigation.asSharedFlow()

    // Le client de l'appli (même coupe-circuit VPN) ; pas de délai global pour une connexion longue,
    // un ping toutes les 25 s détecte les coupures.
    private val client = baseClient.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .cache(null)
        .build()

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var activity: WeakReference<Activity>? = null
    private var session: Session? = null

    /** Appelé par l'activité (onResume / onPause) : la copie d'écran et les touches passent par elle. */
    fun attach(a: Activity) { activity = WeakReference(a) }
    fun detach(a: Activity) { if (activity?.get() === a) activity = null }

    /** Démarre l'aide (nouveau code) si elle ne tourne pas déjà. Thread principal. */
    fun start() {
        if (session != null) return
        val code = newCode()
        _state.value = State(code = code, status = Status.CONNECTING)
        session = Session(code).also { it.open() }
    }

    /** Arrête l'aide et prévient la page web. Thread principal. */
    fun stop() {
        session?.close()
        session = null
        _state.value = State()
    }

    private fun event(text: String?) = _state.update { it.copy(event = text) }
    private fun status(s: Status) = _state.update { if (it.status == Status.OFF) it else it.copy(status = s) }

    /** Une session = un code ; tout se passe sur son thread [h], sauf les touches (thread principal). */
    private inner class Session(code: String) {
        private val thread = HandlerThread("mytv-remote").apply { start() }
        private val h = Handler(thread.looper)
        private lateinit var crypto: Crypto
        private val normalized = normalize(code)
        @Volatile private var closed = false
        private var ws: WebSocket? = null
        private var backoff = 2_000L
        private var peer = false

        // Copie d'écran : tampons réutilisés, aucune allocation par image.
        @Volatile private var bitmap: Bitmap? = null
        @Volatile private var pixels: ByteBuffer? = null
        private val jpeg = ByteArrayOutputStream(96 * 1024)
        private val crc = CRC32()
        private var lastCrc = -1L
        @Volatile private var capturing = false

        private val tick = object : Runnable {
            override fun run() {
                if (!peer || closed) return
                capture()
                h.postDelayed(this, CAPTURE_INTERVAL_MS)
            }
        }
        private val autoStop = Runnable { if (!peer) main.post { if (session === this) stop() } }

        fun open() = h.post {
            if (closed) return@post
            if (!::crypto.isInitialized) crypto = Crypto(normalized) // PBKDF2 : hors du thread principal
            status(Status.CONNECTING)
            val request = Request.Builder().url("${BuildConfig.REMOTE_RELAY}/ws?room=${crypto.room}&role=tv").build()
            ws = client.newWebSocket(request, listener)
            h.removeCallbacks(autoStop)
            h.postDelayed(autoStop, AUTO_STOP_MS)
        }

        fun close() {
            closed = true
            h.post {
                runCatching { if (peer) sendJson(JSONObject().put("t", "bye")) }
                ws?.close(1000, null)
                ws = null
                bitmap?.recycle()
                bitmap = null
                h.removeCallbacksAndMessages(null)
                thread.quitSafely()
            }
        }

        private val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                h.post {
                    backoff = 2_000L
                    if (!peer) status(Status.WAITING)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Messages du relais lui-même (non chiffrés) : présence de l'autre côté.
                h.post {
                    val m = runCatching { JSONObject(text) }.getOrNull() ?: return@post
                    if (m.optString("relay") == "peer" && !m.optBoolean("up")) peerLeft(null)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                h.post {
                    val plain = runCatching { crypto.decrypt(bytes.toByteArray()) }.getOrNull() ?: return@post
                    if (plain.isEmpty() || plain[0] != T_JSON) return@post
                    val m = runCatching { JSONObject(String(plain, 1, plain.size - 1)) }.getOrNull() ?: return@post
                    handle(m)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                h.post { lost(webSocket, t is VpnRequiredException) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                h.post { lost(webSocket, false) }
            }
        }

        /** Connexion au relais perdue : nouvel essai, de plus en plus espacé (2 s → 30 s). */
        private fun lost(socket: WebSocket, vpn: Boolean) {
            if (closed || socket !== ws) return
            ws = null
            peerLeft(null)
            status(if (vpn) Status.VPN_REQUIRED else Status.OFFLINE)
            h.postDelayed({ open() }, backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }

        private fun handle(m: JSONObject) {
            when (m.optString("t")) {
                "hello" -> {
                    peer = true
                    h.removeCallbacks(autoStop)
                    status(Status.CONNECTED)
                    event("La personne qui t'aide est connectée : elle voit ton écran.")
                    sendJson(JSONObject().put("t", "hello").put("version", BuildConfig.VERSION_NAME))
                    lastCrc = -1L // renvoie l'écran même s'il n'a pas changé
                    h.removeCallbacks(tick)
                    h.post(tick)
                }
                "key" -> {
                    if (!peer) return
                    pressKey(m.optString("k"))
                    // L'écran change juste après une touche : copie rapide, puis une autre une fois
                    // les animations finies.
                    h.postDelayed({ capture() }, 150)
                    h.postDelayed({ capture() }, 600)
                }
                "text" -> {
                    if (!peer) return
                    val text = m.optString("s").take(500)
                    main.post {
                        val sink = RemoteTextInput.sink
                        if (sink == null) {
                            info("Sélectionne d'abord un champ de texte sur la TV (avec les flèches), puis réessaie.", false)
                        } else {
                            sink(text)
                            info("Champ rempli ✓", true)
                            h.postDelayed({ capture() }, 150)
                        }
                    }
                }
                "bye" -> peerLeft("La personne qui t'aide a terminé.")
            }
        }

        private fun peerLeft(message: String?) {
            if (!peer) return
            peer = false
            h.removeCallbacks(tick)
            status(Status.WAITING)
            event(message ?: "La personne qui t'aide s'est déconnectée.")
            h.removeCallbacks(autoStop)
            h.postDelayed(autoStop, AUTO_STOP_MS)
        }

        private fun info(msg: String, ok: Boolean) = h.post { sendJson(JSONObject().put("t", "info").put("msg", msg).put("ok", ok)) }

        private fun sendJson(json: JSONObject) {
            val body = json.toString().toByteArray()
            send(T_JSON, body, 0, body.size)
        }

        private fun send(type: Byte, data: ByteArray, offset: Int, length: Int) {
            val socket = ws ?: return
            socket.send(crypto.encrypt(type, data, offset, length).toByteString())
        }

        // ------------------------------------------------------------------ copie d'écran

        private fun capture() {
            if (!peer || closed || capturing) return
            val socket = ws ?: return
            if (socket.queueSize() > MAX_QUEUE) return // connexion lente : on saute une image
            val act = activity?.get() ?: return
            capturing = true
            main.post {
                val decor = act.window?.peekDecorView()
                if (decor == null || decor.width == 0 || decor.height == 0) { capturing = false; return@post }
                val w = CAPTURE_WIDTH.coerceAtMost(decor.width)
                val hgt = decor.height * w / decor.width
                val bmp = bitmap?.takeIf { it.width == w && it.height == hgt }
                    ?: Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888).also { bitmap?.recycle(); bitmap = it; pixels = null }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Copie par le GPU, redimensionnée directement à la taille du tampon.
                    runCatching {
                        PixelCopy.request(act.window, bmp, { result ->
                            if (result == PixelCopy.SUCCESS) encodeAndSend(bmp) else capturing = false
                        }, h)
                    }.onFailure { capturing = false }
                } else {
                    // Android 7.1 : dessin logiciel de la vue, réduit (une fois par seconde au plus).
                    runCatching {
                        val canvas = Canvas(bmp)
                        canvas.scale(w.toFloat() / decor.width, hgt.toFloat() / decor.height)
                        decor.draw(canvas)
                    }.onSuccess { h.post { encodeAndSend(bmp) } }.onFailure { capturing = false }
                }
            }
        }

        private fun encodeAndSend(bmp: Bitmap) {
            try {
                if (closed || bmp.isRecycled) return
                val buf = pixels ?: ByteBuffer.allocate(bmp.byteCount).also { pixels = it }
                buf.rewind()
                bmp.copyPixelsToBuffer(buf)
                crc.reset()
                crc.update(buf.array(), 0, bmp.byteCount)
                if (crc.value == lastCrc) return // écran identique : rien à envoyer
                lastCrc = crc.value
                jpeg.reset()
                jpeg.write(T_JPEG.toInt())
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
                send(T_JPEG, jpeg.toByteArray(), 1, jpeg.size() - 1)
            } finally {
                capturing = false
            }
        }
    }

    // ------------------------------------------------------------------ touches

    // Thread principal uniquement. Les touches envoyées en rafale (touche maintenue sur le PC) sont
    // espacées comme sur une vraie télécommande : sinon l'interface en perd pendant ses animations.
    private var nextKeyAt = 0L

    private fun pressKey(name: String) = main.post {
        val at = maxOf(SystemClock.uptimeMillis(), nextKeyAt)
        nextKeyAt = at + KEY_SPACING_MS
        main.postAtTime({ pressNow(name) }, at)
    }

    private fun pressNow(name: String) {
        val act = activity?.get() ?: return
        when (name) {
            "HOME" -> _navigation.tryEmit(Navigation.HOME)
            "OK_LONG" -> press(act, KeyEvent.KEYCODE_DPAD_CENTER, long = true)
            else -> KEYS[name]?.let { press(act, it, long = false) }
        }
    }

    private fun press(act: Activity, code: Int, long: Boolean) {
        val t = SystemClock.uptimeMillis()
        val source = InputDevice.SOURCE_DPAD or InputDevice.SOURCE_KEYBOARD
        fun key(action: Int, at: Long, repeat: Int, flags: Int) =
            KeyEvent(t, at, action, code, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, source)
        act.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, t, 0, 0))
        if (long) act.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, t + 600, 1, KeyEvent.FLAG_LONG_PRESS))
        act.dispatchKeyEvent(key(KeyEvent.ACTION_UP, if (long) t + 700 else t + 50, 0, 0))
    }

    // ------------------------------------------------------------------ chiffrement

    /** Salle et clé tirées du code ; mêmes calculs que la page web (WebCrypto). */
    private class Crypto(code: String) {
        val room: String = MessageDigest.getInstance("SHA-256").digest("mytv:room:$code".toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
        private val key = SecretKeySpec(
            // PBKDF2-SHA1 : disponible dès Android 7 (SHA-256 seulement à partir d'Android 8).
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(PBEKeySpec(code.toCharArray(), SALT.toByteArray(), ITERATIONS, 256))
                .encoded,
            "AES",
        )
        private val random = SecureRandom()

        /** Message = IV (12 octets) + AES-GCM(type + données). */
        fun encrypt(type: Byte, data: ByteArray, offset: Int, length: Int): ByteArray {
            val iv = ByteArray(12).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val out = ByteArray(12 + cipher.getOutputSize(1 + length))
            System.arraycopy(iv, 0, out, 0, 12)
            var n = cipher.update(byteArrayOf(type), 0, 1, out, 12)
            n += cipher.doFinal(data, offset, length, out, 12 + n)
            return if (12 + n == out.size) out else out.copyOf(12 + n)
        }

        fun decrypt(payload: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload, 0, 12))
            return cipher.doFinal(payload, 12, payload.size - 12)
        }
    }

    companion object {
        private const val T_JSON: Byte = 1
        private const val T_JPEG: Byte = 2
        private const val KEY_SPACING_MS = 150L
        private const val CAPTURE_WIDTH = 960
        private const val CAPTURE_INTERVAL_MS = 1_000L
        private const val JPEG_QUALITY = 60
        private const val MAX_QUEUE = 256L * 1024
        private const val AUTO_STOP_MS = 30 * 60 * 1000L
        private const val SALT = "mytv-remote-v1"
        private const val ITERATIONS = 20_000
        // Sans 0/O, 1/I/L, U : rien d'ambigu à lire au téléphone ou à recopier.
        private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"

        private val KEYS = mapOf(
            "UP" to KeyEvent.KEYCODE_DPAD_UP, "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
            "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT, "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "OK" to KeyEvent.KEYCODE_DPAD_CENTER, "BACK" to KeyEvent.KEYCODE_BACK,
            "MENU" to KeyEvent.KEYCODE_MENU, "PLAY_PAUSE" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            "CH_UP" to KeyEvent.KEYCODE_CHANNEL_UP, "CH_DOWN" to KeyEvent.KEYCODE_CHANNEL_DOWN,
        ) + (0..9).associate { "D$it" to KeyEvent.KEYCODE_0 + it }

        private fun newCode(): String {
            val r = SecureRandom()
            val raw = (1..10).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
            return "${raw.substring(0, 3)}-${raw.substring(3, 6)}-${raw.substring(6)}"
        }

        private fun normalize(code: String) = code.uppercase().filter { it in ALPHABET }
    }
}

/** Champ de texte qui a le focus sur la TV : la page web peut le remplir (adresse, mot de passe…). */
object RemoteTextInput {
    @Volatile var sink: ((String) -> Unit)? = null
}
