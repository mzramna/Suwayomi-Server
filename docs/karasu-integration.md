# Integração Karasu (fork mzramna)

Este fork do Suwayomi-Server acrescenta o necessário para o app **Karasu** (Android) e a **WebUI do fork**
trabalharem juntos. O servidor é passivo: o Karasu é quem inicia toda sincronização.

## O que foi acrescentado

| Recurso | Onde |
| --- | --- |
| API REST `/api/v1/karasu/*` (contrato abaixo) | `manga/KarasuAPI.kt`, `manga/controller/KarasuController.kt`, `manga/impl/karasu/*` |
| Fontes mescladas (agregação + reserva) | `manga/impl/karasu/MergedSources.kt`, ganchos em `Manga.updateMangaAndChapters`, `ChapterForDownload.refreshChapterPageList` e `Page.getPageImage` |
| Tabelas novas (migrações `M0069_KarasuSync`, `M0070_KarasuOperations`) | `MergedManga`, `MergedChapterRoute`, `KarasuDeletion`, `KarasuSetting`, `KarasuOperation` |
| Shelf do Yatagarasu / KOReader (abaixo) | `manga/impl/karasu/KarasuShelf.kt`, `manga/controller/KarasuShelfController.kt` |
| Log de operações (contrato v2, abaixo) | `manga/impl/karasu/KarasuOperations.kt`, `KarasuRecorder.kt`, `KarasuRequestContext.kt` |
| Bateria de contrato HTTP (app + WebUI roteirizados) | `scripts/contract/run.sh`, `server/src/test/kotlin/suwayomi/tachidesk/contract`, `.github/workflows/ci_contract.yml` |
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
usa o volume local. Em NFSv3 o container não tem `rpc.statd`: use `NFS_OPTIONS=rw,noatime,tcp,nolock` (o NFSv4 não precisa). `NFS_FALLBACK_LOCAL=true` cai para o disco local em vez de encerrar se o mount falhar.
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

## Histórico
`PUT /history` body `{"entries":[{"chapterId":1,"lastReadAt":1700000000}]}` → 200 `{"updated":1}`.
`lastReadAt` em **segundos** (epoch); `0` remove o capítulo do histórico. É a única API que escreve esse valor (o GraphQL só o
grava como "agora" ao mudar `lastPageRead`). Ids inexistentes são ignorados. A leitura é pelo GraphQL (`chapter.lastReadAt`).

## Fora do contrato (GraphQL nativo do Suwayomi, já existe)
Categorias, progresso de leitura (isRead/lastPageRead), extensões instaladas: o Karasu usa `POST {server}/api/graphql` com queries escritas à mão (OkHttp + kotlinx.serialization), baseadas em `Suwayomi-Bunko/data/src/main/graphql/*.graphql`.

---

# Shelf do Yatagarasu (KOReader)

Rotas para o fluxo "app envia o CBZ para ler no e-reader; o plugin do KOReader devolve se foi lido". São o equivalente, sob
`/api/v1/karasu`, do contêiner `/api/shelf` do Yatagarasu, e valem a mesma autenticação e o mesmo `health` das demais rotas
(sem credencial o servidor responde 404 + `WWW-Authenticate`, como no resto). Quem usa: o app Karasu (envia, lista, apaga) e o
plugin do KOReader (lista, baixa, marca lido).

`chapterId` é o id **local do app**, sem relação com os capítulos do servidor. O CBZ guardado é exatamente o que o app enviou
(já reformatado para o aparelho); o servidor não reaproveita o CBZ baixado nele e **não marca `isRead` no Suwayomi** (o app
propaga a leitura pela sincronização de progresso). Essas operações **não entram no log de operações**.

| Rota | Resposta |
| --- | --- |
| `GET /health` | o mesmo da seção Saúde (não existe outro) |
| `GET /shelf` | 200 `{"entries":[{"chapterId":123,"chapterUrl":"/c","read":false}]}` ordenado por `chapterId` |
| `POST /shelf` | multipart; 201 com a entrada `{"chapterId":123,"chapterUrl":"/c","read":false}`; 400 se faltar parte, o JSON for inválido ou o arquivo vazio |
| `DELETE /shelf/{chapterId}` | 204, exista ou não; 400 se o id não for numérico |
| `GET\|HEAD /shelf/{chapterId}/file` | 200 CBZ (`Content-Type: application/vnd.comicbook+zip`, `Content-Length`, `Content-Disposition: attachment; filename="{chapterId}.cbz"`); 404 se não existe |
| `PUT /shelf/{chapterId}/read` | corpo `{"read":true\|false}`; 204; 404 se o capítulo não está na shelf; 400 se o corpo for inválido |

`POST /shelf`: duas partes. `metadata` (JSON; com ou sem `filename`, até 64 KB) e `file` (o CBZ,
`application/vnd.comicbook+zip`, sem limite de tamanho):
```json
{"chapterId":123,"chapterUrl":"/c","mangaTitle":"..","chapterName":"..","chapterNumber":1.5,"sourceId":"99"}
```
`chapterId` (inteiro >= 0, também aceito como texto só de dígitos) e `chapterUrl` (texto não vazio) são obrigatórios; os demais
são descritivos e opcionais (`sourceId` pode ser número ou texto). A rota é **idempotente por `chapterId`**: reenviar um id que
já existe **substitui o arquivo e zera `read`**.

- Armazenamento: `{dataRoot}/karasu-shelf/{chapterId}.cbz` + `{chapterId}.json` (metadados e `read`). O nome vem só de um número
  (`^[0-9]{1,18}$`); um id como `../x` é 400 e nunca vira caminho.
- Gravação: o arquivo vai para `*.part` e é renomeado ao final (como os backups); um upload interrompido não altera a versão
  anterior, e `*.part` nunca aparece na lista. Restos com mais de 24 h são apagados no próximo upload.
- Memória: o Javalin/Jetty grava em arquivo temporário (`java.io.tmpdir`) toda parte multipart acima de 1 byte, então um CBZ
  grande não fica no heap; ele é copiado uma vez para a shelf. Durante o upload é preciso espaço livre no `tmpdir` e na shelf.
- Concorrência: trocas de arquivo/registro são serializadas por um lock; a cópia do upload roda fora dele.

Exemplo (o que o plugin faz):
```sh
S=http://servidor:4567/api/v1/karasu; A='-u usuario:senha'
curl $A $S/health
curl $A -F 'metadata={"chapterId":123,"chapterUrl":"/c","mangaTitle":"M","chapterName":"N","chapterNumber":1,"sourceId":"9"};type=application/json' \
        -F 'file=@cap.cbz;type=application/vnd.comicbook+zip' $S/shelf
curl $A $S/shelf
curl $A -o baixado.cbz $S/shelf/123/file && cmp cap.cbz baixado.cbz
curl $A -X PUT -d '{"read":true}' $S/shelf/123/read
curl $A -X DELETE $S/shelf/123
```

---

# Log de operações (contrato v2)

O servidor mantém um log de "quem mudou o quê e quando" para que o app resolva **conflitos reais** (a mesma chave mudou nos
dois lados desde a última sincronização) pelo horário da ação. É registro, não comando: nada é reaplicado a partir dele.
Tabela `KarasuOperation` (`seq` autoincremento atribuído pelo servidor, `op_id` único, `device_id`, `ts` em ms UTC, `entity`,
chave, `action`, valor JSON, `received_at`); retenção de 90 dias (poda na subida do servidor, por `received_at`).

* `POST /operations` `{"operations":[Operation]}` → `{"accepted":n,"duplicates":m}`; idempotente por `id`; valida `entity`/`action`
  (400 sem gravar nada); máximo de 10000 por requisição. Guarda `deviceId` e `ts` como o app mandou.
* `GET /operations?afterSeq=0&entity=&limit=1000` → `{"operations":[...+seq],"lastSeq":n,"serverTime":ms}` ordenado por `seq`;
  `limit` máx. 5000 (acima disso é reduzido); `lastSeq` = `seq` da última operação devolvida, ou o próprio `afterSeq` se não há nada novo.
* Entidades e chaves: ver a tabela do contrato (`category:<id>`, `link:<sourceId>|<mangaUrl>|<categoryId>`,
  `progress:`/`history:`/`deletion:<sourceId>|<mangaUrl>|<chapterUrl>`, `merged:<sourceId>|<mangaUrl>`, `policy`, `backup:<id>`).
  `sourceId` é a fonte da obra como string; `merged:` usa a obra primária.

## Quem registra: o header `X-Karasu-Device`

* **Com** `X-Karasu-Device` (app Karasu, REST e GraphQL): o servidor **não** grava operação automática para as mudanças daquela
  requisição. O app publica o próprio log (`POST /operations`) com o `ts` real da ação do usuário; se o servidor gravasse também,
  o `ts` seria o da sincronização (horas depois) e quebraria a resolução por horário.
* **Sem** o header (WebUI, scripts): o servidor grava sozinho, com `deviceId = "web"` e `ts` = agora, **só mudanças efetivas**
  (valor diferente do anterior; no-op não registra). Pontos instrumentados: criar/renomear/apagar categoria
  (`createCategory`/`updateCategory(s)`/`deleteCategory` e os equivalentes REST), vínculo obra↔categoria
  (`updateManga(s)Categories` e REST), `isRead`/`lastPageRead` de capítulo (`updateChapter(s)` e REST), e as rotas
  `PUT /history`, `PUT /merged`, `PUT /manga/{id}/merged`, `PUT /policy`, `POST /deletions`, upload/exclusão de backup.
  Apagar uma categoria não gera `REMOVE` dos vínculos dela (o app vê pelo `REMOVE` da categoria). `bookmark` não é progresso.

Como o header chega às mutações: um handler `before` do Javalin lê o header (e um `after` limpa); o contexto GraphQL carrega
`Attribute.KarasuDevice` e o wrapper de `@RequireAuth` publica o valor para o resolver síncrono que está executando
(`KarasuRequestContext`, um `ThreadLocal` só dentro desse trecho; código que troca de thread deve ler o dispositivo antes e passar
`deviceId` explícito a `KarasuOperations.record`).

## Testes de contrato HTTP

`scripts/contract/run.sh h2|postgres` (precisa de `SERVER_JAR`) sobe o JAR, semeia obras/capítulos (`scripts/contract/seed.py`) e
roda `suwayomi.tachidesk.contract.*` (`-PcontractUrl=http://127.0.0.1:PORT -PcontractUser= -PcontractPass=`; sem `contractUrl`
os testes são ignorados). Dois clientes roteirizados — "app" (com `X-Karasu-Device`) e "web" (sem) — fazem as chamadas
REST e as consultas/mutações GraphQL do app e conferem o estado e o log (entity/key/action/value/deviceId/ts, sem duplicar).
No CI: `.github/workflows/ci_contract.yml` (H2 e PostgreSQL).
