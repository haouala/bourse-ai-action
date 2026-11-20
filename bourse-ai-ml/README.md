# Bourse AI ML - batch autonome de démonstration

Module Python séparé du processus Java. Première étape : données entièrement fictives, apprentissage chronologique et modèles JSON candidats. Les modèles sont **DEMO_ONLY** et ne peuvent pas activer les poids du backend.

Python 3.11 minimum. Le générateur couvre 2003-2025, avec des scénarios fictifs portant les étiquettes 2008, Covid et 2022. Ces étiquettes servent exclusivement à l'évaluation ; elles ne sont pas fournies au modèle. Les actifs DEMO_001, etc., ne sont pas des actions réelles. Le calendrier est un calendrier de jours de semaine fictif, sans prétendre reproduire les séances historiques.

Les tests du noyau se lancent avec `python -m pytest tests/test_core.py`. La persistance utilise la même base PostgreSQL que Java, dans le schéma indépendant `bourse_ml`. Elle ne lit ni ne modifie les tables `public` du backend. Les modèles, leurs métriques et prédictions sont enregistrés atomiquement, avec une empreinte des données et un verrou de concurrence. Aucun token fournisseur ni clé IA n'est nécessaire.

Le dictionnaire des six tables est dans [docs/SCHEMA.md](docs/SCHEMA.md). Les tests PostgreSQL exigent une base jetable explicitement nommée `bourse_ml_test_...`, transmise par `BOURSE_ML_TEST_DSN`. Le lot CLI ajoutera les commandes de lancement et d'export.
