# Imagem do fork mzramna/Suwayomi-Server (amd64 + arm64).
# O JAR é compilado fora (./gradlew :server:shadowJar) e copiado para docker/app.jar pelo CI.
FROM eclipse-temurin:21-jre-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends nfs-common util-linux tini curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# usuário padrão; PUID/PGID podem ser trocados em runtime (útil para permissões do NFS)
RUN (userdel -r ubuntu 2>/dev/null || true) \
    && groupadd -g 1000 suwayomi && useradd -u 1000 -g 1000 -m -d /home/suwayomi -s /bin/bash suwayomi \
    && mkdir -p /data/config /data/downloads /data/backups \
    && chown -R suwayomi:suwayomi /data /home/suwayomi

COPY docker/app.jar /opt/suwayomi/Suwayomi-Server.jar
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENV DATABASE_TYPE=h2 \
    DOWNLOADS_PATH=/data/downloads \
    BACKUPS_PATH=/data/backups \
    CONFIG_PATH=/data/config \
    NFS_OPTIONS=rw,noatime,tcp \
    PUID=1000 \
    PGID=1000

EXPOSE 4567
VOLUME ["/data/config", "/data/backups"]

HEALTHCHECK --interval=60s --timeout=10s --start-period=120s --retries=3 \
    CMD curl -sS --max-time 8 -o /dev/null "http://127.0.0.1:4567/api/v1/settings/about" || exit 1

ENTRYPOINT ["/usr/bin/tini", "--", "/entrypoint.sh"]
