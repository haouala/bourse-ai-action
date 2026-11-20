# Validation Android par module

Les validations ci-dessous concernent Android, indépendamment des tests backend. Chaque module est compilé et testé avant son commit. Les tests utilisent des fixtures explicites et des serveurs HTTP locaux, jamais des cours de bourse réels.

| Module | Vérification | Résultat |
|---|---|---|
| 1. Socle, navigation, réglages et HTTP | assembleDebug + testDebugUnitTest + lintDebug | Réussis ; 7 tests, 0 échec, 0 erreur, 0 ignoré |

Le socle couvre la validation des URL et HTTPS de production, les montants exacts sans zéro inventé, l’encodage des chemins HTTP, les erreurs sans divulgation du corps serveur, l’absence de retry implicite, l’annulation lors du changement de connexion et le refus des doubles soumissions concurrentes.

L’APK du socle est généré. La validation visuelle et les parcours intégrés seront exécutés après les modules métier. Les captures et tests ne démontrent aucune performance financière.
