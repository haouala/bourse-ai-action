# Configuration et validation de Bourse AI

Java **JDK 21** (avec compilateur) et Maven 3.9+ sont nécessaires. L'application démarre via `lu.bourse.engine.BourseAiApplication`, sur le port **8081** par défaut.

## Accès privés

Les clés Twelve Data, EODHD et FMP sont lues depuis les variables d'environnement définies dans `application.yml`. Elles ne sont pas stockées dans Git. Ne pas partager une configuration IntelliJ contenant leurs valeurs.

Dans le dossier `bourse-ai-engine`, sous Linux / Bash :

```bash
cp .env.example .env
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

Pour conserver les cours et les compteurs, utiliser PostgreSQL :

```bash
# Après l'export des variables .env, dont DB_PASSWORD :
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

Adapter `DB_URL`, `DB_USER` et `DB_PASSWORD` à la base utilisée. Les valeurs du fichier exemple correspondent au service Docker fourni. Sur une base déjà installée, conserver ses identifiants existants. Le mot de passe PostgreSQL n'a pas de valeur par défaut dans cette configuration.

Les migrations Flyway V1 à V5 sont appliquées au démarrage. Ne pas modifier les anciennes migrations sur une base déjà initialisée. V5 ajoute `provider_quota_locks` et `provider_api_calls`.

## Budgets d'appels

| Fournisseur | Budget local par 24h glissantes | Budget local par 60 secondes glissantes | Variables facultatives |
|---|---:|---:|---|
| Twelve Data | 780 crédits | 8 crédits | `TWELVE_DATA_DAILY_LIMIT`, `TWELVE_DATA_MINUTE_LIMIT` |
| EODHD | 18 crédits | 20 crédits | `EODHD_DAILY_LIMIT`, `EODHD_MINUTE_LIMIT` |
| FMP | 240 appels | 5 appels | `FMP_DAILY_LIMIT`, `FMP_MINUTE_LIMIT` |

Ces budgets gardent une marge sous les plafonds gratuits annoncés (Twelve Data 800/jour, EODHD 20/jour, FMP 250/jour). Le plafond FMP par minute est une règle prudente de l'application. Les limites sont configurables à la baisse ; une limite nulle bloque le fournisseur. Cette version rejette les valeurs supérieures aux plafonds gratuits pris en charge.

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
export BOURSE_TEST_DB_URL='jdbc:postgresql://localhost:5432/bourse_ai_test'
export BOURSE_TEST_DB_USER='utilisateur_test'
export BOURSE_TEST_DB_PASSWORD='mot_de_passe_test'
mvn clean verify
```

Les tests d'intégration effacent leurs tables de données : **ne jamais pointer ces variables sur une base à conserver**. Sans ces variables, les tests n'utilisent pas la base PostgreSQL de l'application.

Le module ne déclenche aucun import au démarrage. Les podcasts, l'analyse LLM, le scoring des intervenants et les notifications restent des étapes ultérieures. Les routes de développement doivent être protégées avant toute exposition sur Internet.
