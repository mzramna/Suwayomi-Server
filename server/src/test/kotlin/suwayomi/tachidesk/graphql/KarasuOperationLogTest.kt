package suwayomi.tachidesk.graphql

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.manga.impl.Category
import suwayomi.tachidesk.manga.impl.CategoryManga
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.impl.karasu.DeletionRequestDto
import suwayomi.tachidesk.manga.impl.karasu.HistoryEntryDto
import suwayomi.tachidesk.manga.impl.karasu.KarasuBackups
import suwayomi.tachidesk.manga.impl.karasu.KarasuOperations
import suwayomi.tachidesk.manga.impl.karasu.KarasuRequestContext
import suwayomi.tachidesk.manga.impl.karasu.KarasuSync
import suwayomi.tachidesk.manga.impl.karasu.MergedSourceDto
import suwayomi.tachidesk.manga.impl.karasu.MergedSources
import suwayomi.tachidesk.manga.impl.karasu.OperationEntryDto
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryMetaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.KarasuDeletionTable
import suwayomi.tachidesk.manga.model.table.KarasuOperationTable
import suwayomi.tachidesk.manga.model.table.KarasuSettingTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.MergedChapterRouteTable
import suwayomi.tachidesk.manga.model.table.MergedMangaTable
import suwayomi.tachidesk.test.GraphQLTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every write point that feeds the Karasu operation log, through the real GraphQL schema and the REST
 * implementations: only EFFECTIVE changes are logged, with the contract's keys and values, and only for requests
 * that do not carry `X-Karasu-Device` (the app publishes its own log, with the real time of each action).
 */
class KarasuOperationLogTest : GraphQLTest() {
    @BeforeEach
    @AfterEach
    fun clean() {
        clearTables(
            KarasuOperationTable,
            KarasuDeletionTable,
            KarasuSettingTable,
            MergedChapterRouteTable,
            MergedMangaTable,
            CategoryMetaTable,
            CategoryMangaTable,
            ChapterTable,
            MangaUserTable,
            MangaTable,
        )
        transaction { CategoryTable.deleteWhere { CategoryTable.isDefaultCategory eq false } }
        KarasuRequestContext.clear()
    }

    private fun log(entity: String? = null): List<OperationEntryDto> = KarasuOperations.list(0, entity).operations

    private fun logKeys(entity: String? = null) = log(entity).map { "${it.entity} ${it.action} ${it.key} ${it.value ?: "null"}" }

    private fun createCategoryGql(
        name: String,
        device: String? = null,
    ): Int {
        val response =
            graphql(
                "mutation(\$input: CreateCategoryInput!) { createCategory(input: \$input) { category { id } } }",
                mapOf("input" to mapOf("name" to name)),
                device = device,
            )
        response.assertNoErrors()
        return response.dataPath("createCategory", "category", "id") as Int
    }

    private fun renameGql(
        id: Int,
        name: String,
        device: String? = null,
    ) = graphql(
        "mutation(\$input: UpdateCategoryInput!) { updateCategory(input: \$input) { category { id name } } }",
        mapOf("input" to mapOf("id" to id, "patch" to mapOf("name" to name))),
        device = device,
    ).assertNoErrors()

    private fun deleteGql(
        id: Int,
        device: String? = null,
    ) = graphql(
        "mutation(\$input: DeleteCategoryInput!) { deleteCategory(input: \$input) { category { id } } }",
        mapOf("input" to mapOf("categoryId" to id)),
        device = device,
    ).assertNoErrors()

    private fun linkGql(
        mangaIds: List<Int>,
        add: List<Int>? = null,
        remove: List<Int>? = null,
        clear: Boolean? = null,
        device: String? = null,
    ) = graphql(
        "mutation(\$input: UpdateMangasCategoriesInput!) { updateMangasCategories(input: \$input) { mangas { id } } }",
        mapOf(
            "input" to
                mapOf(
                    "ids" to mangaIds,
                    "patch" to mapOf("addToCategories" to add, "removeFromCategories" to remove, "clearCategories" to clear),
                ),
        ),
        device = device,
    ).assertNoErrors()

    private fun chapterIds(mangaId: Int): List<Int> =
        transaction {
            ChapterTable
                .selectAll()
                .where { ChapterTable.manga eq mangaId }
                .orderBy(ChapterTable.sourceOrder)
                .map { it[ChapterTable.id].value }
        }

    private fun setPageCount(
        chapterId: Int,
        pages: Int,
    ) = transaction { ChapterTable.update({ ChapterTable.id eq chapterId }) { it[pageCount] = pages } }

    private fun chaptersGql(
        ids: List<Int>,
        patch: Map<String, Any?>,
        device: String? = null,
    ) = graphql(
        "mutation(\$input: UpdateChaptersInput!) { updateChapters(input: \$input) { chapters { id } } }",
        mapOf("input" to mapOf("ids" to ids, "patch" to patch)),
        device = device,
    ).assertNoErrors()

    // ---- categories ---------------------------------------------------------------------------------------

    @Test
    fun `creating, renaming and deleting a category are logged with the contract's key and value`() {
        val id = createCategoryGql("Action")
        renameGql(id, "Adventure")
        deleteGql(id)

        assertEquals(
            listOf(
                "CATEGORY SET category:$id {\"name\":\"Action\"}",
                "CATEGORY SET category:$id {\"name\":\"Adventure\"}",
                "CATEGORY REMOVE category:$id null",
            ),
            logKeys(),
        )
        val ops = log()
        assertTrue(ops.all { it.deviceId == "web" })
        assertTrue(ops.zipWithNext().all { (a, b) -> a.ts <= b.ts })
        assertNull(ops.last().value)
    }

    @Test
    fun `a rename to the same name, a patch without a name and deleting what does not exist log nothing`() {
        val id = createCategoryGql("Same")
        val created = log().size
        renameGql(id, "Same")
        graphql(
            "mutation(\$input: UpdateCategoryInput!) { updateCategory(input: \$input) { category { id } } }",
            mapOf("input" to mapOf("id" to id, "patch" to mapOf("includeInUpdate" to "INCLUDE"))),
        ).assertNoErrors()
        deleteGql(987_654)
        assertEquals(created, log().size)
    }

    @Test
    fun `renaming several categories at once logs only the ones whose name changed`() {
        val a = createCategoryGql("A")
        val b = createCategoryGql("B")
        graphql(
            "mutation(\$input: UpdateCategoriesInput!) { updateCategories(input: \$input) { categories { id } } }",
            mapOf("input" to mapOf("ids" to listOf(a, b), "patch" to mapOf("name" to "B"))),
        ).assertNoErrors()
        // a is renamed to "B"; b already is "B"
        assertEquals(listOf("CATEGORY SET category:$a {\"name\":\"B\"}"), logKeys().drop(2))
    }

    @Test
    fun `a request that sends the device header is not logged by the server`() {
        val id = createCategoryGql("Mine", device = "pixel-8")
        renameGql(id, "Renamed", device = "pixel-8")
        deleteGql(id, device = "pixel-8")
        assertTrue(log().isEmpty())

        // the same actions without the header are logged, so the header is what made the difference
        val web = createCategoryGql("Web")
        renameGql(web, "Web 2")
        deleteGql(web)
        assertEquals(3, log().size)
    }

    @Test
    fun `the REST implementations log category changes too`() {
        val id = Category.createCategory(1, "Rest")
        Category.updateCategory(1, id, "Rest 2", null, null, null)
        Category.updateCategory(1, id, "Rest 2", null, null, null)
        Category.updateCategory(1, 987_654, "Ghost", null, null, null)
        Category.removeCategory(1, id)
        Category.removeCategory(1, id)
        assertEquals(
            listOf(
                "CATEGORY SET category:$id {\"name\":\"Rest\"}",
                "CATEGORY SET category:$id {\"name\":\"Rest 2\"}",
                "CATEGORY REMOVE category:$id null",
            ),
            logKeys(),
        )
        // creating an existing name creates nothing
        val again = Category.createCategory(1, "Dup")
        assertEquals(again, Category.createCategory(1, "dup"))
        assertEquals(4, log().size)
    }

    // ---- links --------------------------------------------------------------------------------------------

    @Test
    fun `adding and removing a manga in a category logs link keys by source, manga url and category`() {
        val manga = createLibraryManga("Berserk") // source 1, url "Berserk"
        val cat = createCategoryGql("Dark")
        val before = log().size

        linkGql(listOf(manga), add = listOf(cat))
        linkGql(listOf(manga), add = listOf(cat)) // already there: no-op
        linkGql(listOf(manga), remove = listOf(cat))
        linkGql(listOf(manga), remove = listOf(cat)) // already gone: no-op

        assertEquals(
            listOf("CATEGORY_LINK SET link:1|Berserk|$cat null", "CATEGORY_LINK REMOVE link:1|Berserk|$cat null"),
            logKeys("CATEGORY_LINK"),
        )
        assertEquals(before + 2, log().size)
    }

    @Test
    fun `batch link changes log one operation per link that really changed`() {
        val m1 = createLibraryManga("One")
        val m2 = createLibraryManga("Two")
        val a = createCategoryGql("A")
        val b = createCategoryGql("B")
        linkGql(listOf(m1), add = listOf(a)) // m1 is in A

        linkGql(listOf(m1, m2), add = listOf(a, b)) // only m1-B, m2-A, m2-B are new
        assertEquals(
            setOf("link:1|One|$a", "link:1|One|$b", "link:1|Two|$a", "link:1|Two|$b"),
            log("CATEGORY_LINK").map { it.key }.toSet(),
        )
        assertEquals(4, log("CATEGORY_LINK").size)

        linkGql(listOf(m1, m2), clear = true) // clears the four links
        assertEquals(4, log("CATEGORY_LINK").filter { it.action == "REMOVE" }.size)
        linkGql(listOf(m1, m2), clear = true) // nothing left
        assertEquals(8, log("CATEGORY_LINK").size)

        // removing a category the manga is not in changes nothing
        linkGql(listOf(m1), remove = listOf(a))
        assertEquals(8, log("CATEGORY_LINK").size)
    }

    @Test
    fun `link changes with the device header are not logged`() {
        val manga = createLibraryManga("Quiet")
        val cat = createCategoryGql("Quiet cat", device = "tab")
        linkGql(listOf(manga), add = listOf(cat), device = "tab")
        linkGql(listOf(manga), remove = listOf(cat), device = "tab")
        assertTrue(log().isEmpty())
    }

    @Test
    fun `the REST implementations log link changes`() {
        val manga = createLibraryManga("RestManga")
        val cat = Category.createCategory(1, "RestCat")
        CategoryManga.addMangaToCategory(1, manga, cat)
        CategoryManga.addMangaToCategory(1, manga, cat)
        CategoryManga.removeMangaFromCategory(1, manga, cat)
        CategoryManga.removeMangaFromCategory(1, manga, cat)
        CategoryManga.addMangaToCategory(1, manga, cat)
        CategoryManga.removeMangaFromAllCategories(1, manga)
        assertEquals(
            listOf(
                "CATEGORY_LINK SET link:1|RestManga|$cat null",
                "CATEGORY_LINK REMOVE link:1|RestManga|$cat null",
                "CATEGORY_LINK SET link:1|RestManga|$cat null",
                "CATEGORY_LINK REMOVE link:1|RestManga|$cat null",
            ),
            logKeys("CATEGORY_LINK"),
        )
    }

    // ---- progress -----------------------------------------------------------------------------------------

    @Test
    fun `chapter progress is logged by source, manga url and chapter url, only when read or page changed`() {
        val manga = createLibraryManga("Vagabond")
        createChapters(manga, 3, read = false)
        val ids = chapterIds(manga)
        ids.forEach { setPageCount(it, 20) }

        chaptersGql(listOf(ids[0]), mapOf("isRead" to true))
        chaptersGql(listOf(ids[0]), mapOf("isRead" to true)) // no-op
        chaptersGql(listOf(ids[0]), mapOf("lastPageRead" to 7))
        chaptersGql(listOf(ids[0]), mapOf("lastPageRead" to 7)) // no-op
        chaptersGql(listOf(ids[0]), mapOf("isBookmarked" to true)) // not progress
        chaptersGql(listOf(ids[1]), mapOf("lastPageRead" to 50)) // clamped to 20

        assertEquals(
            listOf(
                "PROGRESS SET progress:1|Vagabond|1 {\"read\":true,\"lastPageRead\":0}",
                "PROGRESS SET progress:1|Vagabond|1 {\"read\":true,\"lastPageRead\":7}",
                "PROGRESS SET progress:1|Vagabond|2 {\"read\":false,\"lastPageRead\":20}",
            ),
            logKeys("PROGRESS"),
        )
        assertTrue(log("PROGRESS").all { it.deviceId == "web" })
    }

    @Test
    fun `a batch update logs only the chapters that really changed`() {
        val manga = createLibraryManga("Batch")
        createChapters(manga, 4, read = false)
        val ids = chapterIds(manga)
        chaptersGql(listOf(ids[0], ids[1]), mapOf("isRead" to true))
        assertEquals(2, log("PROGRESS").size)

        // two of the four are already read
        chaptersGql(ids, mapOf("isRead" to true))
        assertEquals(setOf("progress:1|Batch|3", "progress:1|Batch|4"), log("PROGRESS").drop(2).map { it.key }.toSet())
        assertEquals(4, log("PROGRESS").size)

        // marking unread again is a change for every chapter
        chaptersGql(ids, mapOf("isRead" to false))
        assertEquals(8, log("PROGRESS").size)
        assertEquals(
            JsonObject(
                mapOf(
                    "read" to kotlinx.serialization.json.JsonPrimitive(false),
                    "lastPageRead" to kotlinx.serialization.json.JsonPrimitive(0),
                ),
            ),
            log("PROGRESS").last().value,
        )
    }

    @Test
    fun `updateChapter with the device header logs nothing, even in a document with several aliased mutations`() {
        val manga = createLibraryManga("Aliased")
        createChapters(manga, 2, read = false)
        val ids = chapterIds(manga)
        val document =
            "mutation(\$a: Int!, \$b: Int!) { " +
                "c0: updateChapter(input: {id: \$a, patch: {isRead: true}}) { clientMutationId } " +
                "c1: updateChapter(input: {id: \$b, patch: {isRead: true}}) { clientMutationId } }"
        graphql(document, mapOf("a" to ids[0], "b" to ids[1]), device = "pixel").assertNoErrors()
        assertTrue(log().isEmpty())

        // and the app's batch shape, without the header, logs one operation per chapter
        graphql(document.replace("isRead: true", "isRead: false"), mapOf("a" to ids[0], "b" to ids[1])).assertNoErrors()
        assertEquals(2, log("PROGRESS").size)
    }

    @Test
    fun `the REST chapter endpoints log progress, including the chapters marked as read before`() {
        val manga = createLibraryManga("RestProgress")
        createChapters(manga, 4, read = false)
        val ids = chapterIds(manga)

        Chapter.modifyChapter(1, manga, 3, isRead = true, isBookmarked = null, markPrevRead = true, lastPageRead = null)
        assertEquals(
            setOf("progress:1|RestProgress|1", "progress:1|RestProgress|2", "progress:1|RestProgress|3"),
            log("PROGRESS")
                .map {
                    it.key
                }.toSet(),
        )
        assertEquals(3, log("PROGRESS").size)

        // nothing changes the second time
        Chapter.modifyChapter(1, manga, 3, isRead = true, isBookmarked = null, markPrevRead = true, lastPageRead = null)
        assertEquals(3, log("PROGRESS").size)

        runBlocking {
            Chapter.modifyChapters(
                1,
                Chapter.MangaChapterBatchEditInput(chapterIds = ids, change = Chapter.ChapterChange(isRead = true)),
                manga,
            )
        }
        assertEquals(4, log("PROGRESS").size)
        assertEquals("progress:1|RestProgress|4", log("PROGRESS").last().key)
    }

    // ---- Karasu routes ------------------------------------------------------------------------------------

    @Test
    fun `history is logged as SET with seconds, REMOVE for zero, and only when it changes`() {
        val manga = createLibraryManga("Hist")
        createChapters(manga, 2, read = false)
        val ids = chapterIds(manga)

        KarasuSync.setHistory(
            1,
            listOf(HistoryEntryDto(ids[0], 1_700_000_000), HistoryEntryDto(ids[1], 1_700_000_100), HistoryEntryDto(999_999, 5)),
        )
        KarasuSync.setHistory(1, listOf(HistoryEntryDto(ids[0], 1_700_000_000))) // same value
        KarasuSync.setHistory(1, listOf(HistoryEntryDto(ids[0], 0), HistoryEntryDto(ids[1], 0)))
        KarasuSync.setHistory(1, listOf(HistoryEntryDto(ids[0], 0))) // already out

        assertEquals(
            listOf(
                "HISTORY SET history:1|Hist|1 {\"lastReadAt\":1700000000}",
                "HISTORY SET history:1|Hist|2 {\"lastReadAt\":1700000100}",
                "HISTORY REMOVE history:1|Hist|1 null",
                "HISTORY REMOVE history:1|Hist|2 null",
            ),
            logKeys("HISTORY"),
        )
    }

    @Test
    fun `history with the device header is not logged`() {
        val manga = createLibraryManga("HistQuiet")
        createChapters(manga, 1, read = false)
        KarasuRequestContext.withDevice("pixel") {
            KarasuSync.setHistory(1, listOf(HistoryEntryDto(chapterIds(manga)[0], 1_700_000_000)))
        }
        assertTrue(log().isEmpty())
    }

    @Test
    fun `merged sources are logged with the whole list after the change, keyed by the primary manga`() {
        val manga = createLibraryManga("Merge")
        MergedSources.replace(
            manga,
            listOf(MergedSourceDto("20", "/b", priority = 5), MergedSourceDto("10", "/a", priority = 1, updatesEnabled = false)),
        )
        // the same list once the priorities are normalized: nothing changes
        MergedSources.replace(
            manga,
            listOf(MergedSourceDto("10", "/a", priority = 0, updatesEnabled = false), MergedSourceDto("20", "/b", priority = 1)),
        )
        // the order is part of the state
        MergedSources.replace(
            manga,
            listOf(MergedSourceDto("20", "/b", priority = 0), MergedSourceDto("10", "/a", priority = 1, updatesEnabled = false)),
        )
        MergedSources.replace(manga, emptyList())
        MergedSources.replace(manga, emptyList()) // nothing changes

        val ops = log("MERGED")
        assertEquals(3, ops.size)
        assertTrue(ops.all { it.key == "merged:1|Merge" && it.action == "SET" })
        val list = (ops[0].value as JsonObject)["sources"].toString()
        assertEquals(
            """[{"sourceId":"10","url":"/a","priority":0,"updatesEnabled":false},{"sourceId":"20","url":"/b","priority":1,"updatesEnabled":true}]""",
            list,
        )
        assertEquals(buildJsonObject { put("sources", kotlinx.serialization.json.JsonArray(emptyList())) }, ops[2].value)
    }

    @Test
    fun `the download policy is logged when it changes, not when it is set to what it already is`() {
        KarasuSync.setPolicy("SERVER_ONLY") // the default
        KarasuSync.setPolicy("SERVER_AND_CLIENTS")
        KarasuSync.setPolicy("SERVER_AND_CLIENTS")
        KarasuSync.setPolicy("SERVER_ONLY")
        assertEquals(
            listOf(
                "POLICY SET policy {\"downloadPolicy\":\"SERVER_AND_CLIENTS\"}",
                "POLICY SET policy {\"downloadPolicy\":\"SERVER_ONLY\"}",
            ),
            logKeys("POLICY"),
        )
    }

    @Test
    fun `remote deletions are logged per chapter with deleteFromServer`() {
        val manga = createLibraryManga("Del")
        createChapters(manga, 2, read = false)
        val ids = chapterIds(manga)
        runBlocking { KarasuSync.requestDeletions(1, DeletionRequestDto(ids, deleteFromServer = false)) }
        assertEquals(
            listOf(
                "DELETION SET deletion:1|Del|1 {\"deleteFromServer\":false}",
                "DELETION SET deletion:1|Del|2 {\"deleteFromServer\":false}",
            ),
            logKeys("DELETION"),
        )
        // the same request again is a no-op for the log
        runBlocking { KarasuSync.requestDeletions(1, DeletionRequestDto(ids, deleteFromServer = false)) }
        assertEquals(2, log("DELETION").size)
        // another flag is a change
        runBlocking { KarasuSync.requestDeletions(1, DeletionRequestDto(ids.take(1), deleteFromServer = true)) }
        assertEquals("""{"deleteFromServer":true}""", log("DELETION").last().value.toString())
        runBlocking {
            KarasuRequestContext.withDevice("pixel") { KarasuSync.requestDeletions(1, DeletionRequestDto(ids, deleteFromServer = false)) }
        }
        assertEquals(3, log("DELETION").size)
    }

    @Test
    fun `backups upload and delete are logged with device and size`() {
        val saved = KarasuBackups.save("a.tachibk", "Pixel 8", "hello".byteInputStream())
        assertTrue(KarasuBackups.delete(saved.id))
        assertEquals(false, KarasuBackups.delete(saved.id))
        val withHeader = KarasuRequestContext.withDevice("pixel") { KarasuBackups.save("b.tachibk", "Pixel 8", "x".byteInputStream()) }
        KarasuRequestContext.withDevice("pixel") { KarasuBackups.delete(withHeader.id) }

        assertEquals(
            listOf(
                "BACKUP SET backup:${saved.id} {\"device\":\"Pixel 8\",\"sizeBytes\":5}",
                "BACKUP REMOVE backup:${saved.id} null",
            ),
            logKeys("BACKUP"),
        )
    }

    @Test
    fun `asking for the default category by name creates and logs nothing`() {
        Category.createCategory(1, "Default") // illegal name: it only returns the default category's id
        assertTrue(log().isEmpty())
    }
}
