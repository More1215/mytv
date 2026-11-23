package dev.mytv.backup

import dev.mytv.lan.MiniHttpServer
import dev.mytv.lan.MiniHttpServer.Response
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Échange du fichier de sauvegarde avec un PC ou un téléphone du réseau local, actif UNIQUEMENT
 * pendant que l'écran « Sauvegarde » est ouvert (serveur et sécurité : voir [MiniHttpServer]).
 *
 * Pourquoi ce choix : le Fire TV n'a pas de sélecteur de fichiers et Android 11 cloisonne le
 * stockage ; un fichier laissé sur la TV serait quasi impossible à récupérer et disparaîtrait
 * avec l'appli. Ici le fichier part directement sur l'appareil de l'utilisateur. La phrase
 * secrète voyage dans un en-tête (jamais dans l'URL) et le fichier est chiffré (BackupCrypto).
 */
class LocalBackupServer(
    private val onExport: (passphrase: String) -> ByteArray?,
    private val onRestore: (content: ByteArray, passphrase: String) -> String,
    onEvent: (String) -> Unit,
) {
    private val server = MiniHttpServer("mytv-backup-server", MAX_UPLOAD_BYTES, onEvent) { request ->
        val passphrase = request.headers["x-passphrase"]?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
        when {
            request.method == "GET" && request.path == "/" -> Response.html(page())
            request.method == "POST" && request.path == "/export" -> {
                val file = onExport(passphrase)
                if (file == null) {
                    Response.text(400, "Phrase secrète trop courte (8 caractères minimum).")
                } else {
                    val name = "mytv-sauvegarde-" + SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(Date()) + ".mytv"
                    onEvent("Sauvegarde téléchargée sur l'autre appareil.")
                    Response(200, "application/octet-stream", file, "Content-Disposition: attachment; filename=\"$name\"")
                }
            }
            request.method == "POST" && request.path == "/restore" -> Response.text(200, onRestore(request.body, passphrase))
            else -> Response.text(404, "Introuvable")
        }
    }

    val pin: String get() = server.pin

    /** Démarre l'écoute et renvoie le port, ou null si impossible. */
    fun start(): Int? = server.start()

    fun stop() = server.stop()

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
        private const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024

        fun localAddress(): String? = MiniHttpServer.localAddress()
    }
}
