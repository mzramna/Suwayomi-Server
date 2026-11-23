#!/usr/bin/env bash
# Contract battery of the Karasu pipeline: starts the server (a built jar) on H2 or PostgreSQL, seeds it,
# and runs the scripted "app" and "web" clients (server/src/test/.../contract) against it over HTTP.
#
#   SERVER_JAR=server/build/Suwayomi-Server-vX.jar scripts/contract/run.sh h2
#   SERVER_JAR=... PGHOST=localhost PGUSER=postgres PGPASSWORD=postgres scripts/contract/run.sh postgres
#
# The server is started once to create its schema, stopped, seeded (seed.py: works /live/m01..m30 on the
# server's local source, chapters c1 and c2 downloaded) and started again with Basic Auth. The tests are the
# ones of `suwayomi.tachidesk.contract`, enabled by -PcontractUrl (without it they are skipped).
# CONTRACT_AUTH=none runs the server without authentication (empty user/password).
set -euo pipefail

DB="${1:-h2}"
: "${SERVER_JAR:?SERVER_JAR must point at the Suwayomi-Server jar}"
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
ROOT="$(mktemp -d)"
PORT="${CONTRACT_PORT:-4592}"
if [[ "${CONTRACT_AUTH:-basic}" == none ]]; then USER_NAME=""; USER_PASS=""; else USER_NAME=karasu; USER_PASS=s3cret; fi
SERVER_PID=""
P=suwayomi.tachidesk.config.server

DB_ARGS=()
case "$DB" in
  h2) ;;
  postgres)
    export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}" PGUSER="${PGUSER:-postgres}"
    DBNAME="karasu_contract_$$"
    psql -d postgres -qc "DROP DATABASE IF EXISTS $DBNAME" -c "CREATE DATABASE $DBNAME"
    DB_ARGS=(-D$P.databaseType=POSTGRESQL "-D$P.databaseUrl=postgresql://$PGHOST:$PGPORT/$DBNAME"
             "-D$P.databaseUsername=$PGUSER" "-D$P.databasePassword=${PGPASSWORD:-}")
    ;;
  *) echo "usage: $0 h2|postgres" >&2; exit 2 ;;
esac

stop_server() {
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" 2>/dev/null || true
    # the H2 database is only flushed when the JVM shuts down cleanly: wait for it, then insist
    for _ in $(seq 1 30); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
    kill -9 "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  SERVER_PID=""
}
cleanup() {
  stop_server
  if [[ "$DB" == postgres ]]; then psql -d postgres -qc "DROP DATABASE IF EXISTS $DBNAME" || true; fi
  if [[ -n "${KEEP_LOG:-}" && -f "$ROOT/server.log" ]]; then cp "$ROOT/server.log" "$KEEP_LOG" || true; fi
  rm -rf "$ROOT"
}
trap cleanup EXIT

start_server() {
  local auth=(-D$P.authMode=NONE)
  [[ -n "$USER_NAME" ]] && auth=(-D$P.authMode=BASIC_AUTH "-D$P.authUsername=$USER_NAME" "-D$P.authPassword=$USER_PASS")
  java "-D$P.rootDir=$ROOT" "-D$P.port=$PORT" "-D$P.ip=127.0.0.1" "-D$P.webUIEnabled=false" \
       "-D$P.initialOpenInBrowserEnabled=false" "-D$P.systemTrayEnabled=false" "-D$P.downloadAsCbz=true" \
       "${auth[@]}" "${DB_ARGS[@]}" -Xmx1g -jar "$SERVER_JAR" > "$ROOT/server.log" 2>&1 &
  SERVER_PID=$!
  local curl_auth=()
  [[ -n "$USER_NAME" ]] && curl_auth=(-u "$USER_NAME:$USER_PASS")
  for _ in $(seq 1 120); do
    curl -fsS "${curl_auth[@]}" "http://127.0.0.1:$PORT/api/v1/karasu/health" >/dev/null 2>&1 && return 0
    kill -0 "$SERVER_PID" 2>/dev/null || { echo "server died:"; tail -40 "$ROOT/server.log"; exit 1; }
    sleep 2
  done
  echo "server did not come up:"; tail -40 "$ROOT/server.log"; exit 1
}

if curl -s -o /dev/null "http://127.0.0.1:$PORT/"; then echo "port $PORT is already taken (CONTRACT_PORT)"; exit 1; fi

echo "== [$DB] first start: creating the schema"
start_server
stop_server

echo "== [$DB] seeding"
python3 "$HERE/seed.py" "$ROOT" "$ROOT/seed.sql" "${CONTRACT_WORKS:-40}"
if [[ "$DB" == postgres ]]; then
  PGOPTIONS='-c search_path=suwayomi' psql -d "$DBNAME" -q -v ON_ERROR_STOP=1 -f "$ROOT/seed.sql"
else
  java -cp "$SERVER_JAR" org.h2.tools.RunScript -url "jdbc:h2:$ROOT/database" -script "$ROOT/seed.sql"
fi

echo "== [$DB] starting the server for the tests"
start_server
curl -fsS ${USER_NAME:+-u "$USER_NAME:$USER_PASS"} "http://127.0.0.1:$PORT/api/v1/karasu/health"; echo

cd "$REPO"
# shellcheck disable=SC2086
./gradlew :server:test --tests 'suwayomi.tachidesk.contract.*' \
  "-PcontractUrl=http://127.0.0.1:$PORT" "-PcontractUser=$USER_NAME" "-PcontractPass=$USER_PASS" \
  -I .github/runner-files/exclude-cef-test.init.gradle ${GRADLE_ARGS:-} --console=plain
