package suwayomi.tachidesk.contract

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import suwayomi.tachidesk.test.FakeExtensionStoreServer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The GraphQL paths the Karasu app uses (categories, category links, reading progress), run as the app (header
 * X-Karasu-Device) and as the WebUI (no header). Each action is checked in the state, read back the way the app
 * reads it, and in the operation log: the server logs only the WebUI's changes (device `web`, ts = when it
 * happened); the app's changes are published by the app itself with the real time of the action.
 */
@EnabledIfSystemProperty(named = "contractUrl", matches = ".+")
class ContractGraphQlTest {
    private val web = ContractClient(null)
    private val app = ContractClient("contract-app-" + UUID.randomUUID().toString().take(8))
    private val suffix = UUID.randomUUID().toString().take(6)

    private fun mangaUrl(n: Int) = "/live/m%02d".format(n)

    private fun now() = System.currentTimeMillis()

    private fun window(
        t0: Long,
        t1: Long,
    ) = (t0 - 2000)..(t1 + 2000)

    private fun publish(vararg ops: JsonObject) = app.post("operations", buildJsonObject { put("operations", JsonArray(ops.toList())) })

    // ---- categories ---------------------------------------------------------------------------------------

    @Test
    fun `the web creating, renaming and deleting a category is logged with ts and device web, compacted to the last one`() {
        bystander()
        val seq = web.headSeq()
        val t0 = now()
        val name = "web-cat-$suffix"
        val id = web.createCategory(name)
        assertEquals(name, app.categories()[id])
        assertEquals("$name-2", web.renameCategory(id, "$name-2")) // updateCategory: the rename the contract talks about
        assertEquals("$name-2", app.categories()[id])
        assertEquals("$name-2", web.renameCategory(id, "$name-2")) // same name: nothing
        // creating and renaming left one operation: the rename
        assertEquals(listOf("CATEGORY SET category:$id {\"name\":\"$name-2\"}"), web.operationsAfter(seq, "CATEGORY").map { it.summary })
        web.deleteCategory(id)
        assertFalse(id in app.categories())
        web.deleteCategory(id) // already gone: nothing
        val t1 = now()

        val ops = web.operationsAfter(seq, "CATEGORY")
        // the REMOVE replaced the SET: one tombstone, for the device that may hold the category
        assertEquals(listOf("CATEGORY REMOVE category:$id null"), ops.map { it.summary })
        assertTrue(ops.all { it.deviceId == "web" && it.ts in window(t0, t1) })
    }

    @Test
    fun `the app creating and renaming a category is not logged by the server, the app publishes it with the real ts`() {
        val seq = app.headSeq()
        val name = "app-cat-$suffix"
        val created = now() - 2 * 3600_000 // the action happened two hours before this sync
        val id = app.createCategory(name)
        val renamed = created + 60_000
        app.renameCategory(id, "$name-2")
        assertEquals("$name-2", web.categories()[id])
        assertTrue(app.operationsAfter(seq).isEmpty(), "the server must not stamp the app's change with the sync time")

        val result =
            publish(
                appOperation(app.device!!, created, "CATEGORY", "category:$id", "SET", buildJsonObject { put("name", name) }),
                appOperation(app.device!!, renamed, "CATEGORY", "category:$id", "SET", buildJsonObject { put("name", "$name-2") }),
            )
        assertEquals(2, result.obj["accepted"]!!.jsonPrimitive.int)
        // both were accepted, the rename replaced the creation
        val ops = web.operationsAfter(seq, "CATEGORY")
        assertEquals(listOf(renamed), ops.map { it.ts })
        assertEquals(listOf(app.device), ops.map { it.deviceId })
        assertEquals(listOf("""{"name":"$name-2"}"""), ops.map { it.value.toString() })

        // later the web renames it: a second device in the log, newer ts, the key is the same
        val t0 = now()
        web.renameCategory(id, "$name-web")
        val all = web.operationsAfter(seq, "CATEGORY")
        assertEquals(1, all.size, "the newer operation of the other device replaces it")
        assertEquals("web", all.last().deviceId)
        assertTrue(all.last().ts >= t0 - 2000 && all.last().ts > renamed)
        web.deleteCategory(id)
    }

    // ---- links --------------------------------------------------------------------------------------------

    @Test
    fun `the web adding and removing a manga in categories is logged by source, manga url and category`() {
        bystander()
        val manga = mangaUrl(1)
        val mangaId = web.manga(manga).id
        val a = web.createCategory("web-link-a-$suffix")
        val b = web.createCategory("web-link-b-$suffix")
        val seq = web.headSeq()
        val t0 = now()

        web.updateMangaCategories(mangaId, add = listOf(a, b), remove = emptyList())
        assertEquals(
            setOf(a, b),
            app
                .manga(manga)
                .categoryIds
                .filter { it == a || it == b }
                .toSet(),
        )
        web.updateMangaCategories(mangaId, add = listOf(a), remove = emptyList()) // already linked: nothing
        web.updateMangaCategories(mangaId, add = emptyList(), remove = listOf(a))
        assertEquals(
            setOf(b),
            app
                .manga(manga)
                .categoryIds
                .filter { it == a || it == b }
                .toSet(),
        )
        web.updateMangaCategories(mangaId, add = emptyList(), remove = listOf(a)) // already out: nothing
        val t1 = now()

        val ops = web.operationsAfter(seq, "CATEGORY_LINK")
        // link b is still set; link a was set and removed: its SET was replaced by the tombstone
        assertEquals(
            setOf("CATEGORY_LINK SET link:0|$manga|$b null", "CATEGORY_LINK REMOVE link:0|$manga|$a null"),
            ops.map { it.summary }.toSet(),
        )
        assertEquals(2, ops.size)
        assertTrue(ops.all { it.deviceId == "web" && it.ts in window(t0, t1) && it.value == null })

        web.updateMangaCategories(mangaId, add = emptyList(), remove = listOf(b))
        web.deleteCategory(a)
        web.deleteCategory(b)
    }

    @Test
    fun `the app linking and unlinking a manga is not logged by the server, the app publishes it`() {
        bystander()
        val manga = mangaUrl(2)
        val mangaId = app.manga(manga).id
        val cat = app.createCategory("app-link-$suffix")
        val seq = app.headSeq()
        val linkedAt = now() - 90 * 60_000
        val unlinkedAt = linkedAt + 30 * 60_000

        app.updateMangaCategories(mangaId, add = listOf(cat), remove = emptyList())
        assertTrue(cat in web.manga(manga).categoryIds)
        app.updateMangaCategories(mangaId, add = emptyList(), remove = listOf(cat))
        assertFalse(cat in web.manga(manga).categoryIds)
        assertTrue(app.operationsAfter(seq).isEmpty())

        val key = "link:0|$manga|$cat"
        publish(
            appOperation(app.device!!, linkedAt, "CATEGORY_LINK", key, "SET"),
            appOperation(app.device!!, unlinkedAt, "CATEGORY_LINK", key, "REMOVE"),
        )
        val ops = web.operationsAfter(seq, "CATEGORY_LINK")
        assertEquals(listOf("REMOVE"), ops.map { it.action }, "the REMOVE replaced the SET and stays as a tombstone for the other device")
        assertEquals(listOf(unlinkedAt), ops.map { it.ts })
        assertTrue(ops.all { it.deviceId == app.device && it.key == key })
        web.deleteCategory(cat)
    }

    // ---- progress -----------------------------------------------------------------------------------------

    @Test
    fun `the web marking chapters read is logged per chapter that changed, with read and lastPageRead`() {
        val manga = mangaUrl(7)
        val chapters = web.manga(manga).chapters.sortedBy { it.url }
        // known starting point, set as the app so that nothing of this reaches the log
        app.updateChaptersLikeTheApp(chapters.map { Triple(it.id, false, 0) })
        val seq = web.headSeq()
        val t0 = now()

        // the WebUI applies one patch to many chapters: c1 and c2 become read
        web.updateChaptersLikeTheWeb(listOf(chapters[0].id, chapters[1].id), buildJsonObject { put("isRead", true) })
        val after = app.manga(manga)
        assertEquals(listOf(true, true, false, false), after.chapters.sortedBy { it.url }.map { it.isRead })
        // again, now with c3: only c3 is a change
        web.updateChaptersLikeTheWeb(listOf(chapters[0].id, chapters[1].id, chapters[2].id), buildJsonObject { put("isRead", true) })
        // a bookmark is not reading progress
        web.updateChaptersLikeTheWeb(listOf(chapters[3].id), buildJsonObject { put("isBookmarked", true) })
        // the page read: 2 on c4 (the local chapters have 3 pages, so 2 is the last one)
        web.updateChaptersLikeTheWeb(listOf(chapters[3].id), buildJsonObject { put("lastPageRead", 0) }) // already 0: nothing
        val t1 = now()

        val ops = web.operationsAfter(seq, "PROGRESS")
        val byKey = ops.groupBy { it.key }
        assertEquals(
            setOf("progress:0|$manga|$manga/c1", "progress:0|$manga|$manga/c2", "progress:0|$manga|$manga/c3"),
            byKey.keys,
        )
        assertTrue(byKey.values.all { it.size == 1 }, "a chapter that did not change is not logged again")
        assertTrue(ops.all { it.deviceId == "web" && it.action == "SET" && it.ts in window(t0, t1) })
        assertEquals(
            """{"read":true,"lastPageRead":0}""",
            byKey
                .getValue("progress:0|$manga|$manga/c1")
                .single()
                .value
                .toString(),
        )
        assertEquals(3, ops.size)
    }

    @Test
    fun `the app updating chapters in aliased mutations is not logged by the server, the app publishes the progress`() {
        val manga = mangaUrl(8)
        val chapters = app.manga(manga).chapters.sortedBy { it.url }
        app.updateChaptersLikeTheApp(chapters.map { Triple(it.id, false, 0) })
        val seq = app.headSeq()
        val readAt = now() - 4 * 3600_000

        // the app's batch: several chapters, each with its own patch, in one document
        app.updateChaptersLikeTheApp(listOf(Triple(chapters[0].id, true, 2), Triple(chapters[1].id, false, 1)))
        val state = web.manga(manga).chapters.sortedBy { it.url }
        assertEquals(listOf(true, false), state.take(2).map { it.isRead })
        assertEquals(listOf(2, 1), state.take(2).map { it.lastPageRead })
        assertTrue(app.operationsAfter(seq).isEmpty())

        publish(
            appOperation(
                app.device!!,
                readAt,
                "PROGRESS",
                "progress:0|$manga|$manga/c1",
                "SET",
                buildJsonObject {
                    put("read", true)
                    put("lastPageRead", 2)
                },
            ),
            appOperation(
                app.device!!,
                readAt + 1000,
                "PROGRESS",
                "progress:0|$manga|$manga/c2",
                "SET",
                buildJsonObject {
                    put("read", false)
                    put("lastPageRead", 1)
                },
            ),
        )
        val ops = web.operationsAfter(seq, "PROGRESS")
        assertEquals(listOf(readAt, readAt + 1000), ops.map { it.ts })
        assertEquals(listOf("""{"read":true,"lastPageRead":2}""", """{"read":false,"lastPageRead":1}"""), ops.map { it.value.toString() })

        // and the same state change made by the web afterwards, on the same key, is the newer one
        val t0 = now()
        web.updateChaptersLikeTheWeb(listOf(chapters[0].id), buildJsonObject { put("isRead", false) })
        val newest = web.operationsAfter(seq, "PROGRESS").last()
        assertEquals("web", newest.deviceId)
        assertEquals("progress:0|$manga|$manga/c1", newest.key)
        assertEquals("""{"read":false,"lastPageRead":2}""", newest.value.toString())
        assertTrue(newest.ts >= t0 - 2000 && newest.ts > readAt)
    }

    // ---- extension repositories ---------------------------------------------------------------------------

    private val stores = FakeExtensionStoreServer()

    @AfterEach
    fun stopStores() = stores.close()

    @Test
    fun `the web adding and removing an extension repository is logged as web with the repo key`() {
        bystander()
        val url = stores.store("/contract-web-$suffix/index.json")
        val seq = web.headSeq()
        val t0 = now()

        assertEquals(url, web.addExtensionStore(url))
        assertTrue(url in app.extensionStoreUrls())
        assertEquals(url, web.addExtensionStore(url)) // already there: nothing
        web.removeExtensionStore(url)
        assertFalse(url in app.extensionStoreUrls())
        web.removeExtensionStore(url) // already gone: nothing
        web.removeExtensionStore("http://127.0.0.1:1/never-$suffix/index.json") // never existed: nothing
        val t1 = now()

        val ops = web.operationsAfter(seq, "EXTENSION_REPO")
        assertEquals(listOf("EXTENSION_REPO REMOVE repo:$url null"), ops.map { it.summary })
        assertTrue(ops.all { it.deviceId == "web" && it.ts in window(t0, t1) })
    }

    @Test
    fun `the app adding and removing an extension repository is not logged by the server, the app publishes it`() {
        bystander()
        val url = stores.store("/contract-app-$suffix/index.json")
        val seq = app.headSeq()
        val addedAt = now() - 3 * 3600_000 // the action happened three hours before this sync
        val removedAt = addedAt + 600_000

        assertEquals(url, app.addExtensionStore(url))
        assertTrue(url in web.extensionStoreUrls())
        app.removeExtensionStore(url)
        assertFalse(url in web.extensionStoreUrls())
        assertTrue(app.operationsAfter(seq).isEmpty(), "the server must not stamp the app's change with the sync time")

        val key = "repo:$url"
        val result =
            publish(
                appOperation(app.device!!, addedAt, "EXTENSION_REPO", key, "SET", buildJsonObject { put("indexUrl", url) }),
                appOperation(app.device!!, removedAt, "EXTENSION_REPO", key, "REMOVE"),
            )
        assertEquals(2, result.obj["accepted"]!!.jsonPrimitive.int)
        val ops = web.operationsAfter(seq, "EXTENSION_REPO")
        assertEquals(listOf("REMOVE"), ops.map { it.action })
        assertEquals(listOf(removedAt), ops.map { it.ts })
        assertEquals(listOf("null"), ops.map { it.value.toString() })
        assertTrue(ops.all { it.deviceId == app.device && it.key == key })

        // and the web adding it back is the newer operation on the same key, from the other device
        web.addExtensionStore(url)
        val all = web.operationsAfter(seq, "EXTENSION_REPO")
        assertEquals(1, all.size, "the web's SET replaces the tombstone")
        assertEquals("web", all.last().deviceId)
        assertEquals(key, all.last().key)
        assertTrue(all.last().ts > removedAt)
        web.removeExtensionStore(url)
    }

    // ---- both devices -------------------------------------------------------------------------------------

    @Test
    fun `an app change older than the web change on the same chapter is discarded as a duplicate, the newer one stays`() {
        val manga = mangaUrl(9)
        val chapter =
            app
                .manga(manga)
                .chapters
                .sortedBy { it.url }
                .first()
        app.updateChaptersLikeTheApp(listOf(Triple(chapter.id, false, 0)))
        val seq = web.headSeq()
        val key = "progress:0|$manga|$manga/c1"

        val appTs = now() - 3600_000
        app.updateChaptersLikeTheApp(listOf(Triple(chapter.id, true, 1)))
        val webStart = now()
        web.updateChaptersLikeTheWeb(listOf(chapter.id), buildJsonObject { put("lastPageRead", 2) })
        // the app syncs its log now, twice (a retry): one operation, not two
        val op =
            appOperation(
                app.device!!,
                appTs,
                "PROGRESS",
                key,
                "SET",
                buildJsonObject {
                    put("read", true)
                    put("lastPageRead", 1)
                },
            )
        // the web's operation (newer ts) is in the log already: the app's older one is not kept, now or on a retry
        val first = publish(op)
        assertEquals(0, first.obj["accepted"]!!.jsonPrimitive.int)
        assertEquals(1, first.obj["duplicates"]!!.jsonPrimitive.int)
        assertEquals(1, publish(op).obj["duplicates"]!!.jsonPrimitive.int)

        val ops = app.operationsAfter(seq, "PROGRESS")
        assertEquals(listOf("web"), ops.map { it.deviceId })
        assertEquals("""{"read":true,"lastPageRead":2}""", ops[0].value.toString())
        assertTrue(ops[0].ts >= webStart - 2000)
        val final = web.manga(manga).chapter("$manga/c1")
        assertEquals(true, final.isRead)
        assertEquals(2, final.lastPageRead)
    }

    @Test
    fun `a header with only blanks counts as the web`() {
        bystander()
        val blank = ContractClient("   ")
        val seq = web.headSeq()
        val id = blank.createCategory("blank-$suffix")
        blank.deleteCategory(id)
        val ops = web.operationsAfter(seq, "CATEGORY")
        assertEquals(listOf("CATEGORY REMOVE category:$id null"), ops.map { it.summary })
        assertTrue(ops.all { it.deviceId == "web" })
    }
}
