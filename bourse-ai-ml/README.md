# Bourse AI ML - batch Python indépendant

## Variables techniques du mode marché V2

`train-market` compare maintenant les quatre variables de prix existantes à un jeu
enrichi de 18 variables : bougies, confirmations datées, volume, triples, Elliott,
Donchian, ATR, Bollinger et tendance SMA 20/50. Le Brier de validation choisit à la
fois le modèle et le jeu de variables ; le test final reste réservé à la mesure.
Le rapport publie `selectedFeatureSet` et `featureComparison`, visibles dans Android.
Les valeurs manquantes ne deviennent pas de faux signaux neutres. Les anciens
résultats restent consultables ; une nouvelle empreinte impose le réentraînement.

Le prochain `train-market` utilise automatiquement ces variables si les OHLC et le
calendrier qualifié sont disponibles. Un modèle de prix seuls peut rester retenu
si sa validation est meilleure. Ce périmètre est distinct du mode **démo synthétique**
`PRICE_ONLY/ENRICHED` décrit plus bas. Voir [méthodes, disponibilité et limites](../bourse-ai-engine/TECHNICAL_ANALYSIS.md).

Python tourne dans un processus séparé, sur **la même base PostgreSQL que Java**, dans le schéma dédié `bourse_ml`. Le backend conserve ses tables et ses règles actives.

Le mode démonstration utilise des données fictives reproductibles. Le mode marché lit les historiques qualifiés du backend PostgreSQL, compare deux modèles et publie les résultats de recherche, puis quitte. Aucun token fournisseur ou IA, ordre de bourse, ordonnanceur ou modification du budget de perte n'est utilisé.

## Installation et lancement sous PowerShell

Python 3.12 est la version validée. Adapter les coordonnées PostgreSQL à celles de Java : le port 5434 ci-dessous correspond à sa configuration actuelle par défaut, pas au port HTTP 8081.

~~~powershell
git clone --branch integration/unified-bourse-ai https://github.com/haouala/bourse-ai-action.git bourse-ml
cd bourse-ml\bourse-ai-ml
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.lock
.\.venv\Scripts\python.exe -m pip install --no-deps -e .

$env:PGHOST = "127.0.0.1"
$env:PGPORT = "5434"
$env:PGDATABASE = "bourse_ai"
$mlCredential = Get-Credential -UserName "bourse" -Message "Connexion PostgreSQL"
$env:PGUSER = $mlCredential.UserName
$env:PGPASSWORD = $mlCredential.GetNetworkCredential().Password

.\.venv\Scripts\python.exe -m bourse_ai_ml init-db
.\.venv\Scripts\python.exe -m bourse_ai_ml demo --assets 8 --horizon MONTH
.\.venv\Scripts\python.exe -m bourse_ai_ml status
~~~

Les mots de passe sont séparés de l'adresse : leurs caractères spéciaux ne sont pas à encoder dans une URL. `.env.example` est documentaire, pas chargé automatiquement. Les variables peuvent aussi être remplacées par un fichier pgpass libpq ; une DSN peut être transmise par l'environnement `BOURSE_ML_DSN`, jamais par une option CLI. Pour un serveur distant, configurer le TLS libpq approprié, par exemple PGSSLMODE=verify-full avec le certificat de confiance.

| Java | Python |
|---|---|
| DB_URL=jdbc:postgresql://127.0.0.1:5434/bourse_ai | PGHOST=127.0.0.1, PGPORT=5434, PGDATABASE=bourse_ai |
| DB_USER | PGUSER |
| DB_PASSWORD | PGPASSWORD ou pgpass |

Java doit utiliser le profil **postgres** pour partager cette base. Sa base H2 volatile par défaut n'est pas prise en charge. Java peut être arrêté pendant le batch ; PostgreSQL doit rester démarré.

Sous Linux/macOS, créer le venv avec `python3.12 -m venv .venv`, puis employer `.venv/bin/python` pour les mêmes commandes. Définir PGHOST, PGPORT, PGDATABASE et PGUSER avec `export`. Saisir le mot de passe avec `read -r -s -p "Mot de passe : " PGPASSWORD`, puis `export PGPASSWORD`.

## Commandes et résultats

~~~bash
python -m bourse_ai_ml demo --horizon DAY --assets 8
python -m bourse_ai_ml demo --horizon MONTH --assets 8
python -m bourse_ai_ml demo --horizon YEAR --assets 8
python -m bourse_ai_ml status
python -m bourse_ai_ml report IDENTIFIANT_DU_CALCUL --output output
~~~

Utiliser le Python du venv. La graine `--seed` vaut 42, le nombre `--assets` accepte 1 à 100, et `--cost-bps` vaut 20 points de base **au total sur l'aller-retour**. La même configuration réutilise le résultat existant ; elle ne crée pas de doublon. Un changement de graine, de coût ou d'horizon définit un nouveau jeu.

Les fichiers apparaissent dans `output/<run_id>/` :

| Fichier | Contenu |
|---|---|
| summary.json | Périodes, métriques hors entraînement, résultats par scénario et limites |
| models.json | Normalisation et coefficients JSON de chaque modèle, sans pickle |
| predictions.csv | Estimations et résultats observés de chaque signal de test |
| complete.json | Empreintes des trois fichiers exportés |

Les résultats PostgreSQL sont atomiques. Si seul l'export échoue, la commande `report` le refait sans réentraîner. Les erreurs de connexion n'affichent ni mot de passe ni DSN.

## Données et apprentissage

Les actifs DEMO_001, etc., sont générés de 2003 à 2025. Les prix, composantes financières et informations sont tous synthétiques. Les scénarios portant les étiquettes 2008, Covid et 2022 **ne sont pas des historiques réels de crise**.

Cinq composantes sont disponibles à chaque clôture fictive : technique, financier, informationnel, momentum du marché et volatilité. Le score de référence est une heuristique pédagogique reprenant des amplitudes ; il ne rejoue pas les scores Java passés et n'est pas une probabilité.

L'entrée simulée est l'ouverture suivante. La cible est une hausse nette après 1, 21 ou 252 **jours de semaine fictifs** pour DAY, MONTH ou YEAR. Le FX vaut 1 et les dividendes zéro. Ce calendrier n'est pas un calendrier réel de marché.

| Découpage | Entraînement | Validation | Test |
|---|---|---|---|
| Crise financière fictive | 2003-2005 | 2006-2007 | 2008-2009 |
| Covid fictif | 2003-2017 | 2018-2019 | 2020-2021 |
| Géopolitique fictive | 2003-2019 | 2020-2021 | 2022-2023 |
| Dernière période réservée | 2003-2021 | 2022-2023 | 2024-2025 |

Toute observation dont le résultat déborde sur la période suivante est retirée, notamment pour l'horizon annuel. Toutes les actions d'une même date restent dans le même groupe chronologique. Les étiquettes de crise servent seulement à ventiler l'évaluation, jamais comme entrées rétrospectives.

La normalisation utilise uniquement l'entraînement. La régression logistique compare trois régularisations sur la validation ; le test ne choisit aucun poids. Le rapport fournit Brier, log loss, AUC si les deux classes existent, groupes de calibration et statistiques de signaux sélectionnés. Brier est comparé à une probabilité constante apprise sur l'entraînement.

Les signaux sont corrélés et leurs horizons peuvent se chevaucher : leurs rendements ne sont pas cumulés pour inventer un rendement de portefeuille ou un Sharpe ratio. Le pire rendement observé aux clôtures n'est pas un drawdown intraday.

## Base de données et droits

Les [six tables](docs/SCHEMA.md) contiennent les migrations, jeux, observations, exécutions, modèles et prédictions. La migration est indépendante de Flyway. Aucun accès aux tables publiques du backend n'est nécessaire.

Pour l'essai local, le compte doit pouvoir créer le schéma. Pour séparer les droits, faire exécuter init-db par le propriétaire, puis attribuer au compte du batch USAGE sur le schéma et SELECT, INSERT, UPDATE sur ses tables. Le fonctionnement sans droit sur les tables du backend est testé.

Un verrou empêche deux processus d'entraîner simultanément le même calcul. Un calcul interrompu est récupérable ; les modèles et prédictions ne restent pas partiellement enregistrés. Les modèles ont obligatoirement le statut DEMO_ONLY avec promotion_allowed=false.

## Tests

~~~bash
python -m pytest -m "not postgres" -q
~~~

Pour la suite complète, transmettre BOURSE_ML_TEST_DSN vers une base **jetable**, nommée bourse_ml_test_..., puis lancer `python -m pytest -q`. Les tests recréent le schéma ML et refusent les autres noms de base. Ne pas leur fournir votre base de travail.

`scripts/shared_backend_smoke.py --jar /chemin/backend.jar` est réservé à une base locale jetable **vide**. Il démarre le vrai Java V8, exécute Python dans cette même base, vérifie la relance sans doublon et compare toutes les tables publiques avant/après. La commande normale du batch ne lance pas Java. La CI effectue cet essai puis la suite PostgreSQL.

Voir [docs/VALIDATION.md](docs/VALIDATION.md) pour les résultats effectivement vérifiés.

## Limites

Les résultats synthétiques ne valident aucune performance boursière réelle. Les coefficients logistiques ne remplacent pas directement les amplitudes Java : aucun import ou activation n'est fourni. Voir le mode marché ci-dessous pour le périmètre validé et ses limites.


## Apprendre sans historique d'informations

`python -m bourse_ai_ml demo --feature-set PRICE_ONLY --assets 8 --horizon MONTH` utilise seulement `technical`, `market_momentum`, `volatility`. Les composantes informationnelles et fondamentales ne sont ni consommées ni remplacées par des zéros. `ENRICHED` conserve le mode précédent à cinq composantes et son identité de configuration. Les deux modes partagent un jeu synthétique mais ont des identifiants de calcul et des coefficients distincts.

Le noyau de calcul accepte l'absence de valeurs non utilisées en mode PRICE_ONLY. Le schéma de démonstration stocke ses cinq composantes générées. La référence heuristique enrichie n'est pas présentée comme une comparaison équivalente d'un modèle cours seuls. Le modèle constant de référence reste disponible. Aucun modèle n'est activé dans Java.


## Entraîner sur la base de marché

Démarrer Java avec le profil PostgreSQL pour appliquer Flyway V23. Les commandes utilisent les mêmes paramètres de connexion que la démo. Choisir une date de début couverte par le calendrier et un horodatage de coupure explicite. Sans `--symbols`, la sélection utilise les actions activées dans la watchlist, jusqu’à 100. La commande de couverture indique les séances attendues, les cours au bon format et les observations utilisables par action.

```bash
python -m bourse_ai_ml market-coverage --start 2026-01-01 --as-of 2026-11-24T23:00:00Z --horizon DAY --symbols NVDA MSFT
python -m bourse_ai_ml train-market --start 2026-01-01 --as-of 2026-11-24T23:00:00Z --horizon DAY --symbols NVDA MSFT
python -m bourse_ai_ml market-report IDENTIFIANT_DU_CALCUL
```

**Prérequis par action**

- Importer les bougies journalières dans `market_candles`. La date journalière doit être stockée à minuit UTC, comme les imports Java.
- Assigner un calendrier vérifié avec toutes les séances historiques nécessaires et qualifier la série `SPLIT_ADJUSTED` avec son fournisseur et sa preuve. Le calendrier US livré commence en 2026. Importer la couverture officielle plus ancienne lorsque l’entraînement l’exige.
- Laisser au minimum 21 séances pour construire les composantes. Chaque découpage exige ensuite au moins 15 dates et 30 observations dans ses trois sections après purge des étiquettes. Pour 21 ou 252 séances de prévision, prévoir davantage d’historique. Une lacune dans une fenêtre rend cette observation ou son résultat indisponible.

Les composantes sont calculées à partir des clôtures jusqu’à la séance de référence. L’entrée simulée est l’ouverture suivante et la sortie la clôture après 1, 21 ou 252 séances du calendrier. Le coût aller-retour de 20 points de base par défaut est retranché du rendement brut. La régression logistique et le gradient boosting sont ajustés sur le passé. Le score de Brier de validation choisit le modèle avant l’évaluation finale. Le rapport regroupe les tests chronologiques, la calibration, le Brier, la log loss, l’AUC si applicable et le benchmark constant.

Le backtest financier compare, par devise, des groupes de positions équipondérées sans chevauchement. Le modèle sélectionne un titre à partir d’une estimation de 60 %. Le benchmark prend tous les titres admissibles à la même date. Les frais configurés sont inclus. Le drawdown utilise les points de liquidation des groupes. Le rapport marque les périodes trop courtes et indique si le modèle dépasse le benchmark constant sur le dernier test.

Les rapports et estimations expérimentales sont enregistrés atomiquement dans `ml_market_runs` et `ml_market_predictions`. Un même contenu et une même configuration réutilisent le calcul existant. Pour un compte distinct, donner SELECT sur `assets`, `watchlist`, `asset_market_policies`, `market_sessions`, `daily_series_qualifications` et `market_candles`, et SELECT/INSERT sur les deux tables de résultats. Le mode marché publie uniquement des résultats de recherche. Le backend expose `GET /api/v1/ml/market/{symbol}` et l’écran Android **Intelligence** les affiche. Java masque l’estimation lorsque la clôture de référence est ancienne ou non vérifiable.

**Limites de validation.** La sélection dépend des actions et des historiques disponibles actuellement. Les cours révisés, les retards historiques d’ingestion et les titres radiés ne sont pas reconstruits. Le backtest utilise les rendements en devise locale et ne simule ni conversion FX, ni dividendes, ni slippage variable, ni risque intraday. Les tests GitHub utilisent un jeu de cours artificiels dans le vrai schéma PostgreSQL de Java. Pour mesurer les performances sur vos 100 actions, lancer d’abord `market-coverage` sur votre base puis conserver le rapport `train-market` avec la période et la couverture effectivement utilisées.
