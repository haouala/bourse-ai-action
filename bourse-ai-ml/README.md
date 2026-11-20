# Bourse AI ML - batch Python indépendant

Python tourne dans un processus séparé, sur **la même base PostgreSQL que Java**, dans le schéma dédié `bourse_ml`. Le backend conserve ses tables et ses règles actives.

Cette première version travaille uniquement sur des **données fictives reproductibles**. Elle apprend des coefficients candidats, les évalue et les enregistre, puis quitte. Aucun token fournisseur ou IA, ordre de bourse, ordonnanceur ou modification du budget de perte n'est utilisé.

## Installation et lancement sous PowerShell

Python 3.12 est la version validée. Adapter les coordonnées PostgreSQL à celles de Java : le port 5434 ci-dessous correspond à sa configuration actuelle par défaut, pas au port HTTP 8081.

~~~powershell
git clone --branch feature/ml-batch-demo https://github.com/haouala/bourse-ai-action.git bourse-ml
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

Les résultats synthétiques ne valident aucune performance boursière réelle. Les coefficients logistiques ne remplacent pas directement les amplitudes Java : aucun import ou activation n'est fourni. L'adaptateur aux historiques réels et snapshots Java, la reconstruction point-in-time, les titres radiés, les vrais calendriers et les particularités crypto restent à développer et valider. Ce module n'ajoute ni LLM, ni nouvelles collectes, ni modification Android.
