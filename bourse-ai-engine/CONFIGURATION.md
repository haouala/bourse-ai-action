# Configuration et validation de Bourse AI

Java **JDK 21** (avec compilateur) et Maven 3.9+ sont nécessaires. L'application démarre via `lu.bourse.engine.BourseAiApplication`, sur le port **8081** par défaut.

## Accès privés

Les clés Twelve Data, EODHD et FMP sont lues depuis les variables d'environnement définies dans `application.yml`. Elles ne sont pas stockées dans Git. Ne pas partager une configuration IntelliJ contenant leurs valeurs.

Dans le dossier `bourse-ai-engine`, sous Linux / Bash :

```bash
cp -n .env.example .env
# Modifier .env localement. Ne jamais commiter ce fichier.
# Pour une valeur contenant des espaces, utiliser des guillemets simples.
set -a
. ./.env
set +a
mvn clean verify
mvn spring-boot:run
```

Spring Boot ne lit pas automatiquement `.env` : l'export ci-dessus doit être fait dans le terminal qui lance Maven. Les tests utilisent des clés fictives et un serveur HTTP local, jamais les API réelles.

Dans IntelliJ : Run > Edit Configurations > Environment variables. Renseigner les clés et, si nécessaire, les paramètres PostgreSQL.

## H2 ou PostgreSQL

Sans profil, H2 est une base **en mémoire** : toutes les données, y compris les compteurs de quotas, disparaissent lorsque le processus s'arrête. Ce mode sert aux essais. Ne pas l'utiliser pour les imports réels répétés : un redémarrage ferait perdre le suivi de consommation.

Pour conserver les cours et les compteurs, utiliser le profil `postgres`. Configuration locale par défaut : **PostgreSQL 17**, hôte **127.0.0.1**, port publié **5434**, base **bourse_ai**, utilisateur **bourse**. Dans le conteneur, PostgreSQL écoute toujours sur 5432.

### Conteneur déjà créé manuellement

Si `bourse-postgres17` existe déjà sur ton PC, démarrer ce conteneur :

```bash
sudo docker start bourse-postgres17
sudo docker exec bourse-postgres17 psql -U bourse -d bourse_ai -c 'SELECT version();'
```

Dans le `.env` privé, renseigner :

```dotenv
DB_URL=jdbc:postgresql://127.0.0.1:5434/bourse_ai
DB_USER=bourse
DB_PASSWORD='TON_MOT_DE_PASSE_EXISTANT'
```

Après modification, exporter de nouveau `.env`, puis lancer :

```bash
set -a
. ./.env
set +a
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

Utiliser le même hôte, port, nom de base et utilisateur dans DBeaver. Le mot de passe est celui défini à la création du conteneur. Modifier `.env` ne modifie pas le mot de passe d'une base déjà initialisée.

### Nouvelle installation avec Docker Compose

Utiliser cette option uniquement s'il n'y a pas déjà de conteneur du projet sur le port 5434. Compose utilise son propre volume `bourse_pgdata` ; il ne reprend pas automatiquement le volume du conteneur manuel `bourse17_pgdata`. Ne pas lancer les deux installations sur le même port ni supprimer un volume pour résoudre un conflit.

L'image Compose est `postgres:17-bookworm` avec stockage persistant et redémarrage automatique. Utiliser un moteur Docker compatible avec les images récentes ; l'ancien Docker 19.03 peut empêcher leur initialisation. Le port publié 5434 évite les PostgreSQL locaux déjà présents sur 5432 et 5433.

Pour une nouvelle installation :

```bash
# Après l'export des variables .env, dont DB_PASSWORD :
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

Adapter `DB_URL`, `DB_USER` et `DB_PASSWORD` à la base utilisée. Les valeurs du fichier exemple correspondent au service Docker fourni. Sur une base déjà installée, conserver ses identifiants existants. Le mot de passe PostgreSQL n'a pas de valeur par défaut dans cette configuration.

Les migrations Flyway V1 à V8 sont appliquées au démarrage. Ne pas modifier les anciennes migrations sur une base déjà initialisée. V5 ajoute les quotas ; V6 les positions et le suivi ; V7 les événements sociaux ; V8 les calendriers, les sources d'informations, la saisonnalité et les explications. Voir [le guide local des nouveaux modules](docs/BACKEND_LOCAL.md).

## Budgets d'appels

| Fournisseur | Budget local par 24h glissantes | Budget local par 60 secondes glissantes | Variables facultatives |
|---|---:|---:|---|
| Twelve Data | 780 crédits | 8 crédits | `TWELVE_DATA_DAILY_LIMIT`, `TWELVE_DATA_MINUTE_LIMIT` |
| EODHD | 18 crédits | 20 crédits | `EODHD_DAILY_LIMIT`, `EODHD_MINUTE_LIMIT` |
| FMP | 240 appels | 5 appels | `FMP_DAILY_LIMIT`, `FMP_MINUTE_LIMIT` |

Ces budgets sont les limites locales historiques configurées dans le projet, pas une confirmation des droits commerciaux actuels d'un compte. Le plafond FMP par minute est aussi une règle interne. Les limites sont configurables à la baisse ; une limite nulle bloque le fournisseur. Cette version conserve les plafonds pris en charge par son validateur ; vérifier l'offre et le coût réels avant toute activation.

Les fournisseurs ne réinitialisent pas tous leur quota à la même heure. L'application utilise des fenêtres glissantes, plus conservatrices : **minuit ne remet pas ses compteurs à zéro**. Une réservation sort de la fenêtre après 24h ou 60 secondes.

- Chaque endpoint actuellement intégré avec clé fait une requête pour un symbole et réserve un crédit **avant** son envoi.
- La réservation est validée dans une transaction indépendante : une erreur HTTP ou l'annulation de l'import ne la rembourse pas.
- Un verrou PostgreSQL par fournisseur empêche deux instances partageant la même base de dépasser ensemble leur budget.
- Une réservation refusée renvoie HTTP 429, sans appel au fournisseur. `Retry-After` indique un délai avant réévaluation, lorsque le fournisseur est actif.
- Un HTTP 429 du fournisseur, ou un code JSON 429 de Twelve Data, suspend ce fournisseur pour 24h. Aucun nouvel essai automatique n'est effectué.
- Les redirections HTTP ne sont pas suivies. Les délais de connexion/lecture sont limités à 5/20 secondes.
- Les erreurs du transport n'enregistrent ni URL avec clé, ni corps d'erreur distant, ni cause contenant des secrets.
- GDELT et SEC restent hors des compteurs de ces trois offres ; leurs propres règles d'accès continuent à s'appliquer.
- Les futurs endpoints bulk, batch ou à coût multiple devront déclarer leur coût avant d'être ajoutés. Ils ne sont pas intégrés ici.

**Portée du compteur :** uniquement les appels effectués par cette version de Bourse AI avec la même base persistante. Les appels depuis un autre outil, une autre base ou avant l'installation ne sont pas visibles. Réduire le budget local en conséquence ; il ne s'agit pas du solde réel du compte fournisseur. Conserver les mêmes limites et une horloge synchronisée pour les instances partageant une base. La limite de bande passante FMP et les droits d'accès par formule ne sont pas gérés par ces compteurs.

## Vérifier les quotas et le premier import

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8081/api/v1/providers/quotas
# Un seul import réel après configuration de la clé :
curl -X POST 'http://localhost:8081/api/v1/providers/twelve-data/sync/NVDA?years=1'
curl http://localhost:8081/api/v1/history/analysis/NVDA
curl http://localhost:8081/api/v1/investment/explain/NVDA
```

La consultation des quotas et les autres routes GET ci-dessus lisent la base, sans requête aux fournisseurs. Le résultat des quotas expose les limites, réservations, disponibilités et éventuelle suspension ; aucune clé n'est retournée. `enabled` concerne le budget local et ne prouve pas qu'une clé a été configurée.

## Tests PostgreSQL facultatifs

Par défaut, `mvn clean verify` utilise H2 et un serveur fournisseur simulé. Pour exécuter les mêmes tests sur PostgreSQL, fournir une **base de test dédiée et vide** :

```bash
export BOURSE_TEST_DB_URL='jdbc:postgresql://127.0.0.1:5434/bourse_ai_test'
export BOURSE_TEST_DB_USER='utilisateur_test'
export BOURSE_TEST_DB_PASSWORD='mot_de_passe_test'
mvn clean verify
```

Les tests d'intégration effacent leurs tables de données : **ne jamais pointer ces variables sur une base à conserver**. Sans ces variables, les tests n'utilisent pas la base PostgreSQL de l'application.

Par défaut, aucun import n'est déclenché au démarrage. Les activations marché, RSS et X sont indépendantes. Le module social et les explications déterministes sont présents ; la classification sémantique par LLM, l'intégration du correctif podcasts et les notifications push restent des étapes ultérieures. Les routes de développement doivent être protégées avant toute exposition sur Internet.


## Console d’administration Android

Définir un jeton dédié `ADMIN_MONITOR_TOKEN` dans l’environnement du backend ou dans le fichier `.env` chargé par `start.sh`. Utiliser un secret aléatoire de 32 caractères minimum. Redémarrer le backend, puis ouvrir **Réglages → Administration** dans Android en mode serveur. Le jeton reste en mémoire sur l’écran et l’API renvoie un diagnostic en lecture seule. En production, utiliser HTTPS.

Le contrôle des cours couvre jusqu’à 21 séances admissibles par action suivie. Il distingue les bougies absentes, les valeurs OHLCV incohérentes et les titres impossibles à vérifier sans qualification ou calendrier. Un total porte seulement sur les séries évaluables. Le dernier passage de réparation, même sans manque, est enregistré en base à partir de cette version. L’heure de passage attendue suit le cron configuré à New York, avec 30 minutes de tolérance. Les autres batchs affichent leurs derniers événements et leurs retards de file lorsqu’il y a des tâches à traiter. L’entraînement ML conserve son déclenchement manuel.
