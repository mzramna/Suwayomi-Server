package suwayomi.tachidesk.manga.impl.karasu

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.KarasuDeletionTable
import suwayomi.tachidesk.manga.model.table.KarasuSettingTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MergedChapterRouteTable
import suwayomi.tachidesk.manga.model.table.MergedMangaTable
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KarasuSyncTest : ApplicationTest() {
    @BeforeEach
    @AfterEach
    fun clean() {
        clearTables(MergedChapterRouteTable, MergedMangaTable, KarasuDeletionTable, KarasuSettingTable, ChapterTable, MangaTable)
    }

    @Test
    fun `migration created the karasu tables`() {
        // selecting from each table fails if the M0069 migration did not create it
        transaction {
            assertEquals(0L, MergedMangaTable.selectAll().count())
            assertEquals(0L, MergedChapterRouteTable.selectAll().count())
            assertEquals(0L, KarasuDeletionTable.selectAll().count())
            assertEquals(0L, KarasuSettingTable.selectAll().count())
        }
    }

    @Test
    fun `merged sources are replaced, ordered by priority and never include the primary source`() {
        val mangaId = createLibraryManga("Merge") // source 1
        val group =
            MergedSources.replace(
                mangaId,
                listOf(
                    MergedSourceDto("20", "/b", priority = 5),
                    MergedSourceDto("10", "/a", priority = 1, updatesEnabled = false),
                    MergedSourceDto("1", "/primary-itself", priority = 0),
                    MergedSourceDto("20", "/dup", priority = 9),
                    MergedSourceDto("abc", "/bad-id"),
                    MergedSourceDto("30", " "),
                ),
            )
        assertNotNull(group)
        assertEquals(listOf("10", "20"), group.sources.map { it.sourceId })
        assertEquals(listOf(0, 1), group.sources.map { it.priority })
        assertEquals(listOf(false, true), group.sources.map { it.updatesEnabled })
        assertEquals("1", group.primary.sourceId)
        assertEquals("Merge", group.primary.url)

        // replacing again drops what is not sent anymore
        val again = MergedSources.replace(mangaId, listOf(MergedSourceDto("20", "/b")))
        assertEquals(listOf("20"), again?.sources?.map { it.sourceId })
        assertEquals(1, MergedSources.getAllGroups().size)
        assertEquals(mangaId, MergedSources.findMangaId(1L, "Merge"))
        assertNull(MergedSources.findMangaId(2L, "Merge"))
        assertNull(MergedSources.replace(999_999, emptyList()))
    }

    @Test
    fun `chapter routes tell which source serves the pages`() {
        val mangaId = createLibraryManga("Route")
        createChapters(mangaId, 2, false)
        val chapterId = transaction { ChapterTable.selectAll().first()[ChapterTable.id].value }

        assertNull(MergedSources.routeFor(chapterId))
        MergedSources.setRoute(chapterId, 20L, isExtra = false)
        assertEquals(20L, MergedSources.routeFor(chapterId)?.sourceId)

        // the main source answered again: a fallback route must go away
        MergedSources.clearFallbackRoute(chapterId)
        assertNull(MergedSources.routeFor(chapterId))

        // an "extra" chapter (it only exists on the merged source) keeps its route
        MergedSources.setRoute(chapterId, 20L, isExtra = true)
        MergedSources.clearFallbackRoute(chapterId)
        assertEquals(true, MergedSources.routeFor(chapterId)?.isExtra)
    }

    @Test
    fun `aggregate leaves the list alone when nothing is linked`() {
        val mangaId = createLibraryManga("NoLinks")
        val chapters = emptyList<eu.kanade.tachiyomi.source.model.SChapter>()
        val result = runBlocking { MergedSources.aggregate(mangaId, "NoLinks", chapters) }
        assertEquals(chapters, result.chapters)
        assertTrue(result.extraUrlToSource.isEmpty())
    }

    @Test
    fun `download policy defaults to server only and rejects unknown values`() {
        assertEquals("SERVER_ONLY", KarasuSync.getPolicy().downloadPolicy)
        assertEquals("SERVER_AND_CLIENTS", KarasuSync.setPolicy("SERVER_AND_CLIENTS").downloadPolicy)
        assertEquals("SERVER_AND_CLIENTS", KarasuSync.getPolicy().downloadPolicy)
        assertEquals("SERVER_ONLY", KarasuSync.setPolicy("SERVER_ONLY").downloadPolicy)
        assertFailsWith<IllegalArgumentException> { KarasuSync.setPolicy("EVERYTHING") }
    }

    @Test
    fun `remote deletions are queued by source and url and listed after an id`() {
        val mangaId = createLibraryManga("Del")
        createChapters(mangaId, 3, false)
        val ids = transaction { ChapterTable.selectAll().map { it[ChapterTable.id].value } }

        val queued = runBlocking { KarasuSync.requestDeletions(1, DeletionRequestDto(ids.take(2), deleteFromServer = false)) }
        assertEquals(2, queued.queued)

        val all = KarasuSync.listDeletions(0)
        assertEquals(2, all.size)
        assertEquals("1", all.first().sourceId)
        assertEquals("Del", all.first().mangaUrl)
        assertTrue(all.zipWithNext().all { (a, b) -> a.id < b.id })
        assertEquals(listOf(all.last().id), KarasuSync.listDeletions(all.first().id).map { it.id })
        assertTrue(KarasuSync.listDeletions(all.last().id).isEmpty())
    }

    @Test
    fun `history is set and removed by chapter id, in seconds, and unknown chapters are ignored`() {
        val mangaId = createLibraryManga("Hist")
        createChapters(mangaId, 2, false)
        val ids = transaction { ChapterTable.selectAll().map { it[ChapterTable.id].value }.sorted() }

        fun lastReadAt(chapterId: Int): Long? =
            transaction {
                ChapterUserTable
                    .selectAll()
                    .where { (ChapterUserTable.chapter eq chapterId) and (ChapterUserTable.user eq 1) }
                    .firstOrNull()
                    ?.get(ChapterUserTable.lastReadAt)
            }

        val updated =
            KarasuSync.setHistory(
                1,
                listOf(HistoryEntryDto(ids[0], 1_700_000_000), HistoryEntryDto(ids[1], 1_700_000_100), HistoryEntryDto(999_999, 5)),
            )
        assertEquals(2, updated.updated)
        assertEquals(1_700_000_000L, lastReadAt(ids[0]))
        assertEquals(1_700_000_100L, lastReadAt(ids[1]))

        // 0 takes a chapter out of the history; the other one is untouched
        KarasuSync.setHistory(1, listOf(HistoryEntryDto(ids[0], 0)))
        assertEquals(0L, lastReadAt(ids[0]))
        assertEquals(1_700_000_100L, lastReadAt(ids[1]))

        // a negative time is not a time
        KarasuSync.setHistory(1, listOf(HistoryEntryDto(ids[1], -5)))
        assertEquals(0L, lastReadAt(ids[1]))
        assertEquals(0, KarasuSync.setHistory(1, emptyList()).updated)
    }

    @Test
    fun `lookups find chapters by source and urls`() {
        val mangaId = createLibraryManga("Look")
        createChapters(mangaId, 2, false)

        val manga = KarasuSync.lookupManga(1L, "Look")
        assertNotNull(manga)
        assertEquals(mangaId, manga.mangaId)
        assertEquals(2, manga.chapters.size)
        assertNull(KarasuSync.lookupManga(1L, "nope"))

        val chapter = runBlocking { KarasuSync.lookupChapter(1L, "Look", "1") }
        assertNotNull(chapter)
        assertEquals(false, chapter.downloaded)
        assertEquals(-1L, chapter.sizeBytes)
        assertNull(runBlocking { KarasuSync.lookupChapter(1L, "Look", "99") })
    }

    @Test
    fun `backups are stored per device, listed newest first, and cannot escape their folder`() {
        val first = KarasuBackups.save("a.tachibk", "Pixel 8", "one".byteInputStream())
        Thread.sleep(5)
        val second = KarasuBackups.save("b.tachibk", null, "two".byteInputStream())
        try {
            assertEquals("Pixel 8", first.device)
            assertEquals("a.tachibk", first.name)
            assertEquals(3L, first.sizeBytes)
            assertNull(second.device)

            val listed = KarasuBackups.list().map { it.id }
            assertTrue(listed.indexOf(second.id) < listed.indexOf(first.id))

            assertNotNull(KarasuBackups.find(first.id))
            assertNull(KarasuBackups.find("../server.conf"))
            assertNull(KarasuBackups.find("a/b"))
            assertNull(KarasuBackups.find("does-not-exist"))
        } finally {
            KarasuBackups.delete(first.id)
            KarasuBackups.delete(second.id)
        }
        assertNull(KarasuBackups.find(first.id))
    }

    @Test
    fun `health reports the database and storage`() {
        val health = KarasuHealth.get()
        assertEquals("ok", health.status)
        assertEquals(KarasuHealth.API_VERSION, health.apiVersion)
        // the suite runs on H2 and on PostgreSQL (CI matrix), so compare with the configured type
        assertEquals(serverConfig.databaseType.value.name, health.database.type)
        assertTrue(health.storage.type == "local" || health.storage.type == "nfs")
        assertTrue(health.storage.totalBytes >= health.storage.freeBytes)
    }
}
