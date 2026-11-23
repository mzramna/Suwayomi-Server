package suwayomi.tachidesk.manga.impl.karasu

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.impl.Manga
import suwayomi.tachidesk.manga.impl.chapter.refreshChapterPageList
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MergedChapterRouteTable
import suwayomi.tachidesk.manga.model.table.MergedMangaTable
import suwayomi.tachidesk.manga.model.table.PageTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import suwayomi.tachidesk.manga.impl.Page as PageService

/**
 * Merged sources end to end, with fake HTTP sources registered in the real source registry and a
 * real HTTP server for the images: chapter aggregation, page fallback and the image route that
 * the reader uses, on whichever database the suite runs on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MergedSourcesFlowTest : ApplicationTest() {
    private lateinit var http: HttpServer
    private lateinit var baseUrl: String

    // a 1x1 PNG; each source appends its own tag after the image so the bytes say who served them
    private val png =
        Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )

    private class FakeSource(
        override val id: Long,
        override val baseUrl: String,
        private val tag: String,
        private val chapterNumbers: List<Int>,
        @Volatile var failPages: Boolean = false,
    ) : HttpSource() {
        override val name = "Fake $tag"
        override val lang = "en"
        override val supportsLatest = false

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate =
            SMangaUpdate(
                manga,
                if (fetchChapters) {
                    chapterNumbers.map { n ->
                        SChapter.create().apply {
                            url = "/$tag/c$n"
                            this.name = "Chapter $n"
                            chapter_number = n.toFloat()
                        }
                    }
                } else {
                    chapters
                },
            )

        override suspend fun getPageList(chapter: SChapter): List<Page> {
            if (failPages) throw IOException("source $tag is down")
            return (0..1).map { Page(it, "", "$baseUrl/img/$tag/$it.png") }
        }
    }

    @BeforeAll
    fun startImageServer() {
        http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/img/") { exchange ->
            val tag =
                exchange.requestURI.path
                    .removePrefix("/img/")
                    .substringBefore('/')
            val body = png + tag.toByteArray()
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        baseUrl = "http://127.0.0.1:${http.address.port}"
    }

    @AfterAll
    fun stopImageServer() = http.stop(0)

    @BeforeEach
    @AfterEach
    fun clean() {
        listOf(PRIMARY, EXTRA, DOWN, RESERVE).forEach { GetSource.unregisterSource(it) }
        clearTables(MergedChapterRouteTable, MergedMangaTable, PageTable, ChapterTable, MangaTable)
    }

    private fun register(source: FakeSource) = GetSource.registerSource(source.id to source)

    private fun newManga(
        title: String,
        sourceId: Long,
    ): Int =
        transaction {
            MangaTable
                .insertAndGetId {
                    it[MangaTable.title] = title
                    it[url] = "/$title"
                    it[sourceReference] = sourceId
                    it[initialized] = true
                }.value
        }

    private fun chapters(mangaId: Int) =
        transaction {
            ChapterTable
                .selectAll()
                .where { ChapterTable.manga eq mangaId }
                .map { Triple(it[ChapterTable.id].value, it[ChapterTable.chapter_number], it[ChapterTable.url]) }
                .sortedBy { it.second }
        }

    private fun chapterId(
        mangaId: Int,
        number: Float,
    ) = chapters(mangaId).first { it.second == number }.first

    private fun imageTag(
        mangaId: Int,
        chapterId: Int,
    ): String =
        runBlocking {
            val (stream, mime) = PageService.getPageImage(mangaId = mangaId, chapterId = chapterId, index = 0)
            assertEquals("image/png", mime)
            stream.use { String(it.readBytes().drop(png.size).toByteArray()) }
        }

    @Test
    fun `chapters of active merged sources are added by chapter number and reserves add none`() {
        register(FakeSource(PRIMARY, baseUrl, "main", listOf(1, 2)))
        register(FakeSource(EXTRA, baseUrl, "extra", listOf(2, 3)))
        register(FakeSource(RESERVE, baseUrl, "reserve", listOf(4)))
        val mangaId = newManga("agg", PRIMARY)
        MergedSources.replace(
            mangaId,
            listOf(
                MergedSourceDto(EXTRA.toString(), "/extra-entry", updatesEnabled = true),
                MergedSourceDto(RESERVE.toString(), "/reserve-entry", updatesEnabled = false),
            ),
        )

        runBlocking { Manga.updateMangaAndChapters(mangaId, updateManga = false, updateChapters = true) }

        // 1, 2 from the main source; 3 from the extra source; chapter 2 is not duplicated; 4 (reserve) is absent
        assertEquals(listOf(1f, 2f, 3f), chapters(mangaId).map { it.second })
        assertNull(MergedSources.routeFor(chapterId(mangaId, 1f)))
        assertNull(MergedSources.routeFor(chapterId(mangaId, 2f)))
        val route = MergedSources.routeFor(chapterId(mangaId, 3f))
        assertNotNull(route)
        assertEquals(EXTRA, route.sourceId)
        assertTrue(route.isExtra)

        // a second refresh keeps the list stable
        runBlocking { Manga.updateMangaAndChapters(mangaId, updateManga = false, updateChapters = true) }
        assertEquals(listOf(1f, 2f, 3f), chapters(mangaId).map { it.second })
        assertEquals(EXTRA, MergedSources.routeFor(chapterId(mangaId, 3f))?.sourceId)
    }

    @Test
    fun `pages and images of an extra chapter come from the merged source`() {
        register(FakeSource(PRIMARY, baseUrl, "main", listOf(1)))
        register(FakeSource(EXTRA, baseUrl, "extra", listOf(2)))
        val mangaId = newManga("extra-pages", PRIMARY)
        MergedSources.replace(mangaId, listOf(MergedSourceDto(EXTRA.toString(), "/e")))
        runBlocking { Manga.updateMangaAndChapters(mangaId, updateManga = false, updateChapters = true) }

        val own = chapterId(mangaId, 1f)
        val extra = chapterId(mangaId, 2f)
        runBlocking {
            refreshChapterPageList(mangaId, own)
            refreshChapterPageList(mangaId, extra)
        }

        assertEquals("main", imageTag(mangaId, own))
        assertEquals("extra", imageTag(mangaId, extra))
    }

    @Test
    fun `a reserve answers when the main source fails, and the route is dropped once it recovers`() {
        val main = FakeSource(DOWN, baseUrl, "main", listOf(5), failPages = true)
        register(main)
        register(FakeSource(RESERVE, baseUrl, "reserve", listOf(5)))
        val mangaId = newManga("fallback", DOWN)
        // chapters of the main source are known (its page list is what fails)
        runBlocking { Manga.updateMangaAndChapters(mangaId, updateManga = false, updateChapters = true) }
        val chapter = chapterId(mangaId, 5f)

        // without any link the failure is not hidden
        val failure = runCatching { runBlocking { refreshChapterPageList(mangaId, chapter) } }.exceptionOrNull()
        assertNotNull(failure, "the main source error must surface when no reserve is linked")
        assertNull(MergedSources.routeFor(chapter))

        // with a reserve linked, the reserve serves the same chapter number
        MergedSources.replace(mangaId, listOf(MergedSourceDto(RESERVE.toString(), "/r", updatesEnabled = false)))
        val pageCount = runBlocking { refreshChapterPageList(mangaId, chapter) }
        assertEquals(2, pageCount)
        val route = MergedSources.routeFor(chapter)
        assertNotNull(route)
        assertEquals(RESERVE, route.sourceId)
        assertEquals(false, route.isExtra)
        assertEquals("reserve", imageTag(mangaId, chapter))
        // the reserve's chapter was never added to the list
        assertEquals(1, chapters(mangaId).size)

        // the main source recovers: the fallback route goes away
        main.failPages = false
        runBlocking { refreshChapterPageList(mangaId, chapter) }
        assertNull(MergedSources.routeFor(chapter))
        val pages =
            transaction {
                PageTable
                    .selectAll()
                    .where { PageTable.chapter eq EntityID(chapter, ChapterTable) }
                    .map { it[PageTable.imageUrl] }
            }
        assertTrue(pages.all { it?.contains("/img/main/") == true }, "pages must come from the main source again: $pages")
    }

    @Test
    fun `a source that is not an http source is never used as extra or reserve`() {
        // id 0 is the local source (or nothing at all): neither can serve images, so both are skipped
        register(FakeSource(PRIMARY, baseUrl, "main", listOf(1)))
        val mangaId = newManga("nonhttp", PRIMARY)
        MergedSources.replace(mangaId, listOf(MergedSourceDto("0", "Some Dir"), MergedSourceDto("999999", "/x")))

        runBlocking { Manga.updateMangaAndChapters(mangaId, updateManga = false, updateChapters = true) }

        assertEquals(listOf(1f), chapters(mangaId).map { it.second })
        assertNull(
            runBlocking { MergedSources.fallbackPageList(mangaId, "nonhttp", PRIMARY, 1f) },
        )
    }

    private companion object {
        const val PRIMARY = 91_001L
        const val EXTRA = 91_002L
        const val DOWN = 91_003L
        const val RESERVE = 91_004L
    }
}
