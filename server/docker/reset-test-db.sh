#!/usr/bin/env bash
set -euo pipefail

target="${1:-test}"
db="shijiangtai_${target}"

docker compose up -d --wait postgres
docker compose exec -T postgres psql -U shijiangtai -d postgres -v ON_ERROR_STOP=1 \
  -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='${db}' AND pid <> pg_backend_pid();" \
  -c "DROP DATABASE IF EXISTS ${db};" \
  -c "CREATE DATABASE ${db} OWNER shijiangtai;"
