# Validation Android par module

Les validations ci-dessous concernent Android, indépendamment des tests backend. Chaque module est compilé et testé avant son commit. Les tests utilisent des fixtures explicites et des serveurs HTTP locaux, jamais des cours de bourse réels.

| Module | Vérification | Résultat |
|---|---|---|
| 1. Socle, navigation, réglages et HTTP | assembleDebug + testDebugUnitTest + lintDebug | Réussis ; 7 tests, 0 échec, 0 erreur, 0 ignoré |
| 2. Liste de suivi actions et cryptos | Même chaîne de vérification | Réussie ; 12 tests cumulés, 0 échec, 0 erreur, 0 ignoré |
| 3. Graphiques, indicateurs et calendrier | Même chaîne de vérification | Réussie ; 18 tests cumulés, 0 échec, 0 erreur, 0 ignoré |

Le socle couvre la validation des URL et HTTPS de production, les montants exacts sans zéro inventé, l’encodage des chemins HTTP, les erreurs sans divulgation du corps serveur, l’absence de retry implicite, l’annulation lors du changement de connexion et le refus des doubles soumissions concurrentes.

L’APK du socle est généré. La validation visuelle et les parcours intégrés seront exécutés après les modules métier. Les captures et tests ne démontrent aucune performance financière.

Le module 2 vérifie les recherches et filtres, les données mal formées, les horaires continus crypto, les ajouts et pauses idempotents ainsi que le plafond de 100 actions dans le mode démonstration. Les requêtes serveur utilisent PUT /api/v1/watchlist avec le contrat du backend.

Le module 3 vérifie les quatre intervalles, les bougies ordonnées et cohérentes, les indicateurs absents, les prix extrêmes/identiques, l’affichage exact des petits prix crypto et le calendrier explicitement fictif en démo. Les courbes montrent uniquement l’historique.

## Module 4 — analyses, versions et saisonnalité

APK, Android Lint et les 24 tests cumulés réussissent, sans échec, erreur ou test ignoré. Les tests vérifient les trois horizons, les variantes immuables, la décomposition exacte des variations, les amplitudes et seuils bornés, les scores absents, les liens HTTPS distincts et les statistiques exclusivement mensuelles/trimestrielles/annuelles sans poids dans le score. Les snapshots précédents restent consultables.

## Module 5 — informations, sources et personnes

APK, Lint et les 30 tests cumulés réussissent, sans échec ni test ignoré. Les six nouveaux tests contrôlent les dates distinctes, les dates absentes, le filtre de source, les citations exactes et leur idempotence, la conservation du lien original, l’activation explicite des flux et le journal d’import, sans confondre mention, identité et confirmation. Le mobile consulte aussi les impacts par entreprise et leurs preuves.

## Module 6 — portefeuille, opérations et risque

APK, Lint et les 36 tests cumulés réussissent sans échec ni test ignoré. Les six tests métier ajoutés vérifient les fuseaux et microsecondes exigées par le backend, les limites de risque et montants, les achats idempotents, le cash avec FX appliqué une seule fois, la perte indisponible sans marque, les dates prix/FX indépendantes, les ventes partielles/idempotentes, les clôtures et les plans sans ordre exécutable. Le formulaire conserve ses champs lors de la rotation ; la vérification UI intégrée est prévue dans le lot final.

## Module 7 — alertes et notifications Android

APK, Lint et les 45 tests cumulés réussissent sans échec ni test ignoré. Six tests couvrent les filtres, l’acquittement sans effacer l’historique, la déduplication par serveur/portefeuille/génération et la limite de perte verrouillée. Trois tests supplémentaires sous Robolectric Android 15 (API 35) vérifient le refus en mode démo ou sans portefeuille, le blocage global malgré une permission accordée, et la publication effective d’une notification Android privée avec son intent et sa mémoire de déduplication.

La consultation en arrière-plan utilise WorkManager à intervalle demandé de 15 minutes, après activation et permission utilisateur. Elle lit uniquement les alertes produites par le backend. Les restrictions Android peuvent retarder l’exécution ; ce n’est pas du push instantané FCM. La livraison sur appareil physique et les restrictions spécifiques des constructeurs restent à vérifier sur le téléphone utilisateur.

## Module 8 — parcours et livraison reproductible

La suite ajoute dix parcours natifs Robolectric API 35 aux 45 tests des modules précédents. La chaîne GitHub Actions compile debug et release, exécute toute la suite et Android Lint, exige au moins 55 tests sans échec/erreur/ignoré et au moins 28 captures PNG non vides, puis vérifie la signature du debug APK. Son fichier validation.json indique le commit réellement testé, les totaux par classe, les captures et le SHA-256 de l'APK. Une exécution rouge ne valide pas la livraison.

Les parcours couvrent recherche/filtres, conservation d'un formulaire recréé, quatre intervalles, calendrier, premier score et changements, nouvelle version de règles avec validation, trois échelles saisonnières, citation humaine, flux et journal, personnes et impacts, déclaration d'achat/vente partielle/prix-FX, risque incomplet, scénario personnel, acquittement et historique, URL/connexion, thème sombre et texte agrandi en erreur réseau.

Les captures utilisent les vrais composants Compose avec des données explicitement fictives. Elles ne remplacent pas un test physique de la livraison des notifications, des restrictions batterie et de la connexion au PC de l'utilisateur. Le contrat HTTP est couvert par MockWebServer et par un test optionnel contre le vrai backend V8, démarré sur une base H2 jetable. La chaîne CI active ce dernier test : le total attendu devient 56. Il contrôle la sérialisation mobile réelle, les graphiques, calendrier, analyses/snapshots, sources, positions/FX/perte globale, achats et ventes idempotents, alerte de limite, acquittement, plan et publications/impacts. Aucun fournisseur externe ni courtier n'est appelé. validation.json indique explicitement si ce test a été exécuté ; une exécution Android seule ne valide pas ce contrat réel.

### Commits métier et validations locales

| Module | Commit | Tests cumulés réussis |
|---|---|---:|
| Socle, navigation et HTTP | ef6bdb1244339c8bb3c690abb291b5ef785a2453 | 7 |
| Suivi actions et cryptos | 90bb80102edfe0f5e331c867bdf896ecca2a65ba | 12 |
| Graphiques et calendrier | 2a0bbbbc8a592a08d4021ecb5ac70e5dcaac54c7 | 18 |
| Analyses, règles et saisonnalité | 4a3e17baed31c50b6d2861aa47510a2cd556bebd | 24 |
| Informations et personnes | 102801ce54f56dc6308638bfab527172a8b67b5b | 30 |
| Portefeuille et risque | 7c11467669f05f0819c5ca4a7017b465134ce24f | 36 |
| Alertes et notifications | 6709bd8b6a33b92781a82d4bad42873715925d1b | 45 |

Ces nombres sont cumulatifs. Chaque validation métier comprend aussi assembleDebug et lintDebug. Les commits de CI suivants installent explicitement le SDK sur une machine neuve et publient les artefacts après réussite des contrôles.
