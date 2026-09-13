#!/usr/bin/env sh
set -eu
for container in hify-web hify-app hify-postgres; do
  if docker container inspect "$container" >/dev/null 2>&1; then
    docker rm -f "$container" >/dev/null
  fi
done
echo "Hify containers stopped. PostgreSQL data remains in hify-postgres-data."
