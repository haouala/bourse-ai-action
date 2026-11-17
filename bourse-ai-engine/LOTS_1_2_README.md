# Bourse AI — Lots 1 et 2 + Europe (correctif à superposer)

Le dépôt `haouala/bourse-ai-action` contient maintenant une classe de démarrage autonome. Les instructions de référence sont dans [CONFIGURATION.md](CONFIGURATION.md), notamment pour les compteurs persistants de quotas.

## 1. Comptes gratuits

| Source | Où créer le compte | Clé / accès | Contraintes |
|---|---|---|---|
| Twelve Data | https://twelvedata.com/pricing | Tableau de bord > API Key | 8 crédits/minute et 800 crédits/jour (Basic) ; vérifier couverture des marchés UE |
| EODHD | https://eodhd.com/pricing/ | Dashboard > API token | Free : 20 appels/jour, 1 an EOD, quelques services exclus |
| FMP (facultatif) | https://site.financialmodelingprep.com/developer/docs/pricing | Dashboard > API key | Liste d'actifs et endpoints gratuits limités |
| SEC EDGAR | https://www.sec.gov/search-filings/edgar-application-programming-interfaces | Sans clé ; User-Agent identifiant + contact valide | Emetteurs déclarants américains ; récupérer le bon CIK |
| GDELT | https://blog.gdeltproject.org/gdelt-doc-2-0-api-debuts/ | Aucune clé | Liens/titres d'articles ; requêtes limitées, pas de garantie sur le contenu complet |

Ne **jamais** commiter `.env`, les secrets ou les tokens ; le `.gitignore` du correctif masque `.env`.

## 2. Démarrage

Ouvrir un terminal **dans `bourse-ai-engine`**. Java 21 + Maven 3.9+, Docker Compose.

```bash
cp .env.example .env
# éditer .env et choisir un mot de passe DB fort ; renseigner les tokens obtenus
# pour charger les variables dans le shell qui lance Spring Boot :
set -a; . ./.env; set +a

docker compose up -d
mvn clean test
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

`application.yml` utilise H2 volatile si on ne sélectionne pas `postgres`; les données H2 et compteurs disparaissent au redémarrage. Pour les conserver, **utiliser le profil postgres**. Port par défaut : 8081, configurable avec `SERVER_PORT`.

## 3. Import et tests API

```bash
# Exemple US : 5 ans de données quotidiennes (sous réserve des droits du plan)
curl -X POST 'http://localhost:8081/api/v1/providers/twelve-data/sync/NVDA?years=5'
# Indicateurs SMA20 RSI14 MACD sur données importées
curl 'http://localhost:8081/api/v1/history/analysis/NVDA'
# Europe : une année maximum gratuitement, code de place à vérifier chez EODHD
curl -X POST 'http://localhost:8081/api/v1/providers/eodhd/sync/BNP.PA?providerSymbol=BNP.PA&currency=EUR&name=BNP%20Paribas'
curl 'http://localhost:8081/api/v1/providers/eodhd/observations/BNP.PA'
# Actualités GDELT sans token (l'actif doit avoir été importé)
curl -X POST 'http://localhost:8081/api/v1/providers/gdelt/sync/NVDA?max=25'
curl -X POST 'http://localhost:8081/api/v1/news/analyze/NVDA'
curl 'http://localhost:8081/api/v1/news/NVDA'
# SEC : le CIK doit correspondre à la société — NVDA = 1045810
curl -X POST 'http://localhost:8081/api/v1/providers/sec/sync/NVDA?cik=1045810'
curl 'http://localhost:8081/api/v1/fundamentals/NVDA'
# FMP facultatif : endpoint selon offre gratuite
curl -X POST 'http://localhost:8081/api/v1/providers/fmp/sync/NVDA'
curl 'http://localhost:8081/api/v1/company/NVDA'
# Dossier d'explication déterministe (PAS une IA intégrée)
curl 'http://localhost:8081/api/v1/investment/explain/NVDA'
```

## 4. Données, schéma et limitations

- Migrations Flyway V1 à V5 : `assets`, `market_candles`, `market_news`, `financial_facts`, `company_profiles`, `import_jobs`, `asset_provider_symbols`, `provider_price_observations`, `provider_quota_locks`, `provider_api_calls`.
- `created_time`, `updated_time` pour toutes les tables principales. Dates de marché distinctes des dates de collecte.
- **Le modèle financier Java fournit des formules, PAS de promesse de rendement.** Le sentiment de news est pour l'instant une règle simple de mots clés en anglais, PAS une IA.
- Une observation de marché européenne issue d'EODHD est stockée dans sa table de provenance et dans la série canonique si la date n'y figure pas déjà.
- **Import historique sur cinq ans pour l'Europe non garanti avec les offres gratuites.** EODHD gratuit : 1 an ; Twelve Data Basic : selon place/symbole.
- Prévoir validation qualité stricte pour splits, dividendes, taux de change, devise du titre, distinction cours ajusté/non ajusté et collisions de symboles. D'abord usage expérimental et personnel, vérifier les licences avant tout partage public ou redistribution dans l'app Android.
- Les API ajoutées sont des routes de développement sans authentification utilisateur. **Ne pas exposer sur Internet** avant ajout d'un contrôle d'accès et de quotas.
- Aucune connexion LLM payante ou locale n'est ajoutée : l'explication actuelle est basée sur les formules et données récupérées.
- La suite de 16 tests passe sur H2 et PostgreSQL 17 avec des réponses fournisseurs simulées. Les clés et accès réels doivent encore être validés localement. Voir `GET /api/v1/providers/quotas` pour les réservations de cette application.

## 5. Prochaine étape Android (à conserver)

Application Android avec grille de cartes d'actions ; tap sur une action → détail JSON avec prix, fondamentaux, news, source et horodatages ; bouton « Analyser » → explication des formules, événements, risque et sources via un modèle IA explicite (local ou API, coûts à confirmer). Le document `EUROPE_DATA_SOURCES.md` rappelle ce besoin.
