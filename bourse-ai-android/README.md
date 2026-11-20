# Bourse AI Android — socle initial

Projet Android autonome à ouvrir dans Android Studio, à côté du backend Spring Boot du même dépôt. Création du 20 novembre 2026, branche `feature/android-scaffold`.

Ce premier commit contient le module `app`, une activité Kotlin, un écran d'accueil Jetpack Compose, les thèmes clair/sombre, les textes français et le Gradle Wrapper officiel. L'écran annonce les prochaines fonctions ; il ne présente aucun cours, score ou résultat fictif comme réel. Il fonctionne sans connexion au backend et ne fait aucun appel réseau.

## Ouvrir le projet

1. Cloner le dépôt et sélectionner la branche :

   ```bash
   git clone --branch feature/android-scaffold https://github.com/haouala/bourse-ai-action.git
   ```

   Si le dépôt est déjà cloné, utiliser `git fetch origin` puis sélectionner `feature/android-scaffold` dans le sélecteur de branches de votre IDE, en conservant vos modifications locales.

2. Dans Android Studio, choisir **Open** puis le dossier **`bourse-ai-android`**, celui qui contient `settings.gradle.kts`.
3. Dans **SDK Manager**, installer **Android SDK Platform 35** et **Build-Tools 35.0.0**.
4. Dans **Settings > Build, Execution, Deployment > Build Tools > Gradle**, sélectionner un **JDK 17 ou 21** pour Gradle. Utiliser la distribution du Wrapper.
5. Lancer **Sync Project with Gradle Files**, choisir le module `app`, puis **Run** sur un appareil ou émulateur Android 8.0 (API 26) ou supérieur.

Android Studio crée `local.properties` avec le chemin du SDK. Ce fichier reste propre au poste et est ignoré par Git. Le premier téléchargement des dépendances nécessite Internet.

## Construire depuis un terminal

Depuis `bourse-ai-android`, avec le SDK installé et `ANDROID_HOME` défini ou `sdk.dir` dans `local.properties` :

```bash
./gradlew --version
./gradlew :app:assembleDebug :app:lintDebug
```

Sous Windows PowerShell :

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

L'APK de développement, après une compilation réussie, se trouve dans `app/build/outputs/apk/debug/app-debug.apk`.

## Structure

```text
app/src/main/
  AndroidManifest.xml
  java/lu/bourse/mobile/
    MainActivity.kt
    ui/BourseHome.kt
    ui/theme/BourseTheme.kt
  res/
    drawable/ic_launcher.xml
    values/strings.xml
    values/themes.xml
    values-night/themes.xml
```

Les écrans et les accès aux données seront séparés lorsque les API seront raccordées. Aucun dossier vide ni client API inactif n'est nécessaire pour ce socle.

## État des vérifications

- XML du manifeste, des ressources et du lanceur vérifiés par analyse syntaxique ; références locales aux chaînes et au thème contrôlées.
- JAR du Wrapper officiel 8.11.1 vérifié par SHA-256 ; SHA-256 de la distribution fixé dans `gradle-wrapper.properties` ; script Unix exécutable.
- La commande `./gradlew --version` a été tentée dans l'environnement de création : téléchargement bloqué par `Network is unreachable`. Aucun SDK Android ni émulateur n'y est installé. **Compilation APK, Android Lint et démarrage sur appareil non exécutés avec succès à ce stade.** Les commandes ci-dessus permettent de réaliser ces vérifications dans Android Studio.
- Aucun test métier n'est ajouté à ce projet initial, qui ne contient pas encore de logique financière. Les 103 tests du backend appartiennent à une autre branche et ne constituent pas une validation Android.

## Étapes suivantes

- Raccordement authentifié aux API du backend, états de chargement/erreur et configuration de l'adresse du serveur.
- Liste des actifs, détail et graphiques par horizon.
- Positions déclarées, risque et alertes.
- Sources des informations et décomposition des changements de score.
- Notifications push et tests mobiles.

Aucun token fournisseur, clé IA, mot de passe PostgreSQL ou certificat de signature n'est intégré dans l'application. Les clés des fournisseurs resteront côté backend. Ce socle ne demande aucune configuration de token et n'inclut pas de permission réseau.

## Versions fixées

AGP 8.9.2, Gradle 8.11.1, Kotlin et compilateur Compose 2.1.20, Compose BOM 2025.04.01, Activity Compose 1.10.1. SDK de compilation/cible 35, minimum 26. Ces versions sont fixées pour la reproductibilité ; elles ne sont pas présentées comme les plus récentes ni comme une validation de publication Play Store.

Références officielles : [compatibilité AGP 8.9](https://developer.android.com/build/releases/agp-8-9-0-release-notes), [configuration Compose](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler), [Gradle Wrapper](https://docs.gradle.org/8.11.1/userguide/gradle_wrapper.html), [sommes de contrôle Gradle](https://gradle.org/release-checksums/).
