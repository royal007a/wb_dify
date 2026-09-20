#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
NETWORK=hify-network

docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK" >/dev/null
docker volume inspect hify-postgres-data >/dev/null 2>&1 || docker volume create hify-postgres-data >/dev/null

for container in hify-web hify-app hify-postgres; do
  if docker container inspect "$container" >/dev/null 2>&1; then
    docker rm -f "$container" >/dev/null
  fi
done

(cd "$ROOT_DIR/backend" && mvn -q package -DskipTests)
docker build -t hify-backend:dev -f "$ROOT_DIR/backend/Dockerfile.runtime" "$ROOT_DIR/backend"
docker build -t hify-frontend:dev -f "$ROOT_DIR/frontend/Dockerfile" "$ROOT_DIR"

docker run -d --name hify-postgres --network "$NETWORK" \
  -e POSTGRES_DB=hify -e POSTGRES_USER=hify -e POSTGRES_PASSWORD=hify_local_only \
  -v hify-postgres-data:/var/lib/postgresql/data \
  pgvector/pgvector:pg16 >/dev/null

until docker exec hify-postgres pg_isready -U hify -d hify >/dev/null 2>&1; do sleep 1; done

docker run -d --name hify-app --network "$NETWORK" \
  -e HIFY_DB_URL=jdbc:postgresql://hify-postgres:5432/hify \
  -e HIFY_DB_USERNAME=hify -e HIFY_DB_PASSWORD=hify_local_only \
  hify-backend:dev >/dev/null

until docker exec hify-app wget -qO- http://localhost:8080/actuator/health >/dev/null 2>&1; do
  if [ "$(docker inspect -f '{{.State.Running}}' hify-app)" != "true" ]; then
    docker logs hify-app
    exit 1
  fi
  sleep 1
done

docker run -d --name hify-web --network "$NETWORK" -p 8088:80 hify-frontend:dev >/dev/null
echo "Hify is ready at http://localhost:8088"
