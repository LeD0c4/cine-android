# Selvania Ciné — appli Android (téléphone et télé)

Appli **Jellyfin** « façon Netflix » pour Android : une seule APK pour le téléphone, la tablette et la télé (Android TV / Google TV). Interface en français.

> **English** — A Netflix-style Jellyfin app for Android phones, tablets and TVs: bundled web UI ([cine-web](https://github.com/LeD0c4/cine-web)), native ExoPlayer playback, built-in Chromecast support, Netflix-like end-of-episode screen and optional self-updates from a GitHub releases feed.

## Fonctions

- **N'importe quel serveur Jellyfin** : au premier lancement, on saisit l'adresse du serveur (`jellyfin.exemple.fr`, `192.168.1.20:8096`…), puis son compte ou un code Quick Connect. Plusieurs profils et serveurs possibles.
- **Interface intégrée** : l'interface de [`cine-web`](https://github.com/LeD0c4/cine-web) (version « appli ») est embarquée dans l'APK et servie localement par `WebViewAssetLoader` ; rien à héberger.
- **Lecteur natif** (Media3 / ExoPlayer) : lit le fichier d'origine (MKV, HEVC, Dolby…) sans conversion quand l'appareil le décode, sinon demande une conversion au serveur ; progression enregistrée dans Jellyfin ; « Passer l'intro » ; **écran de fin façon Netflix** (vidéo réduite à gauche, épisode suivant à droite, compte à rebours réglable dans Réglages).
- **Chromecast** (kit Google Cast, récepteur multimédia par défaut) : choix de l'appareil, épisodes suivants envoyés en file d'attente, télécommande plein écran, notification et mini-télécommande, progression enregistrée, épisode suivant au générique. Désactivé quand l'appli tourne sur une télé.
- **Télé** : détectée automatiquement (lanceur Android TV, bannière, navigation à la télécommande, bouton Retour).
- **Mises à jour automatiques** (facultatives) : voir plus bas.

## Installer

Téléchargez l'APK depuis la page **Releases** du dépôt, puis ouvrez-la sur l'appareil (Android demande d'autoriser l'installation depuis cette source). Sur une télé, le plus simple est d'utiliser une appli de téléchargement (« Downloader ») ou un gestionnaire de fichiers avec une clé USB.

Le serveur doit accepter les requêtes de l'appli : si l'appli trouve le serveur mais que la connexion échoue, votre reverse proxy bloque probablement les pré-requêtes CORS (voir *Autoriser les applis* dans le README de `cine-web`).

## Compiler

Prérequis : JDK 17 (celui d'Android Studio convient), SDK Android 36, Node.js 20.19+ pour l'interface. Clonez `cine-web` **à côté** de ce dépôt et construisez sa version « appli » :

```bash
git clone https://github.com/LeD0c4/cine-web ../cine-web
(cd ../cine-web && npm install && npm run build:app)
./gradlew :cine:assembleDebug
```

L'APK est dans `cine/build/outputs/apk/`. L'interface est prise dans `../cine-web/dist-app`, ou ailleurs avec `-PcineUi=/chemin/vers/dist-app`.

### Signature

Pour une version « release » (et pour que les mises à jour s'installent par-dessus l'appli existante), créez une clé **une seule fois** et gardez-la précieusement, hors de git :

```bash
keytool -genkeypair -v -keystore selvania.keystore -alias cine -keyalg RSA -keysize 2048 -validity 10000
```

À la racine du projet, `keystore.properties` (ignoré par git) :

```properties
storePassword=…
keyAlias=cine
keyPassword=…
```

Puis `./gradlew :cine:assembleRelease`. Sans ces deux fichiers, la version « release » n'est pas signée.

## Mises à jour automatiques

Facultatives, désactivées par défaut. Dans `gradle.properties` :

```properties
updateFeed=votre-compte/votre-depot-de-mises-a-jour
```

L'appli lit alors la dernière *release* de ce dépôt GitHub public (au démarrage, au plus toutes les 6 h, et depuis Réglages › Rechercher une mise à jour). Si son numéro (`v2.0.1`) est plus récent que celui de l'appli, elle propose de télécharger l'APK joint et le confie à l'installeur d'Android.

Pour publier une version : augmentez `versionCode` et `versionName` dans `cine/build.gradle.kts`, puis

```bash
node publish.mjs "Ce qui change dans cette version"
```

`publish.mjs` reconstruit l'interface, l'APK signé (et le paquet LG si [`cine-webos`](https://github.com/LeD0c4/cine-webos) est cloné à côté), puis crée la release dans le dépôt `updateFeed` avec la [CLI GitHub](https://cli.github.com/) (`gh auth login` au préalable). Il se lance sur la machine qui a la clé de signature : une mise à jour doit être signée avec la même clé que l'appli installée.

## Personnaliser

- **Identifiant de l'appli** : `applicationId` dans `cine/build.gradle.kts` (à changer si vous publiez votre propre variante, pour qu'elle ne remplace pas celle-ci).
- **Nom et textes** : `cine/src/main/res/values/strings.xml`.
- **Couleurs** : `cine/src/main/res/values/colors.xml` (et `src/app.css` dans `cine-web` pour l'interface).
- **Icônes, bannière télé** : générées depuis le logo par `brand/build-icons.py` dans `cine-web`.

## Organisation du code

| Fichier | Rôle |
|---|---|
| `MainActivity.kt` | vue web de l'interface, pont JavaScript `SelvaniaTV` (`play`, `cast`, `canCast`, `checkUpdate`, `version`), mini-télécommande Chromecast |
| `PlayerActivity.kt` | lecteur natif, intro, écran de fin |
| `Caster.kt`, `CastOptionsProvider.kt`, `CastControllerActivity.kt` | diffusion Chromecast et suivi de la lecture |
| `JellyfinApi.kt`, `DeviceProfile.kt` | appels à Jellyfin ; ce que l'appareil et le Chromecast savent lire |
| `Updater.kt` | mises à jour automatiques |

## Intégration continue

`.github/workflows/android.yml` compile l'APK à chaque envoi et *pull request* (en récupérant `cine-web`), et la joint à une release pour une étiquette `v*`. Variable de dépôt facultative `CINE_WEB_REPO` (votre fork de `cine-web`) ; secrets facultatifs `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` pour un APK signé.

## Contribuer

Voir [`CONTRIBUTING.md`](CONTRIBUTING.md). Ce projet est développé en **vibe coding** : le code est écrit en grande partie par une IA (Claude, d'Anthropic) à partir des demandes et des essais de l'auteur — relisez et testez, et signalez ce qui ne va pas.

## Licence

[GNU GPL v3](LICENSE). Jellyfin est une marque de son projet ; Selvania Ciné n'est pas affilié à Jellyfin. Google Cast et Chromecast sont des marques de Google.
