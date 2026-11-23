package dev.mytv.lan

import dev.mytv.lan.MiniHttpServer.Response

/**
 * Saisie d'un portail depuis le téléphone : la TV affiche un QR code, le téléphone ouvre une
 * page avec un vrai clavier. On peut y coller le lien ou le message du fournisseur, ou remplir
 * les champs. Actif uniquement pendant que l'écran correspondant est ouvert.
 *
 * [onSubmit] reçoit les champs du formulaire et renvoie (succès, message affiché au téléphone).
 */
class PortalWebServer(
    private val onSubmit: (Map<String, String>) -> Pair<Boolean, String>,
    onEvent: (String) -> Unit,
) {
    private val server = MiniHttpServer("mytv-portal-server", MAX_FORM_BYTES, onEvent) { request ->
        when {
            request.method == "GET" && request.path == "/" -> Response.html(PAGE)
            request.method == "POST" && request.path == "/portal" -> {
                val (ok, message) = onSubmit(request.form())
                Response.text(if (ok) 200 else 400, message)
            }
            else -> Response.text(404, "Introuvable")
        }
    }

    val pin: String get() = server.pin

    fun start(): Int? = server.start()

    fun stop() = server.stop()

    private companion object {
        const val MAX_FORM_BYTES = 16 * 1024

        /** Page autonome (aucune ressource externe), lisible sur un petit écran de téléphone. */
        val PAGE = """
<!doctype html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MyTV – Ajouter un portail</title>
<style>
body{font-family:system-ui,sans-serif;background:#0b0f14;color:#f2f5f8;max-width:560px;margin:20px auto;padding:0 16px}
h1{font-size:22px}h2{font-size:17px;margin:4px 0 8px}section{background:#151b23;border-radius:12px;padding:14px 16px;margin:14px 0}
input,textarea,button{font-size:17px;padding:12px;border-radius:8px;border:1px solid #2f80ed;margin:6px 0;width:100%;box-sizing:border-box}
input,textarea{background:#0b0f14;color:#f2f5f8}textarea{min-height:110px}
button{background:#2f80ed;color:#fff;font-weight:600}button:disabled{opacity:.5}
p.small{color:#9aa7b4;font-size:14px;margin:4px 0}label{font-size:14px;color:#9aa7b4}
#msg{white-space:pre-line;font-weight:600;font-size:17px}.ok{color:#3ecf8e}.ko{color:#ff6b6b}
</style></head><body>
<h1>MyTV – Ajouter un portail</h1>
<section><h2>Le plus simple : coller</h2>
<p class="small">Colle le lien ou tout le message envoyé par ton fournisseur (lien get.php, ou « Domain / Username / Password »).</p>
<textarea id="paste" placeholder="http://serveur:8080/get.php?username=…&amp;password=…"></textarea>
<button onclick="send(true)">Envoyer à la TV</button></section>
<section><h2>Ou remplir les champs</h2>
<input id="url" type="url" inputmode="url" autocapitalize="off" autocorrect="off" placeholder="URL du serveur (ex. http://exemple.com:8080)">
<input id="user" autocapitalize="off" autocorrect="off" placeholder="Nom d'utilisateur">
<input id="pass" type="password" placeholder="Mot de passe">
<label><input type="checkbox" style="width:auto" onchange="document.getElementById('pass').type=this.checked?'text':'password'"> Afficher le mot de passe</label>
<input id="name" placeholder="Nom (facultatif)">
<button onclick="send(false)">Envoyer à la TV</button></section>
<p id="msg"></p>
<script>
const pin=new URLSearchParams(location.search).get('pin');
const v=id=>document.getElementById(id).value;
function msg(t,ok){const m=document.getElementById('msg');m.textContent=t;m.className=ok===undefined?'':(ok?'ok':'ko');m.scrollIntoView({behavior:'smooth',block:'center'})}
async function send(paste){
 const f=new URLSearchParams();
 if(paste){if(!v('paste').trim()){msg('Colle d\'abord le lien ou le message.',false);return}f.set('paste',v('paste'))}
 else{f.set('url',v('url'));f.set('user',v('user'));f.set('pass',v('pass'));f.set('name',v('name'))}
 document.querySelectorAll('button').forEach(b=>b.disabled=true);msg('Connexion au portail en cours…');
 try{const r=await fetch('/portal?pin='+pin,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:f.toString()});
  msg(await r.text(),r.ok)}catch(e){msg('La TV ne répond plus. Rouvre l\'écran « Saisir depuis mon téléphone ».',false)}
 document.querySelectorAll('button').forEach(b=>b.disabled=false)}
</script></body></html>
""".trimIndent()
    }
}
