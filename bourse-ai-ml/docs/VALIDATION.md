# Validation du batch ML 0.1.0

Validation locale du 20 novembre 2026 : **42 tests réussis**, zéro échec, erreur ou test ignoré, sur Python 3.12 et PostgreSQL 17.7. Les versions Python sont figées dans requirements.lock.

| Lot | Vérifications | Résultat |
|---|---|---|
| 1 - Générateur et apprentissage | Reproductibilité, extension du futur sans réécriture du passé, trois horizons, coûts, disponibilité des informations, crises exclues des entrées, normalisation sur entraînement, purge des résultats et poids indépendants des résultats de test | 23 tests au commit du noyau |
| 2 - Persistance PostgreSQL | Migration et empreinte, données immuables, injection et transactions, absence de doublons, verrou entre connexions, annulation atomique des sorties, récupération après erreur/interruption et compte sans droit sur les tables Java | 34 tests cumulés |
| 3 - CLI et livraison | Découpage annuel complet, configuration et secrets, commandes, réexport sans entraînement, export impossible après succès DB, installation du package et ressources SQL | 42 tests cumulés ; vérification du package et essai Java/Python séparés |

Le test de l'horizon annuel complet a imposé deux années de validation sur le découpage géopolitique : après purge des résultats à 252 séances fictives, une validation d'un an ne fournissait pas assez d'observations. Aucun seuil de purge n'a été supprimé pour faire passer ce test.

## Essai réel de coexistence Java/Python

Un PostgreSQL jetable a accueilli le vrai JAR Spring Boot V8, ses migrations et ses données de référence. Pendant que Java restait disponible, Python a créé son seul schéma bourse_ml et exécuté le batch MONTH sur huit actifs fictifs.

- 47 672 observations synthétiques enregistrées ;
- quatre découpages et quatre modèles candidats ;
- 16 040 prédictions hors entraînement ;
- deuxième lancement réutilisé sans doublon ;
- 43 tables publiques et 1 126 lignes publiques strictement identiques avant et après (dont la table d'historique Flyway) ;
- Java disponible après le batch, avec les trois versions de règles initiales ;
- aucun appel fournisseur ou courtier, aucune activation de modèle.

La preuve et un extrait du rapport sont dans [DEMO_RESULT.json](DEMO_RESULT.json). Les exports complets sont créés localement sous output/<run_id>/ et sont aussi conservés par le workflow GitHub Actions lorsqu'il réussit. Les résultats de la CI sont à consulter sur l'exécution associée au commit ; ce document n'affirme pas qu'une exécution future a réussi.

Les métriques financières portent exclusivement sur une simulation synthétique. Elles vérifient le fonctionnement logiciel, pas un rendement réel, une probabilité fiable de gain ou un modèle prêt à investir. Les données réelles, les calendriers historiques, la validation financière indépendante et l'intégration des poids dans Java restent hors de ce lot.
