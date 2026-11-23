# Actualités Bourse AI — trois collecteurs (V20)

## Architecture

1. **Web NGrams**: télécharge les deux archives GZIP officielles GDELT
   `YYYYMMDDHHmm00.ngrams.txt.gz` et `YYYYMMDDHHmm00.toc.json.gz`.
   Un scan tabulé par lot détecte les noms complets des entreprises de la
   watchlist, puis récupère les URL, dates et titres par DOCID. Un même lot
   couvre toutes les actions surveillées.
2. **GKG 2.0**: lit `https://data.gdeltproject.org/gdeltv2/lastupdate.txt`,
   récupère la dernière archive ZIP GKG et analyse les colonnes
   `Organizations` et `V2Organizations`. GKG ne fournit pas le titre original:
   une étiquette de provenance est enregistrée, jamais un titre inventé.
3. **RSS / Atom**: conserve `InformationService`, son catalogue officiel,
   son intervalle minimal par source, les retranscriptions et le worker Python
   optionnel. Les nouveaux endpoints unifiés délèguent à ce collecteur existant.

Le module ne consulte **jamais l'API DOC GDELT** qui peut renvoyer 429.
Les deux téléchargements GDELT sont sur `data.gdeltproject.org`;
l'index GKG et les archives sont contrôlés, sans URLs utilisateurs.
Pas de clé GDELT nécessaire. Tous les articles conservent leur URL d'origine.
Les doublons `asset_id+url` sont évités ; la même source peut être inspectée
par plusieurs entreprises sans inventer de direction boursière.

## Configuration

Dans le fichier racine `.env` (charger dans IntelliJ ou `bash start.sh`) :

```dotenv
NEWS_LOG_LEVEL=INFO
NEWS_BULK_ENABLED=false
NEWS_BULK_DELAY_MS=3600000
NEWS_BULK_INITIAL_DELAY_MS=300000
INFORMATION_SCHEDULER_ENABLED=false
INFORMATION_MINIMUM_INTERVAL_SECONDS=3600
```

L'activation `NEWS_BULK_ENABLED=true` lance périodiquement les trois
méthodes (GKG, NGRAMS, RSS). Si `INFORMATION_SCHEDULER_ENABLED=true`
également, éviter le double polling RSS; le verrou existant empêche une
collecte simultanée mais plusieurs planificateurs sont inutiles.
**Préférence** : pour une collecte unifiée, activer NEWS_BULK uniquement,
et laisser le scheduler RSS historique désactivé.

`NEWS_BULK_DELAY_MS` vaut une heure par défaut. Les archives GKG et NGrams couvrent les quatre créneaux de 15 minutes
les plus récents à chaque passage horaire (reprise idempotente). Jusqu’à dix
flux RSS prêts sont traités à chaque passage, sans ignorer les autres flux
si l’un échoue. Les opérations REST
manuelles fonctionnent même si la planification est désactivée.
Le backend reste lié à 127.0.0.1 en local. L'authentification administrateur
est nécessaire **avant toute exposition sur Internet**.

## Postman / curl

```bash
BASE=http://127.0.0.1:8081
curl -s "$BASE/api/v1/news-collection/status"
curl -s -X POST "$BASE/api/v1/news-collection/run?method=GKG"
curl -s -X POST "$BASE/api/v1/news-collection/run?method=NGRAMS"
curl -s -X POST "$BASE/api/v1/news-collection/run?method=RSS"
curl -s "$BASE/api/v1/news-collection/items?limit=50"
curl -s "$BASE/api/v1/information/items"
curl -s "$BASE/api/v1/news/NVDA"
```

L'endpoint NGRAMS accepte aussi un slot précis **publié** :
`?method=NGRAMS&slot=20261122233000`. Les minutes ne sont pas toutes
disponibles ; le dernier créneau GKG utilisé par défaut n'est pas garanti
présent chez NGrams, auquel cas un 503 annonce le lot non publié.

Les APIs retournent `method,batch,status,matched,inserted,message,code,retryable`.
Les erreurs sont structurées : `code,severity,message,retryable,retryAfterSeconds,
requestId,timestamp`. Le client Android utilise ces champs pour les avertissements
et les erreurs, sans jamais présenter une trace Java.

## PostgreSQL

Flyway V20 ajoute `gdelt_bulk_runs` et `gdelt_bulk_guard`.
Chaque `(method,batch_key)` est unique ; les imports atomiques sauvegardent
leur état et le nombre d'articles. Les échecs sont différés et au plus trois
tentatives par lot sont prévues. Les archives sont bornées en octets compressés et décompressés,
en nombre de lignes, longueur de ligne et nombre de correspondances.
Ne supprimez **pas** le volume PostgreSQL lors de la mise à jour.

## Limites assumées

- Les archives ne contiennent pas le texte intégral des articles. Le worker
  de contenu reste responsable de visiter les sites autorisés.
- Les NGrams reflètent des fréquences d'expressions ; une mention n'est pas
  une preuve de citation ou un signal d'achat.
- Le mode automatique cherche le dernier créneau fourni par l'index GKG.
  Pour une couverture exhaustive, une file de backfill des créneaux publiés
  et une meilleure découverte d'archives seront nécessaires.
- Les archives peuvent être volumineuses et ne sont pas garanties disponibles
  à tout moment. Le mode reste désactivé par défaut avant vérification de
  débit, réseau et droits de réutilisation.
