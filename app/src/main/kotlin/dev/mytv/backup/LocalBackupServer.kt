package dev.mytv.backup

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Mini serveur web, actif UNIQUEMENT pendant que l'écran « Sauvegarde » est ouvert, pour
 * échanger le fichier de sauvegarde avec un PC ou un téléphone du même réseau local.
 *
 * Pourquoi ce choix : le Fire TV n'a pas de sélecteur de fichiers et Android 11 cloisonne le
 * stockage ; un fichier laissé sur la TV serait quasi impossible à récupérer et disparaîtrait
 * avec l'appli. Ici le fichier part directement sur l'appareil de l'utilisateur.
 *
 * Sécurité :
 * - toutes les requêtes exigent le code à 6 chiffres affiché sur la TV (comparaison à temps
 *   constant) ; après [MAX_BAD_PIN] erreurs, le serveur s'arrête ;
 * - la phrase secrète voyage dans le corps de la requête (jamais dans l'URL ni les logs) ;
 * - le fichier est de toute façon chiffré (voir BackupCrypto) ; taille d'envoi limitée.
 * Le serveur n'écoute que le réseau local : il n'est pas concerné par le kill switch VPN,
 * qui ne porte que sur les connexions SORTANTES vers Internet.
 */
class LocalBackupServer(
    private val onExport: (passphrase: String) -> ByteArray?,
    private val onRestore: (content: ByteArray, passphrase: String) -> String,
    private val onEvent: (String) -> Unit,
) {
    val pin: String = (SecureRandom().nextInt(900_000) + 100_000).toString()
    private var server: ServerSocket? = null
    @Volatile private var badPins = 0

    /** Démarre l'écoute et renvoie le port, ou null si impossible. */
    fun start(): Int? {
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        } catch (e: IOException) {
            return null
        }
        server = socket
        thread(name = "mytv-backup-server", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: SocketException) { break }
                // Une requête à la fois : usage ponctuel par une seule personne.
                runCatching { client.use { handle(it) } }
            }
        }
        return PORT
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    // ------------------------------------------------------------------ HTTP minimal

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val (method, target) = requestLine.split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val out = socket.getOutputStream()

        if (!checkPin(query["pin"] ?: headers["x-pin"])) {
            respond(out, 403, "text/plain; charset=utf-8", "Code incorrect.".toByteArray())
            if (++badPins >= MAX_BAD_PIN) {
                onEvent("Trop de codes incorrects : partage arrêté par sécurité.")
                stop()
            }
            return
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_UPLOAD_BYTES) {
            respond(out, 413, "text/plain; charset=utf-8", "Fichier trop volumineux.".toByteArray())
            return
        }
        val body = if (method == "POST" && length > 0) readBody(input, length) else ByteArray(0)
        val passphrase = headers["x-passphrase"]?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()

        when {
            method == "GET" && path == "/" -> respond(out, 200, "text/html; charset=utf-8", page().toByteArray())
            method == "POST" && path == "/export" -> {
                val file = onExport(passphrase)
                if (file == null) {
                    respond(out, 400, "text/plain; charset=utf-8", "Phrase secrète trop courte (8 caractères minimum).".toByteArray())
                } else {
                    val name = "mytv-sauvegarde-" + SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(Date()) + ".mytv"
                    respond(out, 200, "application/octet-stream", file, "Content-Disposition: attachment; filename=\"$name\"")
                    onEvent("Sauvegarde téléchargée sur l'autre appareil.")
                }
            }
            method == "POST" && path == "/restore" -> {
                val message = onRestore(body, passphrase)
                respond(out, 200, "text/plain; charset=utf-8", message.toByteArray())
            }
            else -> respond(out, 404, "text/plain; charset=utf-8", "Introuvable".toByteArray())
        }
    }

    private fun checkPin(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), pin.toByteArray())

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray, extraHeader: String? = null) {
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Error" }
        val head = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            extraHeader?.let { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (b == '\n'.code) return buffer.toString("UTF-8").trimEnd('\r')
            buffer.write(b)
            if (buffer.size() > 8_192) return null // ligne d'en-tête anormalement longue
        }
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n == -1) break
            read += n
        }
        return data.copyOf(read)
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    /** Page affichée sur le PC / téléphone. Autonome (aucune ressource externe). */
    private fun page(): String = """
<!doctype html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MyTV – Sauvegarde</title>
<style>
body{font-family:system-ui,sans-serif;background:#0b0f14;color:#f2f5f8;max-width:640px;margin:24px auto;padding:0 16px}
h1{font-size:24px}section{background:#151b23;border-radius:12px;padding:16px 20px;margin:16px 0}
input,button{font-size:16px;padding:10px;border-radius:8px;border:1px solid #2f80ed;margin:6px 0;width:100%;box-sizing:border-box}
input{background:#0b0f14;color:#f2f5f8}button{background:#2f80ed;color:#fff;cursor:pointer}
p.small{color:#9aa7b4;font-size:14px}#msg{white-space:pre-line;font-weight:600}
</style></head><body>
<h1>MyTV – Sauvegarde et restauration</h1>
<p class="small">La sauvegarde contient les portails (mots de passe compris), favoris, reprises de lecture et réglages.
Elle est chiffrée avec ta phrase secrète : <b>sans elle, impossible de la relire</b>. Note-la bien.</p>
<section><h2>Télécharger une sauvegarde</h2>
<input id="p1" type="password" placeholder="Phrase secrète (8 caractères minimum)">
<button onclick="exp()">Télécharger</button></section>
<section><h2>Restaurer une sauvegarde</h2>
<input id="f" type="file" accept=".mytv,application/octet-stream">
<input id="p2" type="password" placeholder="Phrase secrète utilisée à la sauvegarde">
<button onclick="res()">Restaurer sur la TV</button>
<p class="small">Les portails déjà présents sont mis à jour, rien n'est effacé.</p></section>
<p id="msg"></p>
<script>
const pin=new URLSearchParams(location.search).get('pin');
const msg=t=>document.getElementById('msg').textContent=t;
async function exp(){const p=document.getElementById('p1').value;if(p.length<8){msg('Phrase secrète trop courte (8 caractères minimum).');return}
 const r=await fetch('/export?pin='+pin,{method:'POST',headers:{'X-Passphrase':encodeURIComponent(p)}});
 if(!r.ok){msg(await r.text());return}
 const b=await r.blob();const a=document.createElement('a');a.href=URL.createObjectURL(b);
 a.download=(r.headers.get('Content-Disposition')||'').split('filename="')[1]?.replace('"','')||'mytv.mytv';a.click();msg('Sauvegarde téléchargée.')}
async function res(){const f=document.getElementById('f').files[0];const p=document.getElementById('p2').value;
 if(!f){msg('Choisis un fichier de sauvegarde.');return}
 const r=await fetch('/restore?pin='+pin,{method:'POST',headers:{'X-Passphrase':encodeURIComponent(p)},body:await f.arrayBuffer()});
 msg(await r.text())}
</script></body></html>
""".trimIndent()

    companion object {
        const val PORT = 8765
        private const val MAX_BAD_PIN = 5
        private const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024

        /**
         * Adresse de la TV sur le réseau local (Wi-Fi ou Ethernet). On ignore les interfaces VPN
         * (tun/ppp) : c'est l'adresse LOCALE qu'il faut taper sur le PC, pas celle du tunnel.
         */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("ppp") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
