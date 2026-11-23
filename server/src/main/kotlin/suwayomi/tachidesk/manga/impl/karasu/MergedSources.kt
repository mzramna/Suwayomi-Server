package suwayomi.tachidesk.manga.impl.karasu

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.chapter.ChapterRecognition
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.util.source.GetSource.getSourceOrNull
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MergedChapterRouteTable
import suwayomi.tachidesk.manga.model.table.MergedMangaTable

@Serializable
data class MergedSourceDto(
    val sourceId: String,
    val url: String,
    val priority: Int = 0,
    val updatesEnabled: Boolean = true,
)

@Serializable
data class MangaRefDto(
    val sourceId: String,
    val url: String,
)

@Serializable
data class MergedGroupDto(
    val mangaId: Int,
    val primary: MangaRefDto,
    val sources: List<MergedSourceDto>,
)

/**
 * Merged sources engine.
 *
 * - Aggregation: chapters of linked sources with updates enabled are added to the manga's chapter
 *   list when the main source does not have that chapter number (priority decides who wins).
 * - Fallback: when the main source fails to give the pages of a chapter, linked sources (reserves
 *   included) are asked for the same chapter number.
 * - [MergedChapterRouteTable] remembers which source has to be used for the pages of a chapter.
 */
object MergedSources {
    // defaults must be written: the contract's MergedSource always carries priority and updatesEnabled
    private val logJson = Json { encodeDefaults = true }

    private val logger = KotlinLogging.logger {}

    class ChapterRoute(
        val sourceId: Long,
        val isExtra: Boolean,
    )

    data class Aggregated(
        val chapters: List<SChapter>,
        /** chapter url -> source it came from, only for chapters added from merged sources */
        val extraUrlToSource: Map<String, Long>,
    )

    private data class Link(
        val sourceId: Long,
        val url: String,
        val priority: Int,
        val updatesEnabled: Boolean,
    )

    private fun links(mangaId: Int): List<Link> =
        transaction {
            MergedMangaTable
                .selectAll()
                .where { MergedMangaTable.manga eq mangaId }
                .orderBy(MergedMangaTable.priority to SortOrder.ASC, MergedMangaTable.id to SortOrder.ASC)
                .map {
                    Link(
                        it[MergedMangaTable.sourceId],
                        it[MergedMangaTable.url],
                        it[MergedMangaTable.priority],
                        it[MergedMangaTable.updatesEnabled],
                    )
                }
        }

    private fun toDto(link: Link) = MergedSourceDto(link.sourceId.toString(), link.url, link.priority, link.updatesEnabled)

    fun findMangaId(
        sourceId: Long,
        url: String,
    ): Int? =
        transaction {
            MangaTable
                .selectAll()
                .where { (MangaTable.sourceReference eq sourceId) and (MangaTable.url eq url) }
                .firstOrNull()
                ?.get(MangaTable.id)
                ?.value
        }

    fun getGroup(mangaId: Int): MergedGroupDto? {
        val primary =
            transaction {
                MangaTable.selectAll().where { MangaTable.id eq mangaId }.firstOrNull()
            } ?: return null
        return MergedGroupDto(
            mangaId = mangaId,
            primary = MangaRefDto(primary[MangaTable.sourceReference].toString(), primary[MangaTable.url]),
            sources = links(mangaId).map(::toDto),
        )
    }

    fun getAllGroups(): List<MergedGroupDto> {
        val ids = transaction { MergedMangaTable.selectAll().map { it[MergedMangaTable.manga].value }.distinct() }
        return ids.mapNotNull { getGroup(it) }
    }

    /** Replaces every link of the manga. Priorities are normalized to the order of the list. */
    fun replace(
        mangaId: Int,
        sources: List<MergedSourceDto>,
    ): MergedGroupDto? {
        val primary =
            transaction { MangaTable.selectAll().where { MangaTable.id eq mangaId }.firstOrNull() } ?: return null
        val primarySource = primary[MangaTable.sourceReference]

        val cleaned =
            sources
                .mapNotNull { dto ->
                    val id = dto.sourceId.toLongOrNull() ?: return@mapNotNull null
                    if (id == primarySource || dto.url.isBlank()) return@mapNotNull null
                    id to dto
                }.distinctBy { it.first }
                .sortedBy { it.second.priority }

        val before = if (KarasuRecorder.enabled()) links(mangaId).map(::toDto) else null
        transaction {
            MergedMangaTable.deleteWhere { MergedMangaTable.manga eq mangaId }
            MergedMangaTable.batchInsert(cleaned.withIndex().toList()) { (index, pair) ->
                this[MergedMangaTable.manga] = mangaId
                this[MergedMangaTable.sourceId] = pair.first
                this[MergedMangaTable.url] = pair.second.url
                this[MergedMangaTable.priority] = index
                this[MergedMangaTable.updatesEnabled] = pair.second.updatesEnabled
            }
            // routes that pointed to a source that is no longer linked are meaningless now
            val linkedIds = cleaned.map { it.first }
            MergedChapterRouteTable.deleteWhere {
                (MergedChapterRouteTable.chapter inList chapterIdsOf(mangaId)) and
                    (MergedChapterRouteTable.sourceId notInList linkedIds)
            }
        }
        val group = getGroup(mangaId)
        if (before != null && group != null && before != group.sources) {
            // the whole list after the change, keyed by the primary manga: the app has no server ids
            KarasuOperations.record(
                KarasuEntity.MERGED,
                KarasuOperations.mergedKey(primarySource, primary[MangaTable.url]),
                KarasuAction.SET,
                buildJsonObject {
                    put(
                        "sources",
                        logJson.encodeToJsonElement(ListSerializer(MergedSourceDto.serializer()), group.sources),
                    )
                },
                skipIfSameAsLast = false,
            )
        }
        return group
    }

    private fun chapterIdsOf(mangaId: Int): List<Int> =
        transaction {
            ChapterTable
                .select(ChapterTable.id)
                .where { ChapterTable.manga eq mangaId }
                .map { it[ChapterTable.id].value }
        }

    fun routeFor(chapterId: Int): ChapterRoute? =
        transaction {
            MergedChapterRouteTable
                .selectAll()
                .where { MergedChapterRouteTable.chapter eq chapterId }
                .firstOrNull()
                ?.let { ChapterRoute(it[MergedChapterRouteTable.sourceId], it[MergedChapterRouteTable.isExtra]) }
        }

    fun setRoute(
        chapterId: Int,
        sourceId: Long,
        isExtra: Boolean,
    ) {
        transaction {
            val updated =
                MergedChapterRouteTable.update({ MergedChapterRouteTable.chapter eq chapterId }) {
                    it[MergedChapterRouteTable.sourceId] = sourceId
                    it[MergedChapterRouteTable.isExtra] = isExtra
                }
            if (updated == 0) {
                MergedChapterRouteTable.batchInsert(listOf(chapterId)) {
                    this[MergedChapterRouteTable.chapter] = it
                    this[MergedChapterRouteTable.sourceId] = sourceId
                    this[MergedChapterRouteTable.isExtra] = isExtra
                }
            }
        }
    }

    /** Called when the main source answered fine: a leftover fallback route must not be used anymore. */
    fun clearFallbackRoute(chapterId: Int) {
        transaction {
            MergedChapterRouteTable.deleteWhere {
                (MergedChapterRouteTable.chapter eq chapterId) and (MergedChapterRouteTable.isExtra eq false)
            }
        }
    }

    private fun chapterNumberOf(
        mangaTitle: String,
        chapter: SChapter,
    ): Float =
        ChapterRecognition
            .parseChapterNumber(mangaTitle, chapter.name, chapter.chapter_number.toDouble())
            .toFloat()

    /**
     * Only HTTP sources can be used as extra/reserve: their pages are served by [Page.getPageImage],
     * which needs an HttpSource. Anything else (local source, missing extension) is skipped.
     */
    private suspend fun usableSource(sourceId: Long): HttpSource? = getSourceOrNull(sourceId) as? HttpSource

    private suspend fun fetchChapters(
        link: Link,
        mangaTitle: String,
    ): List<SChapter>? {
        val source = usableSource(link.sourceId) ?: return null
        val sManga =
            SManga.create().apply {
                url = link.url
                title = mangaTitle
            }
        return try {
            source.getMangaUpdate(sManga, emptyList(), fetchDetails = false, fetchChapters = true).chapters
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Merged source ${link.sourceId} failed to list chapters of ${link.url}" }
            null
        }
    }

    /**
     * Adds chapters of the linked sources that have updates enabled and whose chapter number the
     * main source (or a source with higher priority) does not have.
     */
    suspend fun aggregate(
        mangaId: Int,
        mangaTitle: String,
        primaryChapters: List<SChapter>,
    ): Aggregated {
        val active = links(mangaId).filter { it.updatesEnabled }
        if (active.isEmpty()) return Aggregated(primaryChapters, emptyMap())

        val seenNumbers = HashSet<Float>()
        val seenUrls = HashSet<String>()
        primaryChapters.forEach { chapter ->
            seenUrls += chapter.url
            chapterNumberOf(mangaTitle, chapter).takeIf { it >= 0f }?.let { seenNumbers += it }
        }

        val extras = ArrayList<SChapter>()
        val extraSources = LinkedHashMap<String, Long>()
        for (link in active) {
            val chapters = fetchChapters(link, mangaTitle) ?: continue
            for (chapter in chapters) {
                val number = chapterNumberOf(mangaTitle, chapter)
                if (number < 0f || number in seenNumbers || chapter.url in seenUrls) continue
                seenNumbers += number
                seenUrls += chapter.url
                extras += chapter
                extraSources[chapter.url] = link.sourceId
            }
        }
        return Aggregated(primaryChapters + extras, extraSources)
    }

    /** Stores the routes of the extra chapters after the chapter list was written to the database. */
    fun recordExtraRoutes(
        mangaId: Int,
        extraUrlToSource: Map<String, Long>,
    ) {
        transaction {
            val chapters =
                ChapterTable
                    .select(ChapterTable.id, ChapterTable.url)
                    .where { ChapterTable.manga eq mangaId }
                    .map { it[ChapterTable.id].value to it[ChapterTable.url] }

            // extras that no longer exist (or were taken over by the main source) lose their route
            val staleIds =
                chapters
                    .filter { (_, url) -> url !in extraUrlToSource }
                    .map { it.first }
            if (staleIds.isNotEmpty()) {
                MergedChapterRouteTable.deleteWhere {
                    (MergedChapterRouteTable.chapter inList staleIds) and (MergedChapterRouteTable.isExtra eq true)
                }
            }

            chapters
                .filter { (_, url) -> url in extraUrlToSource }
                .forEach { (chapterId, url) -> setRoute(chapterId, extraUrlToSource.getValue(url), isExtra = true) }
        }
    }

    /**
     * The main source failed: ask the linked sources (priority order, reserves included) for the
     * same chapter number. Returns the source that answered and its pages.
     */
    suspend fun fallbackPageList(
        mangaId: Int,
        mangaTitle: String,
        primarySourceId: Long,
        chapterNumber: Float,
    ): Pair<Long, List<Page>>? {
        if (chapterNumber < 0f) return null
        for (link in links(mangaId)) {
            if (link.sourceId == primarySourceId) continue
            val source = usableSource(link.sourceId) ?: continue
            try {
                val chapter =
                    fetchChapters(link, mangaTitle)
                        ?.firstOrNull { chapterNumberOf(mangaTitle, it) == chapterNumber }
                        ?: continue
                val pages = source.getPageList(chapter)
                if (pages.isNotEmpty()) {
                    logger.info { "Chapter $chapterNumber of '$mangaTitle' served by merged source ${link.sourceId}" }
                    return link.sourceId to pages
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Merged source ${link.sourceId} failed as fallback for chapter $chapterNumber" }
            }
        }
        return null
    }
}
