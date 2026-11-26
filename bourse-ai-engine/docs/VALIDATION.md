# Validation des modules backend du 20 novembre 2026

Livraison de développement sur `feature/portfolio-timeframes-alerts`, PR 1. Référence machine : [verification-summary.json](verification-summary.json). Aucun test n'a modifié la base du PC de l'utilisateur. Le document d'analyse est mis à jour en version 1.2.

## Construction et non régression

Java 21.0.13.1, Maven 3.9.9, Spring Boot 3.5.7. `mvn clean verify` réussit sur H2. Les mêmes **103 tests** réussissent sur PostgreSQL 17.7 isolé avec `mvn test`. Aucun échec, aucune erreur, aucun test ignoré. Les 53 tests précédents sont conservés ; la suite contient 50 tests supplémentaires, dont une extension de la suite de suivi des positions.

| Suite | Tests | H2 | PostgreSQL 17.7 |
|---|---:|---|---|
| BackendModulesApiTest | 4 | Réussi | Réussi |
| CalendarIntegrationTest | 11 | Réussi | Réussi |
| ExplanationIntegrationTest | 10 | Réussi | Réussi |
| FeedParserTest | 6 | Réussi | Réussi |
| InformationIntegrationTest | 9 | Réussi | Réussi |
| MigrationUpgradeTest | 1 | Réussi | Réussi |
| MonitoringApiIntegrationTest | 5 | Réussi | Réussi |
| PortfolioIntegrationTest | 12 | Réussi | Réussi |
| IntradayFlowIntegrationTest | 4 | Réussi | Réussi |
| ProviderFlowIntegrationTest | 8 | Réussi | Réussi |
| QuotaIntegrationTest | 6 | Réussi | Réussi |
| SeasonalityIntegrationTest | 3 | Réussi | Réussi |
| SeasonalityStatisticsTest | 5 | Réussi | Réussi |
| FinancialIndicatorsTest | 3 | Réussi | Réussi |
| SocialScoringTest | 3 | Réussi | Réussi |
| SocialSignalIntegrationTest | 8 | Réussi | Réussi |
| XCollectorIntegrationTest | 5 | Réussi | Réussi |
| **Total** | **103** | **103 réussis** | **103 réussis** |

Les suites vérifient les formules, les routes, les erreurs, les transactions et les migrations. Les tests de fournisseurs utilisent des serveurs HTTP locaux et des identifiants fictifs ; aucun token réel n'est nécessaire pour les exécuter.

## Comportements vérifiés

- Les imports, devises, quotas, comptabilité, frais, ventes partielles, clés d'idempotence et achats concurrents restent couverts.
- Calendriers : jour férié, clôture anticipée, week-end, changement d'heure, marge de livraison, couverture inconnue, suspension et bougies horaires partielles.
- Prix de clôture du vendredi utilisable le dimanche en estimation ; clôture manquante toujours signalée ; FX avec horodatage et validité propres.
- Une position sans prix laisse le risque global incomplet sans empêcher le réexamen MACD d'une autre position fraîche.
- Graphiques : ordre chronologique, exclusion des bougies en cours et respect de la limite demandée.
- RSS/Atom : date originale et auteur brut, versions, réimport, HTTP 304, intervalle et backoff, reprise d'un bail expiré, refus des DTD/entités externes et réponses trop grandes.
- Une simple mention de nom ne devient pas une attribution. Une citation validée doit être présente exactement dans le texte, et son rejeu ne la duplique pas.
- Saisonnière : rendement depuis la clôture précédente, périodes finies seulement, exclusion des grands trous, échantillons annuels comptés en années, absence de données représentée par NULL.
- Scores : règles et seuils versionnés, marge nette sur exercices et dépôts compatibles, faits futurs ou du jour sans heure exclus, anciennes explications conservées, variation égale à la somme des contributions.
- Les API refusent les horizons non pris en charge, calendriers incomplets et poids de règles invalides. Le module saisonnier n'expose aucune analyse horaire.

## Mise à niveau et schéma réel

Les migrations V1 à V8 ont été appliquées depuis une base vide. Un test séparé crée un schéma V7 avec actif, liste de suivi, portefeuille, position et valorisation, puis applique V8. Le prix et son horodatage sont conservés, le nouvel horodatage FX reprend la date existante et la politique US est renseignée. Ce test passe sur H2 et PostgreSQL, dans son propre schéma temporaire.

Le schéma PostgreSQL inspecté compte **42 tables et 357 colonnes**, hors historique Flyway. Les colonnes sont décrites dans [DATABASE.md](DATABASE.md).

## Application empaquetée et redémarrage

L'application JAR a été démarrée sur PostgreSQL 17.7 isolé. Le script livré `scripts/local_backend_smoke.py` a réussi : 40 bougies explicitement fictives importées, réimport sans doublon, calendrier continu, explication enregistrée, statistiques interrogées et position déclarée.

Le portefeuille fictif a un capital de 1 000 EUR, un achat de 1 unité à 100 EUR, une valorisation à 95 EUR et une limite de perte de 100 EUR. Résultat vérifié : perte 5 EUR et budget restant 95 EUR. Il ne représente aucune transaction réelle.

Après redémarrage sur la même base, les 40 bougies, la position, l'explication enregistrée et les journaux de collecte sont toujours consultables. L'ordonnanceur marché, l'ordonnanceur d'informations et X sont restés désactivés.

## Essai réel des sources publiques

Un essai distinct a volontairement activé et appelé chacun des 12 flux RSS/Atom depuis le collecteur Java. Il a conservé **340 versions d'informations** dans la base isolée. Il n'a appelé aucun fournisseur payant ni envoyé d'ordre. Les articles restent à classifier ; une date absente ou future n'est pas transformée en publication scorable.

| Source | Informations insérées | Résultat |
|---|---:|---|
| ALJAZEERA_EN | 25 | SUCCEEDED |
| BBC_BUSINESS | 56 | SUCCEEDED |
| BOE_NEWS | 50 | SUCCEEDED |
| BOE_SPEECHES | 50 | SUCCEEDED |
| ECB_PRESS | 15 | SUCCEEDED |
| FED_PRESS | 20 | SUCCEEDED |
| FED_SPEECHES | 15 | SUCCEEDED |
| FED_TESTIMONY | 15 | SUCCEEDED |
| FRANCE24_FR | 24 | SUCCEEDED |
| LEMONDE_ECONOMIE | 20 | SUCCEEDED |
| UK_TREASURY | 20 | SUCCEEDED |
| UN_NEWS | 30 | SUCCEEDED |
| **Total** | **340** | **12 sources réussies** |

Les URL et résumés originaux ont été conservés dans l'essai, pas reproduits dans ce dépôt. Les nombres reflètent les flux reçus lors du test, pas une promesse de volume futur. Les flux individuels sont désactivés dans la configuration livrée ; les activer en local est une action explicite.

## Limites de la validation

La réussite des tests logiciels ne démontre aucune rentabilité ni probabilité de gain. Les coefficients, règles MACD/RSI et variation de marge sont expérimentaux. La saisonnalité reste descriptive avec contribution nulle. La classification des textes, l'identité des personnes et la corroboration sont validées humainement ; aucun modèle sémantique n'est connecté.

Les cours réels, droits d'un abonnement, couverture des 100 actions et 30 cryptos, historique intraday long et import de faits SEC réels n'ont pas été validés avec une clé de l'utilisateur. Les séances régulières US sont fournies pour 2026–2028 ; les autres places doivent être importées. Les pauses intrajournalières, séances traversant minuit, détection automatique des suspensions et exceptions propres aux fournisseurs FX restent à étendre.

Le correctif podcasts testé séparément n'est pas compté dans ces 103 tests et n'est pas intégré par ce lot. Les backtests financiers indépendants, les courbes de scénarios, Android, le push et l'authentification avant exposition réseau restent à réaliser.

Un second passage sur les sources a observé HTTP 503 sur BBC Business : les onze autres sources ont continué, l’échec a été conservé et un délai de quinze minutes appliqué. Cette indisponibilité externe ne transforme pas l’absence de nouveaux articles en avis favorable. La reprise manuelle à 01:01:44 UTC, après ce délai, a réussi : 56 informations ajoutées, dernier échec effacé de la source et ancien journal FAILED conservé à côté du nouveau SUCCEEDED. Le second passage atteint ainsi également 340 informations.
