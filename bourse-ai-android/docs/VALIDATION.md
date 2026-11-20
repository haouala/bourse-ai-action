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
