#!/bin/sh
# Starts the users API. The data file lives on a volume so it survives restarts; the first start seeds it.
set -e
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
: "${API_TOKEN:?Set API_TOKEN, the bearer token the Apply workflow sends (GITOPS_PROD_USERS_TOKEN)}"
DATA_FILE="${DATA_FILE:-/data/db.json}"
if [ ! -f "$DATA_FILE" ]; then
  mkdir -p "$(dirname "$DATA_FILE")"
  cp "$APP_DIR/seed.json" "$DATA_FILE"
fi
exec "$APP_DIR/node_modules/.bin/json-server" "$DATA_FILE" --host 0.0.0.0 --port "${PORT:-3000}" --middlewares "$APP_DIR/auth.js"
