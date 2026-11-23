#!/usr/bin/env bash
# Entrypoint do fork mzramna/Suwayomi-Server.
#
#  - NFS_TARGET definido  -> monta o export em $DOWNLOADS_PATH (precisa de cap SYS_ADMIN, ou monte
#                            o NFS no host / use um volume NFS do Docker e deixe NFS_TARGET vazio).
#  - NFS_TARGET vazio     -> usa o volume/pasta local em $DOWNLOADS_PATH.
#  - DATABASE_TYPE        -> h2 (banco local embutido, padrão) ou postgres/postgresql.
set -euo pipefail

log() { echo "[entrypoint] $*"; }

DOWNLOADS_PATH="${DOWNLOADS_PATH:-/data/downloads}"
BACKUPS_PATH="${BACKUPS_PATH:-/data/backups}"
CONFIG_PATH="${CONFIG_PATH:-/data/config}"
PUID="${PUID:-1000}"
PGID="${PGID:-1000}"

mkdir -p "$DOWNLOADS_PATH" "$BACKUPS_PATH" "$CONFIG_PATH"

# ---------- armazenamento: NFS ou local ----------
if [[ -n "${NFS_TARGET:-}" ]]; then
  log "NFS_TARGET=$NFS_TARGET -> montando em $DOWNLOADS_PATH (opções: ${NFS_OPTIONS})"
  if mountpoint -q "$DOWNLOADS_PATH"; then
    log "$DOWNLOADS_PATH já é um ponto de montagem, mantendo."
  elif ! mount -t nfs -o "${NFS_OPTIONS}" "$NFS_TARGET" "$DOWNLOADS_PATH"; then
    if [[ "${NFS_FALLBACK_LOCAL:-false}" == "true" ]]; then
      log "AVISO: falha ao montar o NFS; seguindo com armazenamento LOCAL (NFS_FALLBACK_LOCAL=true)."
    else
      log "ERRO: não foi possível montar $NFS_TARGET. O container precisa de --cap-add SYS_ADMIN (ou privileged)."
      log "      Defina NFS_FALLBACK_LOCAL=true para cair para o disco local em vez de encerrar."
      exit 1
    fi
  fi
else
  log "NFS_TARGET vazio -> armazenamento local em $DOWNLOADS_PATH"
fi

# ---------- banco de dados ----------
JAVA_PROPS=()
add_prop() { JAVA_PROPS+=("-Dsuwayomi.tachidesk.config.server.$1=$2"); }

db_type="$(echo "${DATABASE_TYPE:-h2}" | tr '[:upper:]' '[:lower:]')"
case "$db_type" in
  postgres|postgresql|pg)
    : "${DATABASE_URL:?DATABASE_URL é obrigatório com DATABASE_TYPE=postgres (ex.: jdbc:postgresql://postgres:5432/suwayomi)}"
    url="${DATABASE_URL#jdbc:}"     # o servidor adiciona o prefixo jdbc: sozinho
    add_prop databaseType POSTGRESQL
    add_prop databaseUrl "$url"
    add_prop databaseUsername "${DATABASE_USERNAME:-}"
    add_prop databasePassword "${DATABASE_PASSWORD:-}"
    [[ -n "${DATABASE_SCHEMA:-}" ]] && add_prop databaseSchema "$DATABASE_SCHEMA"
    # espera o Postgres aceitar conexões TCP
    hostport="${url#*://}"; hostport="${hostport%%/*}"; hostport="${hostport##*@}"
    host="${hostport%%:*}"; port="${hostport##*:}"; [[ "$port" == "$host" ]] && port=5432
    log "Aguardando PostgreSQL em $host:$port ..."
    for _ in $(seq 1 60); do
      (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null && break
      sleep 2
    done
    ;;
  h2|local|"")
    add_prop databaseType H2
    ;;
  *)
    log "ERRO: DATABASE_TYPE='$DATABASE_TYPE' inválido. Use h2 ou postgres."
    exit 1
    ;;
esac

add_prop rootDir "$CONFIG_PATH"
add_prop downloadsPath "$DOWNLOADS_PATH"
add_prop backupPath "$BACKUPS_PATH"
add_prop ip "${BIND_IP:-0.0.0.0}"
add_prop port "${PORT:-4567}"
# qualquer outra opção: SUWAYOMI_OPT_<nome>=valor  (ex.: SUWAYOMI_OPT_downloadAsCbz=true)
while IFS='=' read -r name value; do
  [[ "$name" == SUWAYOMI_OPT_* ]] && add_prop "${name#SUWAYOMI_OPT_}" "$value"
done < <(env)

# ---------- permissões e execução sem root ----------
if [[ "$(id -u)" == "0" ]]; then
  groupmod -o -g "$PGID" suwayomi 2>/dev/null || true
  usermod -o -u "$PUID" suwayomi 2>/dev/null || true
  chown -R "$PUID:$PGID" "$CONFIG_PATH" "$BACKUPS_PATH" 2>/dev/null || true
  # num NFS com root_squash o chown pode falhar; só tentamos a pasta raiz
  chown "$PUID:$PGID" "$DOWNLOADS_PATH" 2>/dev/null || true
  RUN=(setpriv --reuid="$PUID" --regid="$PGID" --init-groups)
else
  RUN=()
fi

log "Iniciando Suwayomi-Server (db=$db_type)"
# shellcheck disable=SC2086
exec "${RUN[@]}" java ${JAVA_OPTS:-} "${JAVA_PROPS[@]}" -jar /opt/suwayomi/Suwayomi-Server.jar
