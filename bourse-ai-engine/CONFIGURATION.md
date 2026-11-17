# Configuration locale des accès

Les clés Twelve Data et EODHD sont déjà lues depuis les variables `TWELVE_DATA_API_KEY` et `EODHD_API_KEY` dans `application.yml`. Aucune valeur secrète n'est stockée dans ce dépôt public.

## Linux / Bash

Dans le dossier `bourse-ai-engine` :

```bash
cp .env.example .env
# Modifier .env localement : renseigner les deux clés et, si nécessaire, la base.
# Pour une valeur contenant des espaces, utiliser des guillemets simples.
set -a
. ./.env
set +a
mvn spring-boot:run
```

Spring Boot ne charge pas automatiquement `.env` : les commandes ci-dessus exportent les valeurs dans le terminal de lancement. Sans profil `postgres`, la configuration utilise H2 en mémoire.

Pour PostgreSQL, renseigner les paramètres de la base puis lancer :

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

## IntelliJ

Dans Run > Edit Configurations > Environment variables, ajouter `TWELVE_DATA_API_KEY` et `EODHD_API_KEY` avec leurs valeurs privées. Ajouter les paramètres DB si le profil `postgres` est utilisé. Ne pas partager une configuration de lancement contenant les clés.

## Quotas

Ce changement prépare les accès, mais n'ajoute pas de limiteur d'appels. Ne pas activer d'imports automatiques avant l'ajout d'un compteur persistant par fournisseur. Le plafond FMP prévu est de 240 appels/jour pour conserver une marge sous 250, à ajuster aux conditions du compte et aux appels effectués hors de l'application.

Le fichier `.env` est ignoré par Git. Ne pas utiliser `git add -f .env`. Pour une clé divulguée, la régénérer depuis le compte fournisseur.
