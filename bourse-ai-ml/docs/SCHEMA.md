# Schéma PostgreSQL du batch Python

Le processus Java utilise ses tables existantes ; Python possède uniquement le schéma `bourse_ml`, dans **la même base PostgreSQL**. Aucun changement Flyway Java ni écriture dans les règles actives n'est effectué. Les six tables nouvelles ne contiennent que des données de démonstration.

| Table | Clés et colonnes principales | Rôle |
|---|---|---|
| `schema_migrations` | `version`, `checksum`, `applied_at` | Migration indépendante avec vérification de son empreinte |
| `datasets` | `dataset_id`, `source`, `metadata`, `data_digest`, `row_count` | Définition immuable du jeu, graine, horizon, calendrier fictif, coût et empreinte des observations |
| `observations` | `(dataset_id, symbol, as_of)`, `feature_available_at`, `entry_at`, `label_at`, `features`, `baseline_score`, `entry_price`, `exit_price`, `net_return`, `adverse_close_return`, `stress_tag` | Informations disponibles au signal et résultat futur distinct, après échéance |
| `runs` | `run_id`, `dataset_id`, `configuration`, `status`, `attempts`, `summary`, `error_code` | Exécution reproductible, état et reprises |
| `models` | `(run_id, fold)`, `status`, `promotion_allowed`, `model`, `metrics`, `stress_results` | Coefficients/scaler JSON, performance hors entraînement et ventilations par scénario |
| `predictions` | `(run_id, fold, symbol, as_of)`, `dataset_id`, `estimate` | Estimation expérimentale hors entraînement, reliée au modèle et à son observation |

Les cinq tables métier comportent `created_time` et `updated_time` en `timestamptz`. Les observations et définitions de jeux ne sont jamais réécrites par le batch ; leurs dates restent celles de leur insertion. Les dates des signaux et résultats sont stockées séparément en UTC.

`features` contient cinq nombres ordonnés : technique, financier, informationnel, momentum du marché et volatilité. La normalisation est apprise exclusivement sur la période d'entraînement. Les étiquettes `stress_tag` restent des données d'évaluation et ne sont jamais introduites parmi ces cinq entrées. Le score de référence est une heuristique pédagogique ; il n'est pas une probabilité et ne rejoue pas le moteur Java historique.

Le résultat `net_return` est le rendement entre une ouverture fictive suivante et une clôture future, moins un coût total aller-retour en points de base. Le FX de démonstration vaut 1 et les dividendes valent 0. `adverse_close_return` est le pire rendement net observé aux **clôtures** pendant cet intervalle, pas un drawdown intraday. Ces nombres synthétiques sont des `double precision` pour le calcul ML ; aucune écriture comptable des portefeuilles Java n'est concernée.

Le verrou par exécution est un verrou de session PostgreSQL : deux processus ne peuvent pas entraîner simultanément le même jeu et la même configuration. L'apprentissage tourne hors d'une longue transaction. Les quatre modèles, prédictions et le statut final sont ensuite validés dans une transaction unique. Une interruption conserve un état `RUNNING` récupérable une fois le verrou libéré ; une erreur normale produit `FAILED`, sans résultat partiel. Une relance d'un calcul réussi réutilise le résultat.

Les contraintes imposent `feature_available_at <= as_of < entry_at <= label_at`, des actifs `DEMO_...`, une source `SYNTHETIC_DEMO` et des modèles `DEMO_ONLY` avec `promotion_allowed=false`. Les observations sont aussi vérifiées contre la date de disponibilité du jeu et son empreinte avant apprentissage.
