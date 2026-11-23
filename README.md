# MyTV : lecteur IPTV pour Android TV / Fire TV

Lecteur IPTV natif (portails **Xtream Codes**) pensé pour la télécommande, ciblant le
**Fire TV Stick 4K Plus** (Fire OS 8 / Android 11, 2 Go de RAM).

> Usage personnel. L'appli ne fournit aucun contenu : elle se connecte au portail de ton fournisseur.

## État d'avancement

| Étape | Contenu | État |
|---|---|---|
| 0 | Projet, architecture, écran « Ajouter un portail », stockage chiffré | ✅ |
| 1 | Live TV : catégories, chaînes, lecteur, zapping, EPG, favoris, cache | ✅ (à valider sur Fire TV) |
| 2 | VOD : films, séries, reprise de lecture, recherche | ✅ (à valider sur Fire TV) |
| 3 | Multi-portails, réglages lecteur, sauvegarde/restauration | ✅ (à valider sur Fire TV) |

## Stack

Kotlin · Jetpack Compose for TV (`androidx.tv:tv-material`) · Media3/ExoPlayer · Coroutines/Flow ·
Retrofit + OkHttp + kotlinx.serialization · Room · DataStore · Hilt · Coil.
Toutes les versions sont centralisées dans [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

- minSdk 25, targetSdk 36, compileSdk 37.2
- AGP 9.5, Gradle 9.9 (via le wrapper), Kotlin 2.4
- **JDK 17** requis

## Architecture

Clean architecture en 3 modules Gradle. Le sens des dépendances est garanti par le compilateur :

```
:app     UI Compose TV, ViewModels (MVVM), navigation, Hilt
  │
  ▼
:domain  Kotlin pur (aucune dépendance Android) : modèles, use cases, interfaces de repositories
  ▲
  │
:data    API Xtream, Room, DataStore, chiffrement, implémentations des repositories
```

- **Pas de réseau dans l'UI** : les ViewModels n'appellent que des use cases du domain.
- **Erreurs typées** : toute exception technique (timeout, DNS, HTTP, JSON) est traduite une seule
  fois en `AppError` (`data/.../error/ErrorMapper.kt`). L'UI affiche un message clair pour chaque cas.
- **Sources interchangeables** : les repositories passent par l'interface `IptvSource`. Ajouter le
  support M3U/XMLTV revient à écrire un `M3uSource` et à l'enregistrer dans `IptvSourceFactory`.
- **Parsing tolérant** : les panels Xtream renvoient un même champ tantôt en `"1"`, tantôt en `1`,
  `true` ou `null`. `FlexibleStringSerializer` absorbe ces variations.

### Navigation à la télécommande

Le composant `TvTextField` résout le piège classique des champs texte sur TV. Le champ ne prend le
clavier qu'après **OK**. Les flèches passent d'un champ à l'autre, **Retour** ou **Haut/Bas**
referment le clavier. Le focus initial est toujours posé explicitement à l'ouverture de chaque écran.

## Utilisation à la télécommande

**Accueil** : quatre tuiles (Live TV, Films, Séries, Recherche), puis « Accès rapide » : tes 2
chaînes les plus regardées (comptées après 20 s de visionnage, le zapping ne compte pas) et les
2 derniers films ou épisodes commencés. Les reprises suivantes sont dans « Continuer à regarder ». Au lancement, l'appli relance la dernière chaîne : Retour ramène à la liste des
chaînes, puis à l'accueil.

**Films / Séries** : catégories à gauche (la grille suit la catégorie survolée), affiches à droite.
Une barre sous l'affiche montre où l'on s'est arrêté.
- **Fiche film** : « ▶ Reprendre à 42:10 » ou « ▶ Lecture », et « Depuis le début ».
- **Fiche série** : le bouton principal reprend le dernier épisode commencé, ou propose l'épisode
  suivant celui terminé. Les saisons s'affichent au survol, et chaque épisode montre sa progression.

**Lecteur films / épisodes**

| Touche | Action |
|---|---|
| OK ou ⏯ | pause / lecture |
| Gauche / ⏪ | -10 s · Droite / ⏩ : +30 s (les appuis rapprochés se cumulent) |
| Haut / Bas | afficher les commandes |
| Retour | quitter (la position est enregistrée) |

En fin d'épisode, l'épisode suivant démarre après 10 s, sauf si tu choisis « Annuler ».

**Recherche** : OK pour ouvrir le clavier, puis tape au moins 2 lettres. La recherche porte sur
les films et les séries en cache (aucune requête réseau), sans tenir compte des accents ni des
majuscules : « ete » trouve « Un Été… ».

**Réglages** (bouton « Réglages » de l'accueil ou de la Live TV)
- **Portails** : ajouter un portail, choisir le portail actif (OK), en supprimer un (une
  confirmation est demandée, le focus est sur « Annuler »). Chaque portail garde son cache,
  ses favoris et ses reprises.
- **Lecteur** : OK fait défiler les choix, appliqués tout de suite, même pendant la lecture.
  - Format des chaînes : MPEG-TS (zapping le plus rapide) ou HLS (plus tolérant aux coupures).
  - Mémoire tampon : Démarrage rapide (~1 s), Équilibré, ou Stabilité (~5 s, connexions lentes).
  - Décodage vidéo : Matériel (recommandé) ou Logiciel (si une chaîne affiche une image cassée).
  - Relancer la dernière chaîne au démarrage : Oui / Non.
- **Sauvegarde et restauration** : voir plus bas.

### Sauvegarde et restauration

Sert à changer de Fire TV ou à réinstaller l'appli sans tout ressaisir.

1. Ouvre **Réglages → Sauvegarde et restauration** (ou, sur une installation neuve,
   « Restaurer une sauvegarde » sur l'écran d'ajout de portail).
2. La TV affiche une adresse du type `http://192.168.1.20:8765/?pin=123456`. Ouvre-la dans le
   navigateur d'un PC ou d'un téléphone **connecté au même Wi-Fi**.
3. **Télécharger** : choisis une phrase secrète (8 caractères minimum). Tu obtiens un fichier
   `.mytv` contenant les portails, favoris et reprises.
   **Restaurer** : envoie ce fichier avec sa phrase secrète. Les portails déjà présents ne sont
   pas dupliqués.
4. Reviens à l'accueil avec « Terminé ».

Sécurité :
- Le fichier est chiffré (PBKDF2 210 000 itérations + AES-256-GCM). Sans la phrase secrète, il
  est illisible. **Une phrase oubliée = sauvegarde perdue.**
- Le partage n'existe que pendant que l'écran est ouvert, uniquement sur le réseau local, et
  demande le code à 6 chiffres (5 essais faux maximum, puis il se ferme).
- Rien ne passe par Internet ni par un service tiers.

### Règles de reprise de lecture
- La position est enregistrée toutes les 15 s, à la pause, en quittant le lecteur et quand le
  Fire TV passe en veille.
- En dessous de 30 s, aucune reprise n'est proposée.
- Au-delà de 95 % (générique de fin), le contenu est considéré comme vu : il sort de « Continuer
  à regarder », et pour une série, l'épisode suivant y entre.

**Écran Live TV** : catégories à gauche, chaînes au centre, aperçu vidéo et programme à droite.

| Touche | Action |
|---|---|
| Haut / Bas | parcourir les catégories ou les chaînes |
| Gauche / Droite | passer d'une colonne à l'autre |
| OK | regarder la chaîne en plein écran |
| Appui long sur OK | ajouter ou retirer des favoris |
| Retour | depuis les chaînes : revenir aux catégories ; depuis les catégories : quitter |

**Lecteur plein écran**

| Touche | Action |
|---|---|
| Haut / CH+ | chaîne suivante · Bas / CH- : chaîne précédente |
| Gauche ou OK | sélecteur de chaînes : catégories à gauche, chaînes à droite, ouvert sur la chaîne en cours |
| Droite | afficher les infos du programme et l'heure |
| Menu (☰) | ajouter ou retirer des favoris |
| Retour | ferme le sélecteur, sinon revient à la liste (la lecture continue en aperçu) |

Les heures sont toujours affichées sur 24 h (14:30), et une horloge est visible sur l'écran Live,
dans le guide et dans le bandeau du lecteur.

**Guide TV** (bouton en haut à droite de l'écran Live) : grille des programmes de la
catégorie affichée. Les flèches déplacent le focus, et la fenêtre horaire glisse de 30 min aux bords.
OK lance la chaîne.

Au démarrage, l'appli relance automatiquement la dernière chaîne regardée.

### Choix techniques de la Live TV
- **Démarrage instantané** : tout s'affiche depuis le cache Room. Le catalogue est resynchronisé
  en arrière-plan s'il a plus de 12 h, le guide s'il a plus de 6 h. Le bouton « Actualiser »
  force la synchronisation.
- **Guide XMLTV** : lu en flux, jamais chargé en entier en mémoire. Seule la fenêtre −2 h / +24 h
  des chaînes connues est conservée. Le nouveau guide est importé à côté de l'ancien, puis on
  bascule de l'un à l'autre : le guide n'est jamais vide à l'écran. Si le XMLTV ne fournit rien
  pour une chaîne, l'appli demande au portail son programme en cours (`get_short_epg`).
- **Un seul lecteur pour toute la session**, partagé entre l'aperçu et le plein écran : passer
  de l'un à l'autre ne recharge pas le flux.
- **Zapping** : affichage immédiat de la nouvelle chaîne, chargement du flux après 350 ms sans
  nouvel appui. Faire défiler 10 chaînes ne lance donc qu'un seul flux.
- **Erreurs de flux** : une relance automatique, puis un message avec « Réessayer ».
- Flux live demandés en **MPEG-TS** (`.ts`), le format qui démarre le plus vite. Il sera
  réglable à l'étape 3.

## Machines faibles (box bas de gamme, 1 Go de RAM)

L'appli est pensée pour tourner sur les appareils les plus modestes (testée sur 1 Go de RAM et
1 seul cœur) :
- **Grilles paginées** : seules les affiches proches de l'écran sont lues en base (un catalogue
  de 15 000 films ne charge jamais 15 000 lignes d'un coup).
- **Détection automatique** d'une machine faible : images en 16 bits et cache d'images réduit.
- **Tampon vidéo plafonné en octets** (24 Mo sur machine faible, 64 Mo sinon) : un flux 4K ne
  peut pas saturer la mémoire.
- En HLS, la qualité ne dépasse pas la définition réelle de l'écran.
- Listes du portail décodées au fil de l'eau, sans garder le texte JSON entier en mémoire.

## Confidentialité vis-à-vis du FAI

### Kill switch VPN (toujours actif en release)

**Sans VPN actif, MyTV ne fait aucune connexion** : ni portail, ni guide, ni vidéo, ni logos, ni
même de requête DNS. Un écran « VPN requis » bloque l'appli, et elle reprend toute seule dès que
le VPN se connecte. Le FAI ne voit donc jamais l'adresse du serveur IPTV, seulement une connexion
chiffrée vers le serveur VPN.

- Le verrou est placé en tout premier dans OkHttp, par où passe tout le trafic de l'appli (API,
  ExoPlayer, Coil, DNS chiffré). Il est vérifié avant toute résolution DNS ou ouverture de connexion.
- Il est revérifié à **chaque requête**. Si le VPN tombe en pleine lecture, le flux est coupé
  immédiatement et plus rien ne part.
- Si le VPN est configuré pour **exclure MyTV** (split tunneling), il est considéré comme absent,
  puisque le trafic de MyTV ne passerait justement pas dans le tunnel.
- Aucun bouton ne permet de contourner ce verrou en release. Pour tester sur l'émulateur, qui n'a
  pas de VPN, `mytv.debug.allowNoVpn=true` dans `local.properties` le lève **en debug
  uniquement**. Le badge affiche alors « ⚠ DEBUG : kill switch désactivé ».
- Vérifié dans l'émulateur sans VPN : la version release affiche l'écran bloquant, et le faux
  portail reçoit 0 requête.

**Choisir un VPN** : il faut une application VPN installée sur le Fire TV (de nombreux fournisseurs
proposent une appli Fire TV, dont certains avec une offre gratuite). Dans ses réglages, vérifie
que MyTV n'est pas exclue du tunnel. Activer l'option « kill switch » du VPN lui-même ajoute une
deuxième protection, au niveau de tout le Fire TV.

### Protections supplémentaires

Ce que l'appli fait en plus, automatiquement :

| Protection | Ce que ça cache au FAI |
|---|---|
| **DNS chiffré (DoH)** via Cloudflare, puis Quad9, pour tout le trafic : API, guide, vidéo et logos. Les IP des résolveurs sont figées, donc aucune requête DNS ne part en clair. | les noms des serveurs contactés, et les blocages appliqués au niveau du DNS |
| **Passage automatique en HTTPS** quand le portail annonce un port HTTPS qui fonctionne, à l'ajout du portail puis à chaque lancement | les URLs, les identifiants et le contenu des flux |
| **User-Agent neutre**, aucun nom d'appli IPTV connu | l'empreinte de l'appli |
| **Indicateur permanent** en haut de l'écran Live : 🔒 VPN actif / ⚠ Sans VPN · HTTPS / ⚠ Sans VPN · HTTP non chiffré | — |

Ces protections ne suffiraient pas seules : sans VPN, le FAI verrait l'adresse IP du serveur, et
avec un portail en HTTP, l'URL et la vidéo en clair. C'est pour cela que le kill switch ci-dessus
est obligatoire. Avec le VPN, elles ajoutent une couche : le fournisseur VPN lui-même ne voit pas
les noms de domaine (DoH) ni, en HTTPS, le contenu.

Si aucun résolveur DoH ne répond, l'appli peut utiliser le DNS du système, mais seulement quand le
VPN est actif : il passe alors lui aussi dans le tunnel. En debug, `adb logcat -s MyTV-DNS` indique
quel mode a été utilisé.

**Limite** : le FAI voit qu'un VPN est utilisé et le volume de données échangé, mais pas leur
destination ni leur contenu.

## Sécurité des identifiants

- Le mot de passe du portail est chiffré en **AES-256-GCM avec une clé de l'Android Keystore**,
  qui ne peut pas être extraite de l'appareil. Il est stocké chiffré dans Room, jamais en clair.
- En debug, les logs HTTP masquent `username` et `password` (`username=***`).
- `android:allowBackup="false"` : la base n'est pas incluse dans les sauvegardes Android.
- **Rien n'est en dur dans le code.** Les fichiers sensibles sont ignorés par git :
  `local.properties`, `keystore.properties`, `*.jks`.

> ⚠️ La plupart des portails Xtream fonctionnent en **HTTP non chiffré** : tes identifiants
> circulent donc en clair sur le réseau, quelle que soit l'appli. Le trafic HTTP est autorisé
> (`res/xml/network_security_config.xml`), sinon Android bloquerait ces portails.

## Configuration locale

```powershell
Copy-Item local.properties.example local.properties
```

Puis édite `local.properties` :

- `sdk.dir` : chemin du SDK Android (rempli automatiquement par Android Studio) ;
- facultatif, `mytv.debug.portalUrl` / `mytv.debug.username` / `mytv.debug.password` :
  ces valeurs pré-remplissent le formulaire **dans les builds debug uniquement**. Pratique pour ne
  pas tout retaper à la télécommande. Elles ne sont jamais incluses dans un APK release.

## Compiler

```powershell
.\gradlew.bat assembleDebug
```

APK produit : `app\build\outputs\apk\debug\app-debug.apk` (identifiant `dev.mytv.debug`).

Version release (minifiée par R8) :

```powershell
.\gradlew.bat assembleRelease
```

APK produit : `app\build\outputs\apk\release\app-release.apk`. Sans `keystore.properties`, il est
signé avec la clé debug, ce qui suffit pour un usage perso. Pour signer avec ta propre clé, voir
`keystore.properties.example`.

Tests unitaires (parsing Xtream, traduction des erreurs, normalisation d'URL, masquage des logs) :

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest
```

## Installer sur le Fire TV Stick par ADB (Wi-Fi)

### 1. Une seule fois, sur le Fire TV
1. **Paramètres → Mon Fire TV → À propos** : clique 7 fois sur le nom de l'appareil pour activer
   les **Options pour les développeurs**.
2. **Paramètres → Mon Fire TV → Options pour les développeurs** : active **Débogage ADB**.
3. **Paramètres → Mon Fire TV → À propos → Réseau** : note l'**adresse IP** (ex. `192.168.1.42`).

### 2. Depuis le PC (même réseau Wi-Fi)

```powershell
adb connect 192.168.1.42:5555
```

À la première connexion, le Fire TV affiche « Autoriser le débogage USB ? » : coche
**Toujours autoriser depuis cet ordinateur**, puis **OK**.

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Ou en une étape (compile puis installe sur l'appareil connecté) :

```powershell
.\gradlew.bat installDebug
```

L'appli apparaît dans **Vos applis et chaînes** du Fire TV.

Utile pour diagnostiquer :

```powershell
adb logcat -s MyTV-HTTP AndroidRuntime
```

Pour voir et piloter l'écran du Fire TV depuis le PC, utilise [scrcpy](https://github.com/Genymobile/scrcpy) :

```powershell
scrcpy -s 192.168.1.42:5555
```

## Tester sans vrai portail

Un faux portail Xtream est fourni pour le développement :

```powershell
python tools\fake_xtream.py
```

Depuis l'émulateur Android, il est joignable à l'adresse `http://10.0.2.2:8080` :

| Utilisateur / mot de passe | Comportement |
|---|---|
| `demo` / `demo` | compte actif |
| `expired` / `expired` | compte expiré |
| `slow` / `slow` | répond après 60 s (test du délai dépassé) |
| autre | identifiants refusés |

Le faux portail simule 4 catégories et 40 chaînes, un guide XMLTV et `get_short_epg`. Côté VOD,
il propose 24 films en 3 catégories et 6 séries de 1 à 3 saisons. Les affiches sont générées, et
les fichiers MP4 de 3 min sont servis avec support des requêtes partielles (Range), ce qui permet
l'avance rapide.
- Les chaînes 31 à 40 n'ont pas de guide XMLTV.
- La chaîne 13 renvoie une erreur 404, pour tester l'affichage d'erreur.
- Si **FFmpeg** est installé (`winget install Gyan.FFmpeg.Essentials`), des vidéos de test
  (mires colorées numérotées) sont générées au premier lancement dans `tools/test_streams/`,
  dossier ignoré par git. Elles sont envoyées en flux continu, comme un vrai serveur IPTV.

L'émulateur conseillé est une image **Android TV API 30, x86, 1080p, 2 Go de RAM**, la plus proche
de Fire OS 8. Attention : ses performances ne sont **pas** représentatives du Fire TV Stick. La
fluidité se valide toujours sur l'appareil réel.
