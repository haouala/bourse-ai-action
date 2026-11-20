# Bourse AI

## Batch Python de démonstration

Le module [bourse-ai-ml](bourse-ai-ml/README.md) tourne séparément de Java, sur la même base **PostgreSQL**, dans son schéma `bourse_ml`. Il génère des données fictives, apprend des coefficients avec validation chronologique, enregistre des modèles candidats et exporte ses rapports. Aucun token fournisseur ou IA n'est requis ; les poids actifs et la limite de perte ne sont pas modifiés.

Branche : `feature/ml-batch-demo`, basée sur le backend V8. La base H2 par défaut n'est pas partagée. Voir le guide pour les commandes PowerShell/Linux, les variables PG*, les six tables et les limites des données synthétiques.

Backend Java 21 / Spring Boot dans `bourse-ai-engine`.

Voir [la configuration et les quotas](bourse-ai-engine/CONFIGURATION.md) pour le lancement, les clés privées, PostgreSQL et les tests.

```bash
cd bourse-ai-engine
mvn clean verify
mvn spring-boot:run
```

Port par défaut : 8081. Sans profil `postgres`, la base H2 est volatile ; utiliser PostgreSQL pour conserver les données et les compteurs entre redémarrages.

## Modules backend disponibles en développement

- [Architecture, code et exemples d’API](bourse-ai-engine/docs/ARCHITECTURE.md)
- [Schémas et dictionnaire de la base de données](bourse-ai-engine/docs/DATABASE.md)
- [Résultats des tests et de l’essai après redémarrage](bourse-ai-engine/docs/VALIDATION.md)
- [Publications sociales, influence par sujet et scores par actif](bourse-ai-engine/docs/SOCIAL_SIGNALS.md)
- [Guide local complet sans token puis avec services facultatifs](bourse-ai-engine/docs/BACKEND_LOCAL.md)

Branche de livraison : `feature/portfolio-timeframes-alerts`, [PR 1](https://github.com/haouala/bourse-ai-action/pull/1). Les changements ne sont pas encore fusionnés dans `main`. Depuis une copie existante, récupérer cette branche avec `git fetch origin`, puis `git switch feature/portfolio-timeframes-alerts` ; conserver les éventuelles modifications locales avant de changer de branche.

| Fonction | Comportement livré |
|---|---|
| Marché et positions | Liste configurable de 100 actions et 30 cryptos ; 15m/30m/1h/1d ; achats/ventes déclarés, frais, FX et limite de perte globale |
| Calendriers | Séances régulières US 2026–2028, import d'autres calendriers, fuseaux, fériés, clôtures anticipées, états inconnus et suspensions déclarées |
| Qualité des prix | Dernière bougie attendue, marge de livraison, clôture et ouverture en estimation explicite ; horodatage FX distinct |
| Saisonnière | Statistiques **mensuelles, trimestrielles et annuelles** sur les cours quotidiens ; pas d'analyse horaire ou par jour de semaine |
| Informations | 12 flux publics RSS/Atom politiques, économiques et internationaux ; versions, dates, reprises, mentions et citations validées manuellement |
| Personnalités | Influence par sujet et date ; impact/confidence par actif ; collecte X facultative et plafonnée |
| Explication | Règles MACD/RSI, variation de marge nette et événements ; contributions, sources et snapshots pour DAY/MONTH/YEAR |
| Correction des règles | Versions immuables et comparaison des points entre deux analyses ; aucune réécriture de l'historique |

Pour un premier essai sans compte fournisseur : lancer l'application, puis `python3 scripts/local_backend_smoke.py` depuis `bourse-ai-engine`. Ce script utilise exclusivement des données fictives et ne collecte aucune source réelle.

Les ordonnanceurs sont **désactivés par défaut**. RSS, calendriers, statistiques et explications ne nécessitent aucun token. Twelve Data et X nécessitent une clé uniquement si leurs appels sont volontairement activés ; les étapes sont dans le guide local et `.env.example`.

Les scores sont expérimentaux et explicables, pas des probabilités de gain. La saisonnalité reste descriptive avec zéro point avant validation indépendante. L'interprétation des textes et la vérification de l'identité des locuteurs restent humaines ; aucun LLM n'est connecté. Les graphiques de scénarios, l'application Android, le push, les backtests financiers indépendants et la validation du fournisseur payant restent à réaliser. Les API de développement doivent rester privées jusqu'à l'ajout de l'authentification.

Le suivi des développements est actualisé dans le document d'analyse version 1.2 du 20 novembre 2026, livré séparément en Word et PDF.

