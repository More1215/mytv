package dev.mytv.lan

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
import kotlin.concurrent.thread

/**
 * Mini serveur web du réseau local, partagé par la sauvegarde et la saisie d'un portail depuis
 * le téléphone. Il ne vit que pendant que l'écran concerné est ouvert.
 *
 * Volontairement minimal (aucune bibliothèque) : un seul thread, une requête à la fois,
 * quelques Ko de mémoire. Suffisant pour un usage ponctuel par une seule personne, et sans
 * surcoût sur les petites machines.
 *
 * Sécurité :
 * - toutes les requêtes exigent le code à 6 chiffres affiché sur la TV (comparaison à temps
 *   constant) ; après [MAX_BAD_PIN] erreurs, le serveur s'arrête ;
 * - les secrets voyagent dans le corps ou les en-têtes, jamais dans les logs ;
 * - taille des envois limitée.
 * Il n'écoute que le réseau local : le kill switch VPN, qui ne porte que sur les connexions
 * SORTANTES vers Internet, ne le concerne pas.
 */
class MiniHttpServer(
    private val threadName: String,
    private val maxBodyBytes: Int,
    private val onEvent: (String) -> Unit,
    private val handler: (Request) -> Response,
) {
    class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        /** Corps envoyé en application/x-www-form-urlencoded (formulaire HTML). */
        fun form(): Map<String, String> = parsePairs(body.toString(Charsets.UTF_8))
    }

    class Response(val code: Int, val type: String, val body: ByteArray, val extraHeader: String? = null) {
        companion object {
            fun text(code: Int, text: String) = Response(code, "text/plain; charset=utf-8", text.toByteArray())
            fun html(html: String) = Response(200, "text/html; charset=utf-8", html.toByteArray())
        }
    }

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
        } catch (_: IOException) {
            return null
        }
        server = socket
        thread(name = threadName, isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: SocketException) { break }
                runCatching { client.use { handle(it) } }
            }
        }
        return PORT
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0).orEmpty()
        val target = parts.getOrNull(1) ?: "/"
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val query = parsePairs(target.substringAfter('?', ""))
        val out = socket.getOutputStream()

        if (!checkPin(query["pin"] ?: headers["x-pin"])) {
            respond(out, Response.text(403, "Code incorrect."))
            if (++badPins >= MAX_BAD_PIN) {
                onEvent("Trop de codes incorrects : partage arrêté par sécurité.")
                stop()
            }
            return
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > maxBodyBytes) {
            respond(out, Response.text(413, "Envoi trop volumineux."))
            return
        }
        val body = if (method == "POST" && length > 0) readBody(input, length) else ByteArray(0)
        respond(out, handler(Request(method, target.substringBefore('?'), headers, body)))
    }

    private fun checkPin(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), pin.toByteArray())

    private fun respond(out: OutputStream, r: Response) {
        val status = when (r.code) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 ${r.code} $status\r\n")
            append("Content-Type: ${r.type}\r\n")
            append("Content-Length: ${r.body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            r.extraHeader?.let { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray())
        out.write(r.body)
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

    companion object {
        const val PORT = 8765
        private const val MAX_BAD_PIN = 5

        private fun parsePairs(q: String): Map<String, String> =
            q.split('&').filter { it.contains('=') }.associate {
                decode(it.substringBefore('=')) to decode(it.substringAfter('='))
            }

        private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        /**
         * Adresse de la TV sur le réseau local (Wi-Fi ou Ethernet). On ignore les interfaces VPN
         * (tun/ppp) : c'est l'adresse LOCALE qu'il faut ouvrir sur le téléphone, pas celle du tunnel.
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
