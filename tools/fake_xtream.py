"""
Faux portail Xtream Codes pour tester l'appli sans vrais identifiants.

Lancement :   python tools/fake_xtream.py            (port 8080 par défaut)
Depuis l'émulateur Android, le PC est joignable à l'adresse http://10.0.2.2:8080

Identifiants acceptés :
  demo / demo        -> compte actif
  expired / expired  -> compte expiré
  slow / slow        -> répond après 60 s (test du timeout)
Tout autre couple   -> auth = 0 (identifiants refusés)
Chemin /html/...    -> page HTML (test « ce n'est pas un portail Xtream »)

Contenu simulé : 4 catégories, 40 chaînes, guide XMLTV (-3 h / +26 h), get_short_epg.
Flux vidéo : si FFmpeg est installé, des vidéos de test (mires colorées numérotées) sont générées
au premier lancement dans tools/test_streams/ (ignoré par git). La chaîne 13 renvoie une erreur
404 pour tester l'affichage d'erreur ; les chaînes 31 à 40 n'ont pas d'EPG.

VOD : 24 films (3 catégories) et 6 séries (2 catégories, 1 à 3 saisons), avec affiches générées
et fichiers MP4 de 3 min servis avec support Range (avance rapide).
"""
import base64
import json
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

ACCOUNTS = {
    ("demo", "demo"): "Active",
    ("expired", "expired"): "Expired",
    ("slow", "slow"): "Active",
}

CATEGORIES = [("1", "Information"), ("2", "Sport"), ("3", "Cinéma"), ("4", "Jeunesse")]
CHANNEL_NAMES = {
    "1": ["Info Direct", "Le Fil Actu", "Monde 24", "Éco Matin", "Météo Plus", "Débat TV", "Info Région", "Flash Info", "Planète News", "Actu Continue"],
    "2": ["Sport 1", "Sport 2", "Foot Live", "Tennis Club", "Moto GP TV", "Rugby XV", "Golf Channel", "Basket Zone", "Ski Alpin", "Vélo Tour"],
    "3": ["Ciné Classic", "Ciné Action", "Ciné Comédie", "Ciné Frisson", "Ciné Famille", "Séries Max", "Polar TV", "Ciné Club", "Western HD", "Ciné Nuit"],
    "4": ["Kids Fun", "Dessins Animés", "Junior TV", "Ado Max", "Toon World", "Mini Kids", "Découverte Jr", "Kids Music", "Animaux TV", "Contes TV"],
}
CHANNELS = []  # (stream_id, num, name, category_id, epg_id)
for cat_index, (cat_id, _) in enumerate(CATEGORIES):
    for i, name in enumerate(CHANNEL_NAMES[cat_id]):
        num = cat_index * 10 + i + 1
        epg_id = None if num > 30 else f"ch{num}.fake"
        CHANNELS.append((1000 + num, num, name, cat_id, epg_id))

SHOWS = ["Journal", "Magazine", "Documentaire", "Le Grand Direct", "Film du soir", "Série inédite",
         "Talk-show", "Reportage", "Dessin animé", "Match en direct", "Rediffusion", "Jeu télé"]

# --- VOD : 3 catégories de films (8 films chacune), 2 catégories de séries (3 séries chacune)
VOD_CATEGORIES = [("10", "Action"), ("11", "Comédie"), ("12", "Documentaires")]
MOVIE_TITLES = {
    "10": ["Course Contre la Nuit", "L'Évasion", "Opération Tonnerre", "Le Dernier Rempart",
           "Vitesse Maximale", "Zone Rouge", "L'Infiltré", "Fracture"],
    "11": ["Un Été Chez Mamie", "Les Voisins d'En Face", "Mariage Express", "Le Grand Saut",
           "Vacances Ratées", "Trois Frères et Demi", "Le Coloc", "Bienvenue au Camping"],
    "12": ["Océans Profonds", "La Vie des Abeilles", "Montagnes du Monde", "Le Secret des Volcans",
           "Planète Glace", "Les Grands Fauves", "Cités Perdues", "L'Odyssée des Oiseaux"],
}
MOVIES = []  # (stream_id, title, category_id, year)
for c_index, (cat_id, _) in enumerate(VOD_CATEGORIES):
    for i, title in enumerate(MOVIE_TITLES[cat_id]):
        MOVIES.append((2000 + c_index * 10 + i, title, cat_id, 2015 + (i * 3 + c_index) % 10))

SERIES_CATEGORIES = [("20", "Séries Drame"), ("21", "Séries Animées")]
SERIES = [  # (series_id, titre, catégorie, saisons, épisodes par saison)
    (3001, "Les Gardiens", "20", 3, 4), (3002, "Hôpital Central", "20", 2, 5), (3003, "Brigade Nord", "20", 2, 4),
    (3011, "Petits Héros", "21", 2, 6), (3012, "Robots Rigolos", "21", 1, 8), (3013, "La Forêt Magique", "21", 2, 4),
]
VOD_DURATION_SEC = 180  # durée réelle des fichiers de test


def episode_id(series_id, season, number):
    return series_id * 100 + season * 10 + number


def find_movie(stream_id):
    return next((m for m in MOVIES if m[0] == stream_id), None)


def find_series(series_id):
    return next((s for s in SERIES if s[0] == series_id), None)

HERE = os.path.dirname(os.path.abspath(__file__))
STREAM_DIR = os.path.join(HERE, "test_streams")
STREAM_VARIANTS = 6
COLORS = ["0x1f6feb", "0x2ea043", "0xd29922", "0xbf3989", "0x8957e5", "0xcf222e"]


def ensure_test_streams():
    """Génère 6 vidéos TS de 3 min (mire + horloge) ; le flux d'une chaîne = variante num % 6."""
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        print("FFmpeg introuvable : les flux vidéo renverront 404.")
        return
    os.makedirs(STREAM_DIR, exist_ok=True)
    for v in range(STREAM_VARIANTS):
        out = os.path.join(STREAM_DIR, f"variant{v}.ts")
        if os.path.exists(out):
            continue
        print(f"Génération de {out}...")
        label = f"FLUX {chr(65 + v)}"
        vf = (f"drawtext=text='{label}':fontsize=96:fontcolor=white:x=(w-tw)/2:y=(h-th)/2-60,"
              f"drawtext=text='%{{pts\\:hms}}':fontsize=56:fontcolor=white:x=(w-tw)/2:y=(h-th)/2+80")
        cmd = [ffmpeg, "-y", "-loglevel", "error",
               "-f", "lavfi", "-i", f"color=c={COLORS[v]}:s=1280x720:r=25:d=180",
               "-f", "lavfi", "-i", f"sine=frequency={300 + v * 80}:duration=180",
               "-vf", vf,
               "-c:v", "libx264", "-preset", "veryfast", "-b:v", "800k", "-g", "50",
               "-c:a", "aac", "-b:a", "96k", "-f", "mpegts", out]
        subprocess.run(cmd, check=True)


def ensure_vod_assets():
    """
    Pour la VOD : copies MP4 « faststart » des vidéos de test (avance rapide possible, comme un
    vrai film) et affiches PNG portant le titre du film ou de la série.
    """
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        return
    for v in range(STREAM_VARIANTS):
        out = os.path.join(STREAM_DIR, f"vod{v}.mp4")
        if os.path.exists(out):
            continue
        print(f"Génération de {out}...")
        # Encodage direct en MP4 (et non remux du TS) : un remux TS->MP4 laisse des images que
        # ExoPlayer rejette après une avance rapide (« Invalid NAL length »).
        label = f"FILM {chr(65 + v)}"
        vf = (f"drawtext=text='{label}':fontsize=96:fontcolor=white:x=(w-tw)/2:y=(h-th)/2-60,"
              f"drawtext=text='%{{pts\\:hms}}':fontsize=56:fontcolor=white:x=(w-tw)/2:y=(h-th)/2+80")
        subprocess.run([ffmpeg, "-y", "-loglevel", "error",
                        "-f", "lavfi", "-i", f"color=c={COLORS[v]}:s=1280x720:r=25:d={VOD_DURATION_SEC}",
                        "-f", "lavfi", "-i", f"sine=frequency={300 + v * 80}:duration={VOD_DURATION_SEC}",
                        "-vf", vf, "-c:v", "libx264", "-preset", "veryfast", "-b:v", "800k", "-g", "50",
                        "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", out], check=True)
    posters = [(f"m{m[0]}", m[1], m[0]) for m in MOVIES] + [(f"s{s[0]}", s[1], s[0]) for s in SERIES]
    for name, title, seed in posters:
        out = os.path.join(STREAM_DIR, f"{name}.png")
        if os.path.exists(out):
            continue
        # Apostrophes et deux-points ont un sens spécial pour drawtext : on les retire.
        safe = title.replace("'", " ").replace(":", " ")
        words = safe.split()
        lines = [" ".join(words[i:i + 2]) for i in range(0, len(words), 2)]
        draws = ",".join(
            f"drawtext=text='{line}':fontsize=34:fontcolor=white:x=(w-tw)/2:y=(h/2-60)+{k * 46}"
            for k, line in enumerate(lines)
        )
        subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-f", "lavfi",
                        "-i", f"color=c={COLORS[seed % len(COLORS)]}:s=300x450:d=1",
                        "-vf", draws, "-frames:v", "1", out], check=True)


def xmltv_time(ts):
    return datetime.fromtimestamp(ts, timezone.utc).strftime("%Y%m%d%H%M%S +0000")


def programs_for(num, start, end):
    """Programmes déterministes de 30 à 90 min pour une chaîne, alignés sur la demi-heure."""
    t = start - (start % 1800)
    i = 0
    while t < end:
        duration = 1800 * (1 + (num + i) % 3)
        title = f"{SHOWS[(num * 7 + i) % len(SHOWS)]} #{i + 1}"
        yield title, f"Description de « {title} » sur la chaîne {num}.", t, t + duration
        t += duration
        i += 1


def build_xmltv():
    now = int(time.time())
    parts = ['<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="fake_xtream">']
    for _, num, name, _, epg_id in CHANNELS:
        if epg_id:
            parts.append(f'<channel id="{epg_id}"><display-name>{name}</display-name></channel>')
    # Chaîne inconnue du catalogue : doit être ignorée par le parseur.
    parts.append('<programme start="20200101000000 +0000" stop="20200101010000 +0000" channel="inconnue"><title>X</title></programme>')
    for _, num, _, _, epg_id in CHANNELS:
        if not epg_id:
            continue
        for title, desc, s, e in programs_for(num, now - 3 * 3600, now + 26 * 3600):
            parts.append(f'<programme start="{xmltv_time(s)}" stop="{xmltv_time(e)}" channel="{epg_id}">'
                         f'<title lang="fr">{title}</title><desc lang="fr">{desc}</desc></programme>')
    parts.append("</tv>")
    return "\n".join(parts)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path.startswith("/html"):
            return self._send(200, "<html><body>Maintenance</body></html>", "text/html")
        if url.path.startswith("/live/"):
            return self._stream(url.path)
        if url.path.startswith("/movie/") or url.path.startswith("/series/"):
            return self._vod_file(url.path)
        if url.path.startswith("/img/"):
            return self._image(url.path)

        login = (q.get("username", ""), q.get("password", ""))
        status = ACCOUNTS.get(login)
        if url.path.endswith("xmltv.php"):
            if status is None:
                return self._send(401, "", "text/plain")
            return self._send(200, build_xmltv(), "application/xml")
        if not url.path.endswith("player_api.php"):
            return self._send(404, "not found", "text/plain")
        if login[0] == "slow":
            time.sleep(60)
        if status is None:
            return self._json({"user_info": {"auth": 0}})

        action = q.get("action")
        if action == "get_live_categories":
            return self._json([{"category_id": c, "category_name": n, "parent_id": 0} for c, n in CATEGORIES])
        if action == "get_live_streams":
            # Types volontairement mélangés (int / str), comme sur de vrais panels.
            return self._json([{
                "num": num, "name": name, "stream_type": "live", "stream_id": sid,
                "stream_icon": "", "epg_channel_id": epg_id, "added": "1700000000",
                "category_id": cat if num % 2 else int(cat), "tv_archive": 0,
            } for sid, num, name, cat, epg_id in CHANNELS])
        if action == "get_short_epg":
            sid = int(q.get("stream_id", "0"))
            num = next((c[1] for c in CHANNELS if c[0] == sid), 0)
            now = int(time.time())
            listings = [{
                "title": base64.b64encode(f"(court) {t}".encode()).decode(),
                "description": base64.b64encode(d.encode()).decode(),
                "start_timestamp": str(s), "stop_timestamp": str(e),
            } for t, d, s, e in programs_for(num, now - 3600, now + 4 * 3600)][:4]
            return self._json({"epg_listings": listings})

        vod = self._vod_api(action, q)
        if vod is not None:
            return self._json(vod)

        return self._json({
            "user_info": {
                "username": login[0], "auth": 1, "status": status,
                "exp_date": str(int(time.time()) + 90 * 86400) if status == "Active" else "1600000000",
                "is_trial": "0", "active_cons": "0", "max_connections": "2",
                "allowed_output_formats": ["m3u8", "ts"],
            },
            "server_info": {"url": "10.0.2.2", "port": "8080", "server_protocol": "http",
                            "timezone": "Europe/Paris", "timestamp_now": int(time.time())},
        })

    def _stream(self, path):
        # /live/{user}/{pass}/{stream_id}.ts
        parts = path.strip("/").split("/")
        if len(parts) != 4 or (parts[1], parts[2]) not in ACCOUNTS:
            return self._send(403, "", "text/plain")
        sid = int(parts[3].split(".")[0])
        num = sid - 1000
        file = os.path.join(STREAM_DIR, f"variant{num % STREAM_VARIANTS}.ts")
        if num == 13 or not os.path.exists(file):
            return self._send(404, "", "text/plain")
        # Comme un vrai serveur IPTV : flux sans fin (pas de Content-Length, connexion fermée à la
        # fin), envoyé au rythme de la vidéo après une courte rafale initiale (démarrage rapide).
        self.send_response(200)
        self.send_header("Content-Type", "video/mp2t")
        self.send_header("Connection", "close")
        self.end_headers()
        bytes_per_sec = 1_100_000 / 8 * 1.3   # ~débit du fichier + 30 % de marge
        burst = 1_500_000
        sent = 0
        started = time.monotonic()
        try:
            while True:
                with open(file, "rb") as f:
                    while chunk := f.read(64 * 1024):
                        self.wfile.write(chunk)
                        sent += len(chunk)
                        if sent > burst:
                            ahead = (sent - burst) / bytes_per_sec - (time.monotonic() - started)
                            if ahead > 0:
                                time.sleep(ahead)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass  # zap : le client ferme la connexion, c'est normal

    def _base(self):
        """URL de base vue par le client (pour les affiches), d'après l'en-tête Host."""
        return f"http://{self.headers.get('Host', '10.0.2.2:8080')}"

    def _vod_api(self, action, q):
        base = self._base()
        if action == "get_vod_categories":
            return [{"category_id": c, "category_name": n, "parent_id": 0} for c, n in VOD_CATEGORIES]
        if action == "get_vod_streams":
            return [{
                "num": i + 1, "name": title, "stream_type": "movie", "stream_id": sid,
                "stream_icon": f"{base}/img/m{sid}.png", "rating": str(5 + sid % 5) + ".5",
                "added": str(1700000000 + sid), "category_id": cat, "container_extension": "mp4",
            } for i, (sid, title, cat, year) in enumerate(MOVIES)]
        if action == "get_vod_info":
            m = find_movie(int(q.get("vod_id", "0")))
            if not m:
                return {"info": [], "movie_data": {}}
            sid, title, cat, year = m
            return {
                "info": {
                    "movie_image": f"{base}/img/m{sid}.png", "plot": f"Résumé de « {title} ». Un film de test de {VOD_DURATION_SEC // 60} minutes.",
                    "genre": dict(VOD_CATEGORIES)[cat], "director": "Réalisateur Test", "cast": "Acteur Un, Actrice Deux",
                    "releasedate": f"{year}-06-01", "duration_secs": VOD_DURATION_SEC, "duration": "00:03:00",
                    "rating": str(5 + sid % 5) + ",5",
                },
                "movie_data": {"stream_id": sid, "name": title, "container_extension": "mp4", "category_id": cat},
            }
        if action == "get_series_categories":
            return [{"category_id": c, "category_name": n, "parent_id": 0} for c, n in SERIES_CATEGORIES]
        if action == "get_series":
            return [{"series_id": sid, "name": title, "cover": f"{base}/img/s{sid}.png",
                     "rating": "8", "category_id": cat} for sid, title, cat, _, _ in SERIES]
        if action == "get_series_info":
            s = find_series(int(q.get("series_id", "0")))
            if not s:
                return {"info": [], "episodes": {}}
            sid, title, cat, seasons, per = s
            # Format le plus courant : épisodes indexés par numéro de saison (clés texte).
            episodes = {str(se): [{
                "id": str(episode_id(sid, se, n)), "episode_num": n, "season": se,
                "title": f"Épisode {n}", "container_extension": "mp4",
                "info": {"plot": f"Saison {se}, épisode {n} de « {title} ».", "duration_secs": VOD_DURATION_SEC},
            } for n in range(1, per + 1)] for se in range(1, seasons + 1)}
            return {
                "info": {"name": title, "cover": f"{base}/img/s{sid}.png", "plot": f"Résumé de la série « {title} ».",
                         "genre": dict(SERIES_CATEGORIES)[cat], "cast": "Distribution Test", "releaseDate": "2022-01-01"},
                "seasons": [{"season_number": se, "name": f"Saison {se}"} for se in range(1, seasons + 1)],
                "episodes": episodes,
            }
        return None

    def _image(self, path):
        file = os.path.join(STREAM_DIR, os.path.basename(path))
        if not os.path.exists(file):
            return self._send(404, "", "text/plain")
        with open(file, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", "image/png")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "max-age=86400")
        self.end_headers()
        self.wfile.write(data)

    def _vod_file(self, path):
        """Film ou épisode : fichier MP4 complet avec support des requêtes Range (avance rapide)."""
        parts = path.strip("/").split("/")
        if len(parts) != 4 or (parts[1], parts[2]) not in ACCOUNTS:
            return self._send(403, "", "text/plain")
        content_id = int(parts[3].split(".")[0])
        file = os.path.join(STREAM_DIR, f"vod{content_id % STREAM_VARIANTS}.mp4")
        if not os.path.exists(file):
            return self._send(404, "", "text/plain")
        size = os.path.getsize(file)
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes="):
            a, _, b = rng[len("bytes="):].partition("-")
            start = int(a) if a else 0
            end = int(b) if b else size - 1
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        try:
            with open(file, "rb") as f:
                f.seek(start)
                remaining = end - start + 1
                while remaining > 0 and (chunk := f.read(min(64 * 1024, remaining))):
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def _json(self, body):
        self._send(200, json.dumps(body, ensure_ascii=False), "application/json")

    def _send(self, code, text, ctype):
        data = text.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype + "; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        # Les identifiants sont dans l'URL : on ne logge que l'action ou le type de ressource.
        url = urlparse(self.path)
        action = parse_qs(url.query).get("action", [""])[0]
        kind = "live" if url.path.startswith("/live/") else url.path
        sys.stderr.write(f"{self.command} {kind} {action}\n")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    ensure_test_streams()
    ensure_vod_assets()
    print(f"Faux portail Xtream sur http://0.0.0.0:{port} (émulateur : http://10.0.2.2:{port})")
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
