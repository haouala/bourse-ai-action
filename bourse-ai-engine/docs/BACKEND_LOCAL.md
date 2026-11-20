# Tester les modules backend en local

État du 20 novembre 2026. Les routes ci-dessous appartiennent à la branche `feature/portfolio-timeframes-alerts`, PR 1. Elles ne sont pas encore fusionnées dans `main`. Les tests utilisent une base isolée ; ils ne modifient pas la base de ton PC.

## Démarrer sans token

Prérequis : JDK 21 avec `javac`, Maven 3.9+, puis se placer dans `bourse-ai-engine`. Les calendriers, les statistiques et les explications utilisent la base locale. Les flux RSS publics ne demandent pas de token. Tous les ordonnanceurs et X sont arrêtés par défaut.

```bash
java -version
javac -version
mvn -version
mvn clean verify
mvn spring-boot:run
```

Cette commande utilise H2 en mémoire. Elle convient pour une démonstration ; les données disparaissent à l'arrêt. Vérifier `http://localhost:8081/actuator/health`.

Dans un second terminal, Python 3 permet de jouer un parcours complet avec des prix explicitement fictifs, sans appel à un fournisseur :

```bash
python3 scripts/local_backend_smoke.py
# Windows : py scripts/local_backend_smoke.py
```

Le script enregistre un actif `DEMO-*` désactivé dans la liste de suivi, importe 40 bougies, vérifie un réimport sans doublons, enregistre une explication et un portefeuille fictif. Il ne lance aucune collecte RSS ni X. Il affiche les identifiants à consulter. Ne pas utiliser cet actif comme donnée de marché ou comme opportunité réelle.

## Conserver les données dans PostgreSQL

Reprendre les identifiants de ta base existante, sans recréer ni effacer son volume. Le port local du projet est généralement 5434 ; adapter si nécessaire. Voir aussi [CONFIGURATION.md](../CONFIGURATION.md).

Linux ou Bash :

```bash
cp -n .env.example .env
# Renseigner localement DB_URL, DB_USER et DB_PASSWORD dans .env.
set -a
. ./.env
set +a
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

PowerShell :

```powershell
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:5434/bourse_ai'
$env:DB_USER = 'bourse'
$env:DB_PASSWORD = 'TON_MOT_DE_PASSE_LOCAL'
mvn spring-boot:run '-Dspring-boot.run.profiles=postgres'
```

Dans IntelliJ, définir le profil actif `postgres` et les mêmes variables dans la configuration de lancement. Spring Boot ne lit pas `.env` automatiquement. Les migrations V1 à V8 sont appliquées au démarrage ; ne pas modifier une migration déjà appliquée. Sauvegarder une base à conserver avant sa mise à niveau. La migration V8 ajoute notamment l'horodatage FX ; les marques existantes reprennent initialement la date de leur prix pour ce champ.

## Configuration par module

| Module | Token | Activation |
|---|---|---|
| Calendriers et état des marchés | Aucun | Calendrier régulier US 2026–2028 fourni ; affectation ou import pour une autre place |
| Saisonnalité mensuelle trimestrielle annuelle | Aucun | Historique quotidien déjà importé ; qualification des ajustements séparée |
| Explications et versions des règles | Aucun | Calcul à la demande sur les données présentes |
| RSS et Atom | Aucun pour le catalogue fourni | Activer chaque source via REST ; collecte manuelle ou ordonnanceur facultatif |
| Publications X | Bearer Token X | Désactivé ; token, activation et plafond explicites nécessaires |
| Cours Twelve Data | Clé Twelve Data | Nécessaire uniquement pour importer de vrais cours de ce fournisseur |
| Faits SEC | Contact réel dans `SEC_USER_AGENT` | Pas de token SEC ; définir une identification correcte de l'application |

```dotenv
MARKET_SCHEDULER_ENABLED=false
INFORMATION_SCHEDULER_ENABLED=false
INFORMATION_MINIMUM_INTERVAL_SECONDS=900
INFORMATION_SCHEDULER_DELAY_MS=60000
SOCIAL_X_ENABLED=false
SOCIAL_X_BEARER_TOKEN=
SOCIAL_X_MONTHLY_POST_LIMIT=0
```

Laisser ces valeurs pour commencer. Aucun import réel n'est lancé automatiquement. Le budget de marché n'inclut pas une consommation X éventuelle.

## Collecter une première source d'informations

```bash
curl http://localhost:8081/api/v1/information/sources
curl -X PUT http://localhost:8081/api/v1/information/sources/FED_PRESS/enabled \
  -H 'Content-Type: application/json' -d '{"enabled":true}'
curl -X POST http://localhost:8081/api/v1/information/sources/FED_PRESS/sync
curl 'http://localhost:8081/api/v1/information/items?source=FED_PRESS'
curl http://localhost:8081/api/v1/information/runs
```

Sous PowerShell, utiliser `curl.exe` pour ces exemples, ou `Invoke-RestMethod`. La commande PUT avec `Invoke-RestMethod` :

```powershell
Invoke-RestMethod -Method Put -Uri 'http://localhost:8081/api/v1/information/sources/FED_PRESS/enabled' -ContentType 'application/json' -Body '{"enabled":true}'
Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/api/v1/information/sources/FED_PRESS/sync'
```

Le catalogue contient 12 flux : communiqués, discours et témoignages Fed ; publications BCE ; actualités et discours Banque d'Angleterre ; Trésor britannique ; ONU ; BBC économie ; Le Monde économie ; Al Jazeera en anglais ; France 24 en français. Les droits de redistribution des contenus et l'accès aux articles complets ne sont pas déduits de l'accès au RSS. Seuls titres, résumés disponibles et liens sont collectés ; les paywalls ne sont pas contournés.

Deux appels immédiats sur une source peuvent produire HTTP 429 **local** : l'intervalle minimal ou le délai de reprise est actif. Une source désactivée produit 409. Un échec distant produit 502 avec un code assaini et un journal persistant ; sa collecte peut être relancée après le délai indiqué par `next_attempt_at`. Les réponses 304 ne créent pas de nouveaux articles.

Pour une collecte périodique volontaire, activer les sources souhaitées puis relancer avec `INFORMATION_SCHEDULER_ENABLED=true`. Le worker examine une source échue par passage, sans activer les sources désactivées. Il ne partage pas le bouton d'activation du fournisseur de cours.

Un catalogue supplémentaire peut être fourni avec `INFORMATION_CATALOG=file:/chemin/catalogue.json`, en reprenant le schéma de `information-sources.json`. Ce fichier est une configuration administrateur de confiance, pas une URL fournie par un article ou un modèle IA. Un nouveau code de source est inséré au démarrage. Une entrée existante n'est pas remplacée silencieusement : utiliser un nouveau code ou une migration contrôlée pour changer sa destination. Aucun endpoint public n'accepte une URL à télécharger arbitrairement.

## Personnalités et validation des paroles

L'enregistrement d'une personnalité passe par `POST /api/v1/social/actors`, puis son profil d'influence par `POST /api/v1/social/actors/{id}/profiles` ; voir [SOCIAL_SIGNALS.md](SOCIAL_SIGNALS.md).

Lors de la collecte, un nom reconnu crée `NAME_MATCH_ONLY`. Cela indique une mention, pas une preuve que cette personne a parlé. `author_text` conserve aussi le champ brut de la source sans le déclarer vérifié.

Après vérification humaine, `POST /api/v1/information/items/{id}/attributions` accepte :

```json
{
  "actorId": 123,
  "exactQuote": "Passage copié exactement depuis le résumé collecté",
  "evidenceUrl": "https://site-officiel.example/preuve"
}
```

Remplacer l'identifiant et le passage par ceux effectivement enregistrés. Le passage doit exister dans le texte collecté, sa date doit être utilisable et l'acteur doit être enregistré. La publication créée est marquée `HUMAN_VERIFIED_QUOTE`, avec URL originale et preuve ; un rejeu identique ne la duplique pas. Le backend ne vérifie pas à ta place l'identité du locuteur ni le sens du texte.

Cette route retourne `publicationId`, utilisable pour créer ou enrichir un événement canonique dans `/api/v1/social/events`. Classifier le sujet, la confirmation et l'exposition de chaque actif, puis enregistrer son évaluation. Une reprise doit être liée au même événement pour éviter de multiplier ses points. Les articles sans date fiable restent dans la boîte de collecte et ne deviennent pas des publications scorables.

## Calendriers et cours attendus

```bash
curl http://localhost:8081/api/v1/calendars
curl 'http://localhost:8081/api/v1/calendars/US_EQUITIES/sessions?from=2026-11-01&to=2026-12-31'
curl 'http://localhost:8081/api/v1/calendars/assets/NVDA/state?timeframe=15m'
```

La dernière route suppose que l'actif a déjà été enregistré. Le défaut `US_EQUITIES` ne concerne que les actifs déclarés US, fuseau `America/New_York`, séance régulière `09:30–16:00`. Les cryptos reçoivent `CRYPTO_24_7`. Les autres actifs sont `UNASSIGNED`, sans séance inventée.

Pour une autre place, importer un calendrier validé avec `POST /api/v1/calendars` :

```json
{
  "code": "MA_PLACE",
  "zoneId": "Europe/Paris",
  "sourceUrl": "https://place-officielle.example/calendrier",
  "sessions": [
    {"date":"2026-11-20","opensAt":"2026-11-20T08:00:00Z","closesAt":"2026-11-20T16:30:00Z","reason":"EXEMPLE_A_REMPLACER"},
    {"date":"2026-11-21","opensAt":null,"closesAt":null,"reason":"WEEKEND"},
    {"date":"2026-11-22","opensAt":null,"closesAt":null,"reason":"WEEKEND"}
  ]
}
```

Cet exemple montre le format, pas un calendrier officiel certifié. L'import doit inclure **chaque date** de sa couverture, y compris les jours fermés. Pour une utilisation durable, importer des années complètes depuis les horaires officiels de la place. Le remplacement doit conserver au moins la couverture précédente. Les séances de ce modèle sont continues sur leur date locale ; les marchés avec pause de midi ou séance traversant minuit nécessitent une extension.

Affecter ensuite la politique par `PUT /api/v1/calendars/assets/{symbol}` :

```json
{"calendarCode":"MA_PLACE","deliveryGraceSeconds":180,"tradingStatus":"ACTIVE","statusReason":null,"fxMaxAgeSeconds":3600}
```

La marge de livraison est configurable selon le fournisseur ; 180 secondes n'est pas une promesse de données en temps réel. Une suspension peut être déclarée avec `tradingStatus=HALTED`. Sa détection automatique par un flux de statuts n'est pas connectée.

Le prix doit couvrir la dernière bougie attendue, avec une estimation explicite au dernier cours lorsque le marché est fermé ou que la première bougie est encore attendue. Une clôture manquante reste périmée pendant le week-end. Une couverture inconnue produit `CALENDAR_UNKNOWN`.

Les marques acceptent `fxObservedAt`, distinct de `observedAt`. Pour compatibilité, son absence reprend la date du prix. Le contrôle FX utilise sa propre durée de validité et une convention de week-end spot du vendredi 17 h au dimanche 17 h à New York. Les jours fériés et conventions spécifiques d'un fournisseur FX restent à qualifier ; un taux de référence quotidien peut nécessiter une durée différente. Hors week-end, une action fermée ne rend pas un taux de change ancien automatiquement valable.

## Saisonnalité sans données horaires dédiées

```bash
curl http://localhost:8081/api/v1/seasonality/NVDA
curl -X POST http://localhost:8081/api/v1/seasonality/NVDA/snapshots
curl http://localhost:8081/api/v1/seasonality/NVDA/snapshots
```

Le module lit uniquement les bougies `1d`, ignore les mois/trimestres/années incomplets et mesure le rendement depuis la clôture précédant la période. Il présente fréquence historique de hausse, moyenne, médiane, dispersion, extrêmes et échantillon. Une case sans observation contient `null`, pas une fausse moyenne nulle.

L'état `ADJUSTMENT_UNVERIFIED` est normal tant que la série n'a pas été qualifiée. Après vérification effective des ajustements du fournisseur, une attestation peut être enregistrée :

```json
{"provider":"FOURNISSEUR_EFFECTIF","adjustment":"SPLIT_ADJUSTED","evidenceUrl":"https://fournisseur.example/documentation-verifiee"}
```

Route : `PUT /api/v1/seasonality/{symbol}/qualification`. Cette opération ne transforme pas les cours et ne doit pas servir à déclarer ajustée une série brute. Une série mélangeant des fournisseurs non qualifiés reste signalée. Les contrôles de trous sont conservateurs, sans certification de toutes les séances historiques. La contribution au score reste **zéro** : la saisonnalité est descriptive, en attente de validation indépendante, et ne constitue pas une probabilité future de hausse.

## Comprendre et comparer les scores

```bash
curl -X POST 'http://localhost:8081/api/v1/explanations/NVDA?horizon=DAY'
curl -X POST 'http://localhost:8081/api/v1/explanations/NVDA?horizon=MONTH'
curl -X POST 'http://localhost:8081/api/v1/explanations/NVDA?horizon=YEAR'
curl 'http://localhost:8081/api/v1/explanations/NVDA/history?horizon=MONTH'
curl http://localhost:8081/api/v1/explanations/snapshots/IDENTIFIANT_RETOURNE
curl http://localhost:8081/api/v1/explanations/rulesets
```

Les facteurs comprennent MACD/RSI sur 15m, 1h ou 1d selon l'horizon ; variation de marge nette sur deux exercices annuels SEC comparables ; contributions des événements sociaux/actualités classifiés ; statistiques saisonnières affichées sans points. La marge nette utilise résultat net/chiffre d'affaires, pas la marge opérationnelle. Les unités, périodes et dépôts doivent être compatibles ; une date de dépôt SEC sans heure n'est disponible qu'au lendemain UTC dans cette règle.

Chaque calcul est conservé avec valeurs, preuves, formule, méthode et version. Le score est un **classement heuristique expérimental**, jamais une probabilité de gain. `coverageScore` décrit les composantes disponibles. Des facteurs absents restent `MISSING`, anciens `STALE`, ambigus `UNRESOLVED` ; sans composante exploitable, `score` est `null`. Un prix inutilisable bloque tout avis présenté comme actuel mais laisse consultable l'explication des données connues. Les routes ne renvoient aucun ordre exécutable.

La comparaison avec la précédente analyse expose `previousSnapshotId`, `scoreChange` et `contributionChanges`. Leur somme correspond exactement à la variation du score lorsqu'il est calculable. Une nouvelle règle est créée, jamais modifiée en place :

```bash
curl -X POST http://localhost:8081/api/v1/explanations/rulesets \
 -H 'Content-Type: application/json' \
 -d '{"name":"EXPERIMENT_MONTH_V2","rules":{"technicalMax":10,"fundamentalMax":20,"informationMax":20,"marginChangeThreshold":4,"rsiOversold":30,"rsiOverbought":70}}'
# Puis POST /api/v1/explanations/NVDA?horizon=MONTH&rulesetId=ID_RETOURNE
```

La somme des amplitudes doit être comprise entre 1 et 50 pour conserver le score entre 0 et 100 autour d'une base 50. Une composante à zéro est désactivée explicitement. La création d'une variante n'établit pas son efficacité et ne remplace pas les défauts. Les backtests financiers indépendants, les courbes de scénarios et l'interface Android restent à développer ; ce lot prépare leurs données explicatives.

## Tokens des services facultatifs

Pour Twelve Data : créer un compte sur [le site officiel](https://twelvedata.com/), récupérer la clé du tableau de bord et définir `TWELVE_DATA_API_KEY` localement. Vérifier les droits et quotas de son compte avant tout import ; le logiciel ne souscrit aucune offre. [Documentation officielle](https://twelvedata.com/docs).

Pour SEC : renseigner `SEC_USER_AGENT='BourseAI contact-reel@domaine.tld'`, puis utiliser le CIK vérifié de l'émetteur. Les faits financiers déjà importés alimentent la règle de marge. Ne pas confondre le contact SEC avec un token.

Pour X : dans [la console développeur officielle](https://console.x.com), créer/configurer l'application autorisée à lire les données publiques, récupérer son Bearer Token, puis définir `SOCIAL_X_BEARER_TOKEN` uniquement localement. Ajouter un plafond `SOCIAL_X_MONTHLY_POST_LIMIT` explicitement choisi et au moins égal à 5 avant `SOCIAL_X_ENABLED=true`. Enregistrer le compte numérique vérifié et sa preuve d'identité, puis appeler `/api/v1/social/actors/{id}/sync-x`. Une invocation lit au plus une page de cinq publications ; suivre `morePages` pour la suite. L'accès et la facturation dépendent du compte X ; le plafond local de lectures ne garantit pas un plafond de facture. Aucun appel réel X ni achat n'est réalisé dans cette livraison.

Les anciens connecteurs EODHD/FMP conservent leurs variables existantes. Ne pas mettre de clé dans un fichier suivi par Git, une URL de catalogue RSS ou une capture partagée.

## Tests et diagnostic

```bash
mvn clean verify
# Même suite, uniquement sur une base PostgreSQL de test dédiée :
export BOURSE_TEST_DB_URL='jdbc:postgresql://127.0.0.1:5434/bourse_ai_test'
export BOURSE_TEST_DB_USER='utilisateur_test'
export BOURSE_TEST_DB_PASSWORD='mot_de_passe_test'
mvn clean verify
```

Les tests nettoient leurs tables : ne pas fournir la base à conserver. Le choix des tests ne dépend pas de `DB_URL` mais de `BOURSE_TEST_DB_URL`. Sans ces variables, H2 est utilisé et les fournisseurs sont simulés. Sous PowerShell, définir les trois mêmes noms avec `$env:NOM = 'valeur'`.

| Symptôme | Vérification |
|---|---|
| Connexion PostgreSQL refusée ou SCRAM | Profil actif, port, base, utilisateur et `DB_PASSWORD` réellement exportés |
| Relation absente | Bonne base et version Flyway V8 ; ne pas créer les tables à la main |
| `CALENDAR_UNKNOWN` | Affectation du calendrier et date couverte, y compris la séance précédente |
| `FX_STALE` | Date originale du taux et durée de validité, distinctes du prix de l'action |
| RSS 409/429 | Source activée, collecte en cours, intervalle minimal ou backoff |
| `DATE_MISSING` / `FUTURE_DATE` | Date du flux inutilisable ; le texte est conservé sans score automatique |
| Score absent ou provisoire | Composantes manquantes, événement non classifié, historique insuffisant ou prix inutilisable |

Ces API restent des routes de développement sans authentification utilisateur. Les conserver sur un environnement privé avant d'ajouter l'authentification pour l'application mobile ou l'exposition réseau.
