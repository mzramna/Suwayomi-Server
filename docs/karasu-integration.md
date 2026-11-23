# Integração Karasu (fork mzramna)

Este fork do Suwayomi-Server acrescenta o necessário para o app **Karasu** (Android) e a **WebUI do fork**
trabalharem juntos. O servidor é passivo: o Karasu é quem inicia toda sincronização.

## O que foi acrescentado

| Recurso | Onde |
| --- | --- |
| API REST `/api/v1/karasu/*` (contrato abaixo) | `manga/KarasuAPI.kt`, `manga/controller/KarasuController.kt`, `manga/impl/karasu/*` |
| Fontes mescladas (agregação + reserva) | `manga/impl/karasu/MergedSources.kt`, ganchos em `Manga.updateMangaAndChapters`, `ChapterForDownload.refreshChapterPageList` e `Page.getPageImage` |
| Tabelas novas (migração `M0069_KarasuSync`) | `MergedManga`, `MergedChapterRoute`, `KarasuDeletion`, `KarasuSetting` |
| Docker multi-arch (NFS opcional, H2/PostgreSQL) | `docker/Dockerfile`, `docker/entrypoint.sh`, `docker/docker-compose.example.yml` |
| CI: sincroniza o upstream, compila e publica no GHCR | `.github/workflows/upstream_sync_docker_release.yml` |
| Embutir a WebUI do fork no JAR | `-PwebuiZipUrl=... -PwebuiRevisionTag=...` em `:server:downloadWebUI` |

## Banco de dados

O Suwayomi já traz dois bancos, e o fork mantém ambos (não foi adicionado SQLite):

* **H2** (padrão): banco embutido em arquivo local — é o "banco local" do servidor.
* **PostgreSQL**: `DATABASE_TYPE=postgres` + `DATABASE_URL=jdbc:postgresql://host:5432/suwayomi`,
  `DATABASE_USERNAME`, `DATABASE_PASSWORD` (pool HikariCP já é o do servidor).

## Armazenamento (local ou NFS)

`NFS_TARGET=192.168.1.50:/volume1/mangas` faz o `entrypoint.sh` montar o export em `/data/downloads`
(`NFS_OPTIONS`, padrão `rw,noatime,tcp`); o container precisa de `cap_add: SYS_ADMIN`. Com `NFS_TARGET` vazio
usa o volume local. `NFS_FALLBACK_LOCAL=true` cai para o disco local em vez de encerrar se o mount falhar.
`GET /api/v1/karasu/health` informa se o armazenamento é `nfs` ou `local` (lido de `/proc/mounts`) e o espaço livre.

## Fontes mescladas — semântica

* Uma obra pode ter fontes extras (`MergedManga`). `priority` 0 é a mais alta.
* `updatesEnabled = true`: ao atualizar capítulos, os capítulos da fonte extra cujo **número** a fonte principal
  (e as de prioridade maior) não possui são adicionados à lista.
* `updatesEnabled = false`: **reserva** — nunca lista capítulos, só é consultada se a fonte principal falhar ao
  entregar as páginas (erro HTTP, Cloudflare...). A fonte que respondeu fica registrada em `MergedChapterRoute`
  e é usada para baixar as imagens daquele capítulo.
* A obra extra precisa existir como `url` válida naquela fonte; o servidor não a cria no banco como mangá próprio.

## Política de download e exclusão remota

`PUT /policy` guarda `SERVER_ONLY` ou `SERVER_AND_CLIENTS` (o Karasu lê e decide se baixa o que o servidor já tem).
`POST /deletions` grava "túmulos" por (fonte, url da obra, url do capítulo); cada Karasu consulta
`GET /deletions?afterId=` na sincronização e apaga a cópia local. Com `deleteFromServer=true` o arquivo do servidor
também é removido.

---

# Contrato REST — v1

Base: `{server}/api/v1/karasu` (respeita o prefixo de subpath do servidor). Autenticação: a mesma do Suwayomi
(Basic Auth / sessão — o Karasu usa header `Authorization: Basic ...` quando configurado; a WebUI usa o cookie/token que já usa).
Todos os corpos JSON usam camelCase. `sourceId` é SEMPRE string (Long de 64 bits não cabe em JS).
Erros: JSON `{"error":"mensagem"}` com status HTTP adequado.

## Saúde
`GET /health` → 200
```json
{ "status":"ok", "serverVersion":"v2.1.0", "apiVersion":1,
  "database":{"type":"H2"|"POSTGRESQL"},
  "storage":{"type":"local"|"nfs","path":"/data/downloads","freeBytes":123,"totalBytes":456} }
```

## Arquivo CBZ de capítulo baixado
`GET|HEAD /chapter/{chapterId}/archive` → 200 CBZ (Content-Length, Content-Disposition). 404 se o capítulo NÃO está baixado no servidor (nunca dispara download).
`GET /chapter/lookup?sourceId=&mangaUrl=&chapterUrl=` → 200 `{"chapterId":1,"mangaId":2,"downloaded":true,"sizeBytes":123}` | 404.
`GET /manga/lookup?sourceId=&url=` → 200 `{"mangaId":2,"title":"..","chapters":[{"chapterId":1,"url":"..","chapterNumber":1.0,"downloaded":true,"sizeBytes":123}]}` | 404.

`sizeBytes` = -1 significa desconhecido/não baixado (em `/manga/lookup` é sempre -1; use `/chapter/lookup` para o tamanho real).

## Backups
`POST /backups/upload?device=<nome>` multipart, campo `file` → 201 `BackupInfo`
`GET /backups` → 200 `[BackupInfo]` (mais novo primeiro)
`GET /backups/{id}/download` → 200 binário
`DELETE /backups/{id}` → 204
`BackupInfo = {"id":"<nome-do-arquivo>","name":"..","sizeBytes":123,"createdAt":1700000000000,"device":"Pixel 8"|null}`

## Fontes mescladas
`MergedSource = {"sourceId":"123","url":"/manga/x","priority":0,"updatesEnabled":true}` (priority 0 = mais alta; `updatesEnabled:false` = reserva).
`MergedGroup = {"mangaId":2,"primary":{"sourceId":"1","url":"/m"},"sources":[MergedSource]}`
`GET /merged` → `[MergedGroup]` (todas as obras que têm fontes extras)
`GET /manga/{mangaId}/merged` → `MergedGroup` (sources vazio se não houver)
`PUT /manga/{mangaId}/merged` body `{"sources":[MergedSource]}` → 200 `MergedGroup` (substitui tudo)
`PUT /merged` body `{"primary":{"sourceId","url"},"sources":[MergedSource]}` → 200 `MergedGroup` | 404 se a obra primária não existe no servidor (usado pelo Karasu, que não conhece mangaId do servidor)
Semântica no servidor: capítulos agregados e deduplicados por chapterNumber respeitando priority; se a fonte primária falhar ao buscar páginas/baixar, tenta as reservas.

## Política de download e exclusão remota
`GET /policy` → `{"downloadPolicy":"SERVER_ONLY"|"SERVER_AND_CLIENTS"}`; `PUT /policy` mesmo corpo.
`POST /deletions` body `{"chapterIds":[1,2],"deleteFromServer":true}` → 200 `{"queued":2}` (registra um "túmulo" por capítulo; deleteFromServer apaga também o arquivo no servidor)
`GET /deletions?afterId=0` → `[{"id":5,"sourceId":"1","mangaUrl":"/m","chapterUrl":"/c","chapterNumber":3.0,"requestedAt":1700000000000}]` ordenado por id. O Karasu guarda o último id visto e apaga localmente.

## Fora do contrato (GraphQL nativo do Suwayomi, já existe)
Categorias, progresso de leitura (isRead/lastPageRead), extensões instaladas: o Karasu usa `POST {server}/api/graphql` com queries escritas à mão (OkHttp + kotlinx.serialization), baseadas em `Suwayomi-Bunko/data/src/main/graphql/*.graphql`.
