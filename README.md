# Bourse AI — Architecture unifiée (release candidate)

**Un seul dépôt :** backend Java 21 / Spring Boot 3, base PostgreSQL 17 persistante, collecte de marché, actualités/podcasts et worker Python, laboratoire ML Python, application Android Kotlin/Jetpack Compose et application Java de démonstration hors ligne.

> **Statut :** développement expérimental. Pas de garantie de rendement, de connexion à un broker ni de système d'exécution automatique. Les APIs doivent rester sur l'ordinateur local tant qu'une authentification par utilisateur n'est pas en place.

## Cloner, configurer et démarrer

Pré-requis : **JDK 21, Maven 3.9+, Docker avec Compose**. L'application démarre sans clés payantes : les collectes sont désactivées par défaut.

```bash
git clone https://github.com/haouala/bourse-ai-action.git
cd bourse-ai-action
cp .env.example .env
# Ouvrir .env et renseigner DB_PASSWORD
bash start.sh
```

Sous Windows PowerShell :

```powershell
git clone https://github.com/haouala/bourse-ai-action.git
cd bourse-ai-action
Copy-Item .env.example .env
notepad .env
.\start.ps1
```

**Pendant la phase d'intégration**, ces instructions s'appliquent à la branche `integration/unified-bourse-ai`. Après la fusion validée, elles fonctionneront directement sur `main`. Pour l'essayer avant fusion : `git switch integration/unified-bourse-ai`.

Le script démarre PostgreSQL 17 sur `127.0.0.1:5434` avec un **volume Docker persistant**, puis Spring Boot avec le profil `postgres` sur `127.0.0.1:8081`. **Ne pas supprimer le volume** lorsque le mot de passe diffère : modifier la configuration ou restaurer les identifiants utilisés à la création.

Vérifier : `http://127.0.0.1:8081/actuator/health` (attendu : `UP`).

Pour arrêter : `Ctrl+C` pour Maven puis, si nécessaire, `docker compose --env-file .env -f bourse-ai-engine/docker-compose.yml stop`. La base reste intacte.

## Tester et développer

```bash
cd bourse-ai-engine
mvn -B -ntp clean verify
```

Tests worker sans API payante :
```bash
PYTHONPATH=bourse-ai-information/src python -m unittest discover -s bourse-ai-information/tests -v
```

Le batch ML Python exige Python 3.12 et ses dépendances `bourse-ai-ml/requirements.lock`; voir [son guide](bourse-ai-ml/README.md). Les tests GitHub Actions vérifient la base Java, PostgreSQL, les workers et les deux projets Android sur la branche d'intégration.

## Modules et applications

| Emplacement | Rôle |
| --- | --- |
| `bourse-ai-engine/` | APIs REST, qualité, backtest, données, scoring, prévisions, risque, registres de décisions, alertes |
| `bourse-ai-information/` | Worker Python : articles, transcriptions, analyse OpenAI, preuves |
| `bourse-ai-ml/` | Laboratoire ML Python avec jeu d'essai synthétique ; non promu en trading |
| `bourse-ai-android/` | **Application principale Android Kotlin/Compose**, mode démo et transport backend |
| `android-demo/` | **Autre APK Java de démonstration**, entièrement hors ligne |

Les nouvelles fonctionnalités Java ne sont pas toutes reliées aux écrans Kotlin existants. **Ne pas confondre une APK démo compilée avec une application opérationnelle sur données réelles.**

## Configuration et sécurité

- `.env` (privé, ignoré par Git) contient la base et, optionnellement, les clés de fournisseurs ; `.env.example` ne contient aucun secret.
- L'adresse d'écoute du serveur est **127.0.0.1 par défaut**. Ne mettre `SERVER_BIND_ADDRESS=0.0.0.0` qu'après sécurisation de l'API, y compris l'authentification et les droits par utilisateur.
- Les APIs de collecte externe et les traitements d'informations payants restent désactivés par défaut.
- Activer d'abord les sources pertinentes, vérifier leurs licences, quotas et clés. Les abonnements IA sont différents des clés de l'API OpenAI.
- Les simulations de trading ne passent **aucun** ordre chez eToro ou un autre courtier.

## Architecture et commentaires de calcul

L’[analyse technique V2](bourse-ai-engine/TECHNICAL_ANALYSIS.md) décrit les figures,
les points d’opportunité/risque, leurs plafonds et la comparaison ML avec/sans
variables enrichies. Les nouvelles données sont consultables dans Android 0.4.

Lire [ARCHITECTURE_UNIFIEE.md](ARCHITECTURE_UNIFIEE.md) pour les flux Java/Python/Android, la gestion des données, les versions Flyway **V1–V18**, les formules de volatilité, drawdown, score, backtest et prédictions. Les services de calcul portent des commentaires décrivant les hypothèses et limites.

Pour les détails des quotas et fournisseurs : [CONFIGURATION.md](bourse-ai-engine/CONFIGURATION.md).

**Avant une utilisation sur des comptes réels :** authentification et isolation des portefeuilles, contrôle des frais/FX et données réelles, validation hors échantillon, intégration Android, tests complets et analyse de sécurité.
