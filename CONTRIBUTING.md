# Contribuer à Selvania Ciné

Merci de vouloir améliorer le projet ! Selvania Ciné est réparti en trois dépôts, à cloner **côte à côte** dans un même dossier (les scripts de construction se trouvent les uns les autres par ce chemin relatif) :

```
mon-dossier/
├── cine-web/       interface (site + interface intégrée aux applis)
├── cine-android/   appli Android téléphone + télé
└── cine-webos/     appli LG webOS
```

```bash
git clone https://github.com/LeD0c4/cine-web
git clone https://github.com/LeD0c4/cine-android
git clone https://github.com/LeD0c4/cine-webos
```

## Projet développé en « vibe coding »

Le code a été écrit en grande partie par une IA (Claude, d'Anthropic) à partir des demandes, des retours et des essais de l'auteur. Il fonctionne au quotidien, mais certaines parties n'ont été testées que sur une installation : relisez ce que vous modifiez, testez, et n'hésitez pas à signaler ce qui vous paraît bancal. Les contributions humaines (ou assistées, du moment qu'elles sont relues) sont les bienvenues.

## Signaler un problème

Ouvrez une *issue* en précisant :

- l'appareil (navigateur, téléphone Android, Android TV, télé LG…) et la version de l'appli (Réglages › À propos) ;
- la version de Jellyfin du serveur ;
- ce que vous avez fait, ce que vous attendiez, ce qui s'est passé (capture d'écran bienvenue).

**Ne publiez jamais** l'adresse de votre serveur, un jeton, un mot de passe ou une clé de signature dans une issue ou une *pull request*.

## Proposer une modification

1. *Forkez* le dépôt concerné, créez une branche (`git checkout -b correction-sous-titres`).
2. Faites des changements ciblés (une correction ou une fonction par *pull request*).
3. Vérifiez avant d'envoyer :
   - **cine-web** : `npm run check` (TypeScript / Svelte, 0 erreur), puis `npm run build` et `npm run build:app` ;
   - **cine-android** : `./gradlew :cine:assembleDebug` ;
   - **cine-webos** : `node build.mjs`.
4. Essayez sur un vrai serveur. Sans serveur à vous, le serveur de démonstration de Jellyfin convient : `https://demo.jellyfin.org/stable`, compte `demo` sans mot de passe. Attention : c'est un serveur public partagé ; dans « Lire sur… » apparaissent les appareils d'autres visiteurs, n'envoyez rien dessus.
5. Ouvrez la *pull request* en décrivant le changement et la façon de le tester.

## Conventions

- **Langue** : interface, commentaires et messages de commit en français ; textes de l'interface au vouvoiement, courts et concrets.
- **cine-web** : Svelte 5 (runes `$state`, `$derived`, `$effect`), TypeScript, pas de bibliothèque d'interface. Couleurs et tailles via les variables CSS de `src/app.css`. Tout doit rester utilisable à la télécommande (focus visible, navigation aux flèches : voir `src/lib/tv.ts`) et sur les vieux moteurs web des télés (la version « appli » est compilée pour Chrome 53).
- **cine-android** : Kotlin, vues Android classiques (pas de Compose), Media3 pour la lecture, kit Google Cast pour la diffusion.
- Pas d'adresse de serveur, de nom de domaine ni de compte personnel dans le code : tout ce qui dépend d'une installation passe par un réglage, une variable d'environnement ou `gradle.properties`.

## Licence

En contribuant, vous acceptez que votre code soit publié sous la licence du projet, la **GNU GPL v3** (voir `LICENSE`).
