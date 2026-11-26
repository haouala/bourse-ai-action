#!/usr/bin/env bash
# Launch Bourse AI locally with persistent PostgreSQL and disabled paid collectors.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
if [[ ! -f .env ]]; then echo "Copy .env.example to .env, set DB_PASSWORD and run again." >&2; exit 1; fi
# The local .env is a trusted, user-edited shell-compatible file, never loaded from the network.
set -a
# shellcheck disable=SC1091
source "$ROOT/.env"
set +a
if [[ -z "${DB_PASSWORD:-}" || "$DB_PASSWORD" == "CHOOSE_A_STRONG_PASSWORD" ]]; then
  echo "Choose a real DB_PASSWORD in .env; the example placeholder is not allowed." >&2
  exit 1
fi
command -v docker >/dev/null || { echo "Docker is required." >&2; exit 1; }
command -v mvn >/dev/null || { echo "Maven 3.9+ is required." >&2; exit 1; }
command -v java >/dev/null || { echo "JDK 21 is required." >&2; exit 1; }
if [[ "${START_POSTGRES:-true}" != "false" ]]; then
  docker compose --env-file "$ROOT/.env" -f "$ROOT/bourse-ai-engine/docker-compose.yml" up -d --wait postgres
fi
echo "PostgreSQL started (persistent volume). Backend: http://127.0.0.1:${SERVER_PORT:-8081}"
cd "$ROOT/bourse-ai-engine"
exec mvn -B -ntp -Dspring-boot.run.profiles=postgres spring-boot:run
