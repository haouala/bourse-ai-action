# Lot 1 — Persistence et indicateurs

## Application
Les endpoints d'origine `/api/v1/market/assets`, `/api/v1/recommendations` et `/api/v1/portfolio/simulation` restent synthétiques (donnees fictives). Les nouveaux endpoints sont separes sous `/api/v1/history`.

## Installation
1. Copier les fichiers de ce correctif dans le depot `haouala/bourse-ai` en conservant les chemins, depuis la RACINE du depot (le dossier contenant `bourse-ai-engine/`).
2. Depuis `bourse-ai-engine/`, lancer `mvn clean test`.
3. Mode de demonstration en memoire, sans PostgreSQL : `mvn spring-boot:run`. Les donnees de l'API import sont perdues au redemarrage.
4. Pour stockage persistant : copier `.env.example` vers `.env`, modifier le mot de passe, `docker compose up -d`.
5. Exporter les variables a Spring Boot : `export DB_PASSWORD='votre_secret'` (Linux/macOS), puis `mvn spring-boot:run -Dspring-boot.run.profiles=postgres`.
6. Le port par defaut du depot est 8080. Pour 8081 : `export SERVER_PORT=8081`.

Ne pas committer `.env` ; ajouter `.env` au `.gitignore` du projet. Un mot de passe d'exemple n'est PAS un mot de passe de production.

## Essai avec un import manuel
POST `/api/v1/history/import` avec Content-Type: application/json :
```json
{"symbol":"NVDA","name":"NVIDIA","assetType":"STOCK","currency":"USD","timeframe":"1d","provider":"MANUAL_DEMO","bars":[{"openTime":"2026-10-01T20:00:00Z","open":100,"high":104,"low":99,"close":102,"volume":200000}]}
```
Importer au moins 35 bougies distinctes valides avant de consulter `GET /api/v1/history/analysis/NVDA?timeframe=1d`.
L'API donne un code 422 si l'historique est insuffisant. Le moteur ne fabrique pas de prix.

## Limites
- Les données réelles ne sont PAS encore connectées à Finnhub ou un autre fournisseur. Cet endpoint d'import reçoit des données JSON du client.
- La détection de doublons est adaptée au développement local ; dans une ingestion concurrente il faudra une stratégie UPSERT transactionnelle.
- Pas de gestion des splits/dividendes ; ne pas utiliser les indicateurs pour des décisions d'investissement sans normalisation et contrôle de qualité.
- API d'import non authentifiée : réserver à un environnement local, ajouter sécurité/authentification avant exposition publique.
- Stockage des indicateurs dans une table dédiée et backtesting restent les prochaines évolutions.
