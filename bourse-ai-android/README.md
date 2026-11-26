# Bourse AI Android

Application native Kotlin / Jetpack Compose, Android 8.0 minimum (API 26), cible Android 15 (API 35). Le code Android est autonome dans ce dossier. Le serveur conserve les données, les calculs, les collectes et les tokens.

## Fonctions disponibles

| Module | Fonction |
|---|---|
| Marché | Actions et cryptos, recherche, filtres, ajout, activation/pause ; plafonds de 100 actions et 30 cryptos actives |
| Graphiques | Historique 15 min, 30 min, 1 h et 1 jour, petites valeurs crypto, OHLC et indicateurs calculés par le serveur |
| Figures de bougies | Marteau, doji, engloutissements haussier/baissier fournis par le backend ; date, confirmation de prix et volume séparés |
| Aide financière | Icônes d’information contextuelles : définition, exemple et limites ; lexique recherchable dans Accueil et Profil |
| Calendrier | Séance, dernière clôture attendue, prochaine ouverture et motif renvoyés par le backend |
| Analyse | Horizons journée/mois/année, composantes techniques/financières/informationnelles, preuves, snapshots et explication des variations |
| Règles et saisonnalité | Créer une version immuable de règles ; statistiques mensuelles, trimestrielles et annuelles descriptives, sans points dans le score |
| Informations | Publications, liens originaux, dates de publication et collecte, attribution humaine d'une citation exacte |
| Sources et personnes | Activation et collecte manuelle de flux, journal des imports, identités documentées, publications et impacts par entreprise |
| Portefeuille | Création/sélection par ID, achats déjà effectués, ventes partielles/totales, frais, FX et dates séparées, perte maximale globale |
| Plans | Protection/objectifs illustratifs fournis par le serveur et hypothèse personnelle de prix avec graphique |
| Alertes | Boîte de réception, filtre nouvelles/toutes, acquittement conservant l'historique, réévaluation manuelle et notifications Android facultatives |
| Réglages | Démonstration isolée, adresse du serveur, test de connexion et portefeuille |

Aucun ordre de bourse n'est transmis. Les courbes de prix montrent le passé ; les scénarios n'inventent pas une prévision. Le score et la couverture des données ne sont pas des probabilités de gain.

Les figures nécessitent le champ optionnel `patternAnalysis` du backend. Un serveur
plus ancien conserve l’historique et affiche la détection indisponible. Les figures
ne changent pas le score ni le ML. Voir [les règles et limites](../bourse-ai-engine/CANDLE_PATTERNS.md).

## Installer l'APK

Ouvrir [la compilation Android](https://github.com/haouala/bourse-ai-action/actions/workflows/android.yml), sélectionner une exécution verte correspondant au dernier commit, puis télécharger l'artefact **Bourse-AI-Android-debug**. Extraire le ZIP et installer son fichier app-debug.apk sur Android 8 ou supérieur. Android peut demander d'autoriser cette installation depuis le navigateur ou le gestionnaire de fichiers.

C'est un APK de développement signé avec une clé de debug, pas une version Play Store. Les clés de signature de debug peuvent changer entre machines de compilation : Android refusera une mise à jour si la signature diffère. Dans ce cas, désinstaller l'ancienne version de test avant de réinstaller ; les réglages locaux seront supprimés. Les données enregistrées sur le serveur restent sur le serveur.

L'application démarre en **DÉMONSTRATION · données fictives locales**. Aucun token ni serveur n'est nécessaire pour explorer ce mode. Ses écritures sont temporaires, en mémoire ; il redémarre sur son portefeuille fictif numéro 1 après relance du processus. Les noms, prix, déclarations et scores de démonstration ne constituent pas des informations actuelles.

## Connexion au backend local

Le contrat mobile cible le backend V8 de la branche **feature/portfolio-timeframes-alerts**, validé au commit **856c5e538968515626fe370c1c1179b0ccba8875**. Le backend plus ancien présent sur main ou sur cette branche Android ne possède pas nécessairement ces routes. Utiliser deux dossiers de travail :

~~~bash
git clone --branch feature/android-scaffold https://github.com/haouala/bourse-ai-action.git bourse-android
git clone --branch feature/portfolio-timeframes-alerts https://github.com/haouala/bourse-ai-action.git bourse-backend
~~~

1. Démarrer PostgreSQL et le backend dans le second dossier selon son README. Vérifier http://localhost:8081/actuator/health : le statut doit être UP.
2. Sur émulateur, ouvrir Réglages et saisir **http://10.0.2.2:8081**. Sur téléphone, utiliser l'adresse IP locale du PC, par exemple **http://192.168.1.20:8081**, sur le même réseau Wi-Fi. localhost sur le téléphone désigne le téléphone.
3. Décocher **Mode démonstration**, enregistrer la connexion puis la tester.
4. Dans Positions, créer un portefeuille avec son capital et sa perte maximale globale. Conserver son identifiant pour le sélectionner ensuite. Le backend ne fournit pas de route listant tous les portefeuilles.
5. Ajouter les actifs à Marché. Les cours apparaîtront après import par le backend et configuration des fournisseurs ; ajouter un symbole ne télécharge pas automatiquement son historique.
6. Déclarer l'heure, la date, la quantité et le prix des achats déjà réalisés, les frais et le FX vers la devise du portefeuille. Le FX vaut 1 uniquement lorsque les devises sont identiques.
7. Les marques de valorisation nécessitent un prix, un FX explicite et deux dates indépendantes. Si les données manquent, perte et budget restant restent indisponibles.

Les clés Twelve Data, des autres fournisseurs et de l'IA se configurent **uniquement sur le backend**, selon son README et son fichier d'exemple de configuration. Aucun token fournisseur n'est intégré à l'APK et le mobile ne contourne pas les quotas.

HTTP n'est autorisé que dans le build de développement pour les essais locaux. Le build release exige HTTPS. Le backend actuel n'offre pas encore une authentification utilisateur complète : utiliser un réseau privé de développement ; l'exposition publique exige une authentification/autorisation côté serveur. Un certificat HTTPS doit être reconnu par Android.

## Sources, déclarations et alertes

Dans Infos > Sources, activer les flux souhaités et lancer une collecte. La planification des collectes, leurs limites et leurs éventuels tokens restent côté serveur. Le mobile n'aspire pas tous les réseaux sociaux mondiaux. Une mention de nom ne démontre ni l'identité ni l'attribution d'une phrase. L'attribution humaine exige un acteur enregistré, un passage exact, une date de publication et un lien de preuve HTTPS. La qualification financière de l'impact reste distincte.

Dans Alertes, activer la surveillance Android et accepter la permission de notification sur Android 13+. WorkManager demande une consultation **toutes les 15 minutes au plus tôt**. Android, l'économie de batterie et les restrictions du constructeur peuvent la retarder. Ce mécanisme ne garantit pas une alerte instantanée et n'utilise pas FCM. Les notifications sont privées sur l'écran verrouillé, dédupliquées par serveur/portefeuille/génération et ouvrent l'écran Alertes.

Le backend doit rester démarré, collecter les données et produire les alertes. Le téléphone ne réévalue pas les positions en arrière-plan : il lit les alertes existantes. Les notifications sont désactivées en démonstration et restent facultatives. Pour arrêter la surveillance, décocher son activation dans Alertes.

Les podcasts transcrits automatiquement, la classification sémantique automatique de chaque déclaration et les prédictions de trajectoire ne sont pas des fonctions ajoutées par ce module Android.

## Ouvrir et construire

Dans Android Studio, ouvrir **bourse-ai-android**, le dossier contenant settings.gradle.kts. Installer Platform 35 et Build-Tools 35.0.0 dans SDK Manager ; choisir JDK 17 ou 21 pour Gradle. Le Wrapper officiel utilise Gradle 8.11.1 avec checksum vérifié. Le SDK doit être configuré par ANDROID_HOME ou sdk.dir dans local.properties, ignoré par Git.

Depuis ce dossier :

~~~bash
bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
~~~

Sous PowerShell :

~~~powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
~~~

APK : app/build/outputs/apk/debug/app-debug.apk. Le build assembleRelease vérifie aussi la compilation de production mais génère un APK non signé ; sa publication nécessite votre propre clé de signature.

Les tests d'interface rendent les vrais composants Compose avec Robolectric API 35 et le moteur graphique natif. Ils enregistrent leurs captures sous app/build/outputs/screenshots. Ces captures ne proviennent pas d'un appareil physique. Les résultats et les limites sont décrits dans [docs/VALIDATION.md](docs/VALIDATION.md).

## Architecture

MainActivity héberge BourseApp et la navigation. AppStore gère l'état, l'annulation et les opérations en cours. data/ contient les contrats et validations, HttpTransport et les jeux de démonstration séparés. ui/ contient les écrans ; ui/theme/ contient les couleurs clair/sombre. AlertNotifications utilise WorkManager et les notifications Android. Les montants financiers sont traités en BigDecimal, les identifiants d'opération sont stables lors d'une nouvelle tentative, et aucune écriture n'est rejouée automatiquement après une erreur réseau.

## Validation contre le vrai backend V8

La CI récupère séparément le backend au commit indiqué plus haut, construit son JAR, puis démarre une base H2 jetable pour tester les contrats mobiles réels. Elle n'accède ni à votre PostgreSQL local ni à un fournisseur de cours.

Pour reproduire cet essai, construire le JAR dans le dossier backend avec Maven et JDK 21, puis exécuter depuis le dossier Android :

~~~bash
python3 scripts/run_with_backend.py --jar /chemin/absolu/vers/bourse-ai-engine-0.1.0-SNAPSHOT.jar
python3 scripts/record_validation.py
~~~

La suite Android seule comporte 55 tests ; l'essai HTTP réel en ajoute un. Les rapports restent sous app/build et comprennent backend-contract.json, validation.json, les rapports JUnit/Lint et les captures natives. Cette validation H2 ne remplace pas les tests backend sur PostgreSQL ni un essai sur votre téléphone.

## APK de démonstration et APK connecté

`./gradlew :app:assembleDebug :app:assembleBackend` produit deux APK signés pour le développement.
L’APK `app-debug.apk` ouvre par défaut le mode démonstration. L’APK `app-backend.apk` ouvre par défaut le mode serveur et s’installe à côté du premier. Dans **Profil**, enregistrez l’adresse du backend, puis testez la connexion. Sur téléphone, saisissez l’adresse réseau de votre serveur. Sur émulateur, l’adresse initiale est `http://10.0.2.2:8081`. La version connectée attend un backend configuré et affiche les erreurs et données absentes dans l’interface.
