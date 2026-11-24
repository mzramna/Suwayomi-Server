package suwayomi.tachidesk.contract

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every Karasu REST route, end to end over HTTP, as the Karasu app ("app": sends X-Karasu-Device) and as the
 * WebUI ("web": does not) would call it. For each route that changes something the test checks the state AND the
 * operation log: the server logs by itself only what "web" does, as device `web` with the time of the change;
 * what the "app" does is logged by the app (POST /operations) with the real time of the action.
 */
@EnabledIfSystemProperty(named = "contractUrl", matches = ".+")
class ContractRestTest {
    private val web = ContractClient(null)
    private val app = ContractClient("contract-app-" + UUID.randomUUID().toString().take(8))

    private fun mangaUrl(n: Int) = "/live/m%02d".format(n)

    private fun now() = System.currentTimeMillis()

    // ---- health and access --------------------------------------------------------------------------------

    @Test
    fun `health tells status, versions, database and storage`() {
        for (client in listOf(web, app)) {
            val r = client.get("health")
            assertEquals(200, r.status)
            assertEquals("ok", r.obj["status"]!!.jsonPrimitive.content)
            assertTrue(
                r.obj["serverVersion"]!!
                    .jsonPrimitive.content
                    .isNotBlank(),
            )
            assertTrue(r.obj["apiVersion"]!!.jsonPrimitive.int >= 1)
            assertTrue(
                r.obj["database"]!!
                    .jsonObject["type"]!!
                    .jsonPrimitive.content in setOf("H2", "POSTGRESQL"),
            )
            val storage = r.obj["storage"]!!.jsonObject
            assertTrue(storage["type"]!!.jsonPrimitive.content in setOf("local", "nfs"))
            assertTrue(storage["totalBytes"]!!.jsonPrimitive.long >= storage["freeBytes"]!!.jsonPrimitive.long)
        }
    }

    @Test
    fun `a wrong password is refused and no operation of it reaches the log`() {
        assumeTrue(ContractConfig.user.isNotEmpty(), "the server runs without authentication")
        val intruder = ContractClient("intruder", ContractConfig.user, "wrong-" + ContractConfig.pass)
        // the server answers an unauthenticated request with 404 + WWW-Authenticate (upstream behaviour the app already tolerates)
        val refused = setOf(401, 403, 404)
        assertTrue(intruder.get("health").status in refused)
        assertEquals("Basic", intruder.get("health").header("WWW-Authenticate"))
        val seq = app.headSeq()
        val body =
            buildJsonObject {
                put("operations", buildJsonArray { add(appOperation("intruder", now(), "POLICY", "policy", "SET")) })
            }
        assertTrue(intruder.post("operations", body).status in refused)
        assertTrue(intruder.request("GET", "/api/v1/karasu/operations", withAuth = false).status in refused)
        assertTrue(app.operationsAfter(seq).isEmpty())
    }

    // ---- archives and lookups -----------------------------------------------------------------------------

    @Test
    fun `a downloaded chapter is found by source and urls and streamed as a CBZ, a missing one is a 404`() {
        val manga = mangaUrl(30)
        val found = app.get("chapter/lookup", "sourceId" to ContractConfig.SOURCE_ID, "mangaUrl" to manga, "chapterUrl" to "$manga/c2")
        assertEquals(200, found.status)
        assertEquals(
            true,
            found.obj["downloaded"]!!
                .jsonPrimitive.content
                .toBoolean(),
        )
        val chapterId = found.obj["chapterId"]!!.jsonPrimitive.int
        val size = found.obj["sizeBytes"]!!.jsonPrimitive.long
        assertTrue(size > 0)

        val head = app.head("chapter/$chapterId/archive")
        assertEquals(200, head.status)
        assertEquals(size.toString(), head.header("Content-Length"))
        assertEquals(0, head.body.size)
        assertNotNull(head.header("Content-Disposition"))

        val get = app.get("chapter/$chapterId/archive")
        assertEquals(200, get.status)
        assertEquals(size, get.body.size.toLong())
        val entries =
            ZipInputStream(ByteArrayInputStream(get.body)).use { zip ->
                generateSequence { zip.nextEntry }.map { it.name }.toList()
            }
        assertEquals(3, entries.size)

        val missing = app.get("chapter/lookup", "sourceId" to ContractConfig.SOURCE_ID, "mangaUrl" to manga, "chapterUrl" to "$manga/c3")
        assertEquals(200, missing.status)
        assertEquals(
            false,
            missing.obj["downloaded"]!!
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals(-1L, missing.obj["sizeBytes"]!!.jsonPrimitive.long)
        // never starts a download: still a 404, with the JSON error shape
        val notThere = app.get("chapter/${missing.obj["chapterId"]!!.jsonPrimitive.int}/archive")
        assertEquals(404, notThere.status)
        assertTrue(
            notThere.obj["error"]!!
                .jsonPrimitive.content
                .isNotBlank(),
        )

        assertEquals(404, app.get("chapter/lookup", "sourceId" to "0", "mangaUrl" to manga, "chapterUrl" to "/nope").status)
        assertEquals(400, app.get("chapter/lookup", "sourceId" to "0").status)
        assertEquals(400, app.get("chapter/abc/archive").status)
        assertEquals(404, app.get("chapter/999999999/archive").status)
    }

    @Test
    fun `manga lookup lists the chapters with their downloaded flag`() {
        val manga = mangaUrl(30)
        val r = app.get("manga/lookup", "sourceId" to ContractConfig.SOURCE_ID, "url" to manga)
        assertEquals(200, r.status)
        assertEquals("Live M30", r.obj["title"]!!.jsonPrimitive.content)
        val chapters = r.obj["chapters"]!!.jsonArray.map { it.jsonObject }
        assertEquals(4, chapters.size)
        assertEquals(setOf("$manga/c1", "$manga/c2", "$manga/c3", "$manga/c4"), chapters.map { it["url"]!!.jsonPrimitive.content }.toSet())
        assertEquals(
            listOf(true, true, false, false),
            chapters
                .sortedBy {
                    it["url"]!!.jsonPrimitive.content
                }.map { it["downloaded"]!!.jsonPrimitive.content.toBoolean() },
        )
        assertTrue(chapters.all { it["sizeBytes"]!!.jsonPrimitive.long == -1L })
        assertEquals(404, app.get("manga/lookup", "sourceId" to "0", "url" to "/nope").status)
        assertEquals(400, app.get("manga/lookup", "sourceId" to "0").status)
    }

    // ---- backups ------------------------------------------------------------------------------------------

    @Test
    fun `backups go up, are listed newest first, come back identical and can be deleted`() {
        val payload = ByteArray(7_000) { (it * 31).toByte() }
        val seq = app.headSeq()
        val sent = app.upload("contract.tachibk", payload, "Contract Pixel")
        assertEquals(201, sent.status)
        val id = sent.obj["id"]!!.jsonPrimitive.content
        assertEquals("contract.tachibk", sent.obj["name"]!!.jsonPrimitive.content)
        assertEquals(payload.size.toLong(), sent.obj["sizeBytes"]!!.jsonPrimitive.long)
        assertEquals("Contract Pixel", sent.obj["device"]!!.jsonPrimitive.content)
        assertTrue(sent.obj["createdAt"]!!.jsonPrimitive.long > 0)

        val list = app.get("backups")
        assertEquals(200, list.status)
        assertEquals(
            id,
            list.json.jsonArray
                .first()
                .jsonObject["id"]!!
                .jsonPrimitive.content,
        )

        val download = app.get("backups/${java.net.URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")}/download")
        assertEquals(200, download.status)
        assertTrue(payload.contentEquals(download.body))

        // the app identified itself: the server logged nothing, the app publishes its own operations
        assertTrue(app.operationsAfter(seq).isEmpty())
        val ts = now() - 3 * 3600_000
        val published =
            app.post(
                "operations",
                buildJsonObject {
                    put(
                        "operations",
                        buildJsonArray {
                            add(
                                appOperation(
                                    app.device!!,
                                    ts,
                                    "BACKUP",
                                    "backup:$id",
                                    "SET",
                                    buildJsonObject {
                                        put("device", "Contract Pixel")
                                        put("sizeBytes", payload.size)
                                    },
                                ),
                            )
                        },
                    )
                },
            )
        assertEquals(1, published.obj["accepted"]!!.jsonPrimitive.int)
        val logged = app.operationsAfter(seq, "BACKUP").single()
        assertEquals(ts, logged.ts)
        assertEquals(app.device, logged.deviceId)

        assertEquals(204, app.delete("backups/${java.net.URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")}").status)
        assertEquals(404, app.delete("backups/${java.net.URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")}").status)
        assertFalse(
            app
                .get("backups")
                .json.jsonArray
                .any { it.jsonObject["id"]!!.jsonPrimitive.content == id },
        )
        assertEquals(1, app.operationsAfter(seq).size, "the app's delete was not logged by the server either")
    }

    @Test
    fun `the web uploading and deleting a backup is logged by the server as web`() {
        bystander()
        val payload = ByteArray(1_234) { it.toByte() }
        val seq = web.headSeq()
        val t0 = now()
        val sent = web.upload("web.tachibk", payload, null)
        assertEquals(201, sent.status)
        assertEquals(JsonNull, sent.obj["device"])
        val id = sent.obj["id"]!!.jsonPrimitive.content
        val encoded =
            java.net.URLEncoder
                .encode(id, Charsets.UTF_8)
                .replace("+", "%20")
        assertEquals(204, web.delete("backups/$encoded").status)
        val t1 = now()

        val ops = web.operationsAfter(seq, "BACKUP")
        // the REMOVE replaced the SET and stays as a tombstone for the other device
        assertEquals(listOf("BACKUP REMOVE backup:$id null"), ops.map { it.summary })
        assertTrue(ops.all { it.deviceId == "web" && it.ts in (t0 - 2000)..(t1 + 2000) })
    }

    // ---- merged sources -----------------------------------------------------------------------------------

    private fun mergedBody(
        manga: String,
        vararg sources: Triple<String, String, Boolean>,
    ) = buildJsonObject {
        put(
            "primary",
            buildJsonObject {
                put("sourceId", ContractConfig.SOURCE_ID)
                put("url", manga)
            },
        )
        put(
            "sources",
            buildJsonArray {
                sources.forEachIndexed { index, (id, url, updates) ->
                    add(
                        buildJsonObject {
                            put("sourceId", id)
                            put("url", url)
                            put("priority", index)
                            put("updatesEnabled", updates)
                        },
                    )
                }
            },
        )
    }

    @Test
    fun `merged sources set by the web are read back everywhere and logged as the whole list`() {
        val manga = mangaUrl(5)
        web.put("merged", mergedBody(manga)) // start empty
        val seq = web.headSeq()
        val t0 = now()

        val put = web.put("merged", mergedBody(manga, Triple("7001", "/a", true), Triple("7002", "/b", false)))
        assertEquals(200, put.status)
        val mangaId = put.obj["mangaId"]!!.jsonPrimitive.int
        assertEquals(
            ContractConfig.SOURCE_ID,
            put.obj["primary"]!!
                .jsonObject["sourceId"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            manga,
            put.obj["primary"]!!
                .jsonObject["url"]!!
                .jsonPrimitive.content,
        )
        assertEquals(listOf("7001", "7002"), put.obj["sources"]!!.jsonArray.map { it.jsonObject["sourceId"]!!.jsonPrimitive.content })

        assertEquals(
            2,
            app
                .get("manga/$mangaId/merged")
                .obj["sources"]!!
                .jsonArray.size,
        )
        assertTrue(
            app
                .get("merged")
                .json.jsonArray
                .any { it.jsonObject["mangaId"]!!.jsonPrimitive.int == mangaId },
        )

        // the same list again changes nothing: nothing is logged
        web.put("merged", mergedBody(manga, Triple("7001", "/a", true), Triple("7002", "/b", false)))
        // by manga id, with one source less
        val byId =
            web.put(
                "manga/$mangaId/merged",
                buildJsonObject {
                    put(
                        "sources",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("sourceId", "7001")
                                    put("url", "/a")
                                },
                            )
                        },
                    )
                },
            )
        assertEquals(200, byId.status)
        // unknown primary / unknown manga id are 404
        assertEquals(404, web.put("merged", mergedBody("/never/seen", Triple("7001", "/a", true))).status)
        assertEquals(404, web.put("manga/999999999/merged", buildJsonObject { put("sources", JsonArray(emptyList())) }).status)
        assertEquals(404, web.get("manga/999999999/merged").status)
        web.put("merged", mergedBody(manga))
        val t1 = now()

        val ops = web.operationsAfter(seq, "MERGED")
        val key = "merged:${ContractConfig.SOURCE_ID}|$manga"
        // three changes of the same target: only the last one is kept
        assertEquals(1, ops.size, ops.joinToString("\n") { it.summary })
        assertTrue(ops.all { it.key == key && it.action == "SET" && it.deviceId == "web" && it.ts in (t0 - 2000)..(t1 + 2000) })
        assertEquals("""{"sources":[]}""", ops[0].value.toString())
        assertTrue(
            web
                .get("manga/$mangaId/merged")
                .obj["sources"]!!
                .jsonArray
                .isEmpty(),
        )
    }

    @Test
    fun `merged sources set by the app are not logged by the server`() {
        val manga = mangaUrl(6)
        app.put("merged", mergedBody(manga))
        val seq = app.headSeq()
        val put = app.put("merged", mergedBody(manga, Triple("7003", "/c", true)))
        assertEquals(200, put.status)
        assertEquals(
            1,
            app
                .get("manga/${put.obj["mangaId"]!!.jsonPrimitive.int}/merged")
                .obj["sources"]!!
                .jsonArray.size,
        )
        app.put("merged", mergedBody(manga))
        assertTrue(app.operationsAfter(seq).isEmpty())
    }

    // ---- policy -------------------------------------------------------------------------------------------

    @Test
    fun `the download policy round trips, and only a change by the web is logged`() {
        val before =
            web
                .get("policy")
                .obj["downloadPolicy"]!!
                .jsonPrimitive.content
        val other = if (before == "SERVER_ONLY") "SERVER_AND_CLIENTS" else "SERVER_ONLY"

        fun policy(value: String) = buildJsonObject { put("downloadPolicy", value) }
        try {
            val seq = web.headSeq()
            assertEquals(200, web.put("policy", policy(other)).status)
            assertEquals(
                other,
                app
                    .get("policy")
                    .obj["downloadPolicy"]!!
                    .jsonPrimitive.content,
            )
            web.put("policy", policy(other)) // already so
            assertEquals(400, web.put("policy", policy("EVERYTHING")).status)
            assertEquals(400, web.request("PUT", "/api/v1/karasu/policy", "{}".toByteArray()).status)
            assertEquals(
                listOf("POLICY SET policy {\"downloadPolicy\":\"$other\"}"),
                web.operationsAfter(seq, "POLICY").map { it.summary },
            )
            assertEquals("web", web.operationsAfter(seq, "POLICY").single().deviceId)

            val seq2 = web.headSeq()
            assertEquals(200, app.put("policy", policy(before)).status)
            assertEquals(
                before,
                web
                    .get("policy")
                    .obj["downloadPolicy"]!!
                    .jsonPrimitive.content,
            )
            assertTrue(web.operationsAfter(seq2, "POLICY").isEmpty())
        } finally {
            app.put("policy", policy(before))
        }
    }

    // ---- remote deletions ---------------------------------------------------------------------------------

    @Test
    fun `remote deletions are queued by chapter, listed after a cursor and logged by the web only`() {
        val manga = mangaUrl(3)
        val m = web.get("manga/lookup", "sourceId" to ContractConfig.SOURCE_ID, "url" to manga).obj
        val chapterIds =
            m["chapters"]!!.jsonArray.associate {
                it.jsonObject["url"]!!.jsonPrimitive.content to
                    it.jsonObject["chapterId"]!!.jsonPrimitive.int
            }
        val cursor =
            web
                .get("deletions", "afterId" to 0)
                .json.jsonArray
                .maxOfOrNull { it.jsonObject["id"]!!.jsonPrimitive.long } ?: 0L
        val seq = web.headSeq()
        val t0 = now()

        val queued =
            web.post(
                "deletions",
                buildJsonObject {
                    put(
                        "chapterIds",
                        JsonArray(listOf(JsonPrimitive(chapterIds.getValue("$manga/c1")), JsonPrimitive(chapterIds.getValue("$manga/c3")))),
                    )
                    put("deleteFromServer", false)
                },
            )
        assertEquals(200, queued.status)
        assertEquals(2, queued.obj["queued"]!!.jsonPrimitive.int)
        val t1 = now()

        val fresh =
            app
                .get("deletions", "afterId" to cursor)
                .json.jsonArray
                .map { it.jsonObject }
        assertEquals(2, fresh.size)
        assertEquals(setOf("$manga/c1", "$manga/c3"), fresh.map { it["chapterUrl"]!!.jsonPrimitive.content }.toSet())
        assertTrue(
            fresh.all {
                it["sourceId"]!!.jsonPrimitive.content == ContractConfig.SOURCE_ID &&
                    it["mangaUrl"]!!.jsonPrimitive.content == manga
            },
        )
        assertTrue(fresh.zipWithNext().all { (a, b) -> a["id"]!!.jsonPrimitive.long < b["id"]!!.jsonPrimitive.long })
        assertTrue(fresh.all { it["requestedAt"]!!.jsonPrimitive.long in (t0 - 2000)..(t1 + 2000) })
        // only the devices were asked: the server's copy is still there
        assertEquals(
            true,
            web
                .get(
                    "chapter/lookup",
                    "sourceId" to "0",
                    "mangaUrl" to manga,
                    "chapterUrl" to "$manga/c1",
                ).obj["downloaded"]!!
                .jsonPrimitive.content
                .toBoolean(),
        )

        val ops = web.operationsAfter(seq, "DELETION")
        assertEquals(
            setOf(
                "DELETION SET deletion:0|$manga|$manga/c1 {\"deleteFromServer\":false}",
                "DELETION SET deletion:0|$manga|$manga/c3 {\"deleteFromServer\":false}",
            ),
            ops.map { it.summary }.toSet(),
        )
        assertTrue(ops.all { it.deviceId == "web" })

        // the app asks the same: queued, listed, but logged by the app, not by the server
        val seq2 = web.headSeq()
        val byApp =
            app.post(
                "deletions",
                buildJsonObject {
                    put("chapterIds", JsonArray(listOf(JsonPrimitive(chapterIds.getValue("$manga/c4")))))
                    put("deleteFromServer", false)
                },
            )
        assertEquals(1, byApp.obj["queued"]!!.jsonPrimitive.int)
        assertTrue(web.operationsAfter(seq2).isEmpty())
        assertEquals(
            0,
            web
                .post(
                    "deletions",
                    buildJsonObject {
                        put("chapterIds", JsonArray(listOf(JsonPrimitive(999_999_999))))
                        put("deleteFromServer", false)
                    },
                ).obj["queued"]!!
                .jsonPrimitive.int,
        )
    }

    // ---- history ------------------------------------------------------------------------------------------

    @Test
    fun `history is set in seconds and removed with zero, as the web, and logged as SET and REMOVE`() {
        bystander()
        val manga = mangaUrl(4)
        val chapterIds =
            app
                .manga(manga)
                .chapters
                .sortedBy { it.url }
                .map { it.id }

        fun history(vararg entries: Pair<Int, Long>) =
            buildJsonObject {
                put(
                    "entries",
                    buildJsonArray {
                        entries.forEach { (id, at) ->
                            add(
                                buildJsonObject {
                                    put("chapterId", id)
                                    put("lastReadAt", at)
                                },
                            )
                        }
                    },
                )
            }
        web.put("history", history(chapterIds[0] to 0, chapterIds[1] to 0))
        val seq = web.headSeq()

        val put = web.put("history", history(chapterIds[0] to 1_700_000_000, chapterIds[1] to 1_700_000_100, 999_999_999 to 5))
        assertEquals(200, put.status)
        assertEquals(2, put.obj["updated"]!!.jsonPrimitive.int)
        val read = web.manga(manga)
        assertEquals(1_700_000_000L, read.chapter("$manga/c1").lastReadAt)
        assertEquals(1_700_000_100L, read.chapter("$manga/c2").lastReadAt)

        web.put("history", history(chapterIds[0] to 1_700_000_000)) // same value: nothing
        web.put("history", history(chapterIds[0] to 0))
        assertEquals(0L, web.manga(manga).chapter("$manga/c1").lastReadAt)
        web.put("history", history(chapterIds[0] to 0)) // already out: nothing

        // c1: its SET was replaced by the REMOVE (a tombstone for the other device); c2: the SET
        assertEquals(
            listOf(
                "HISTORY SET history:0|$manga|$manga/c2 {\"lastReadAt\":1700000100}",
                "HISTORY REMOVE history:0|$manga|$manga/c1 null",
            ),
            web.operationsAfter(seq, "HISTORY").map { it.summary },
        )
        assertTrue(web.operationsAfter(seq, "HISTORY").all { it.deviceId == "web" })

        // the app writing history is not logged by the server
        val seq2 = web.headSeq()
        assertEquals(
            1,
            app
                .put("history", history(chapterIds[2] to 1_700_000_500))
                .obj["updated"]!!
                .jsonPrimitive.int,
        )
        assertEquals(1_700_000_500L, app.manga(manga).chapter("$manga/c3").lastReadAt)
        app.put("history", history(chapterIds[2] to 0))
        assertTrue(web.operationsAfter(seq2).isEmpty())
        web.put("history", history(chapterIds[1] to 0))
    }

    // ---- the operation log endpoints ----------------------------------------------------------------------

    @Test
    fun `operations are published idempotently by id and read back in seq order with the app's own ts`() {
        bystander()
        val seq = app.headSeq()
        val device = app.device!!
        val t = now() - 5 * 3600_000
        val a = appOperation(device, t, "CATEGORY", "category:910001", "SET", buildJsonObject { put("name", "Contract") })
        val b = appOperation(device, t + 1000, "CATEGORY_LINK", "link:0|/live/m01|910001", "SET")
        val c = appOperation(device, t + 2000, "CATEGORY", "category:910001", "REMOVE")

        fun body(vararg ops: kotlinx.serialization.json.JsonObject) = buildJsonObject { put("operations", JsonArray(ops.toList())) }
        val first = app.post("operations", body(a, b))
        assertEquals(200, first.status)
        assertEquals(2, first.obj["accepted"]!!.jsonPrimitive.int)
        assertEquals(0, first.obj["duplicates"]!!.jsonPrimitive.int)

        // a, b again (retry after a lost response) plus a new one
        val second = app.post("operations", body(a, b, c))
        assertEquals(1, second.obj["accepted"]!!.jsonPrimitive.int)
        assertEquals(2, second.obj["duplicates"]!!.jsonPrimitive.int)

        val page = app.get("operations", "afterSeq" to seq).obj
        val ops = page["operations"]!!.jsonArray.map { Op.of(it.jsonObject) }
        // no duplicates, and c (REMOVE) replaced a (SET) of the same target
        assertEquals(2, ops.size, "no duplicates in the log")
        assertTrue(ops.zipWithNext().all { (x, y) -> x.seq < y.seq })
        assertEquals(listOf(t + 1000, t + 2000), ops.map { it.ts })
        assertEquals(listOf(b, c).map { it["id"]!!.jsonPrimitive.content }, ops.map { it.id })
        assertTrue(ops.all { it.deviceId == device })
        assertEquals(listOf("SET", "REMOVE"), ops.map { it.action })
        assertEquals(null, ops[0].value)
        assertEquals(null, ops[1].value)
        assertEquals(ops.last().seq, page["lastSeq"]!!.jsonPrimitive.long)
        // serverTime lets the app correct its clock
        val serverTime = page["serverTime"]!!.jsonPrimitive.long
        assertTrue(kotlin.math.abs(serverTime - now()) < 10_000)

        // paging and the entity filter
        val firstPage = app.get("operations", "afterSeq" to seq, "limit" to 1).obj
        assertEquals(1, firstPage["operations"]!!.jsonArray.size)
        val rest = app.get("operations", "afterSeq" to firstPage["lastSeq"]!!.jsonPrimitive.long).obj
        assertEquals(1, rest["operations"]!!.jsonArray.size)
        assertEquals(1, app.operationsAfter(seq, "CATEGORY").size)
        assertEquals(1, app.operationsAfter(seq, "CATEGORY_LINK").size)
        val empty = app.get("operations", "afterSeq" to ops.last().seq).obj
        assertTrue(empty["operations"]!!.jsonArray.isEmpty())
        assertEquals(ops.last().seq, empty["lastSeq"]!!.jsonPrimitive.long)
        // limit is capped, not refused
        assertEquals(200, app.get("operations", "afterSeq" to seq, "limit" to 1_000_000).status)
    }

    @Test
    fun `invalid operations and queries are 400 with the error shape and write nothing`() {
        val seq = app.headSeq()
        val good = appOperation(app.device!!, now(), "POLICY", "policy", "SET", buildJsonObject { put("downloadPolicy", "SERVER_ONLY") })
        for (bad in listOf(
            appOperation(app.device!!, now(), "NOPE", "policy", "SET"),
            appOperation(app.device!!, now(), "POLICY", "policy", "UPSERT"),
            appOperation("", now(), "POLICY", "policy", "SET"),
            appOperation(app.device!!, now(), "POLICY", "", "SET"),
            appOperation(app.device!!, now(), "POLICY", "policy", "SET", id = ""),
        )) {
            val r =
                app.post(
                    "operations",
                    buildJsonObject {
                        put(
                            "operations",
                            buildJsonArray {
                                add(good)
                                add(bad)
                            },
                        )
                    },
                )
            assertEquals(400, r.status, bad.toString())
            assertTrue(
                r.obj["error"]!!
                    .jsonPrimitive.content
                    .isNotBlank(),
            )
        }
        assertEquals(400, app.postRaw("operations", "not json").status)
        assertEquals(400, app.get("operations", "afterSeq" to "x").status)
        assertEquals(400, app.get("operations", "entity" to "NOPE").status)
        assertTrue(app.operationsAfter(seq).isEmpty())
        // an empty batch is fine
        assertEquals(
            0,
            app
                .post("operations", buildJsonObject { put("operations", JsonArray(emptyList())) })
                .obj["accepted"]!!
                .jsonPrimitive.int,
        )
    }
}
