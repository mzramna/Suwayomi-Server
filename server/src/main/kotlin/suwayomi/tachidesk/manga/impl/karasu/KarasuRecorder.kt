package suwayomi.tachidesk.manga.impl.karasu

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.MangaTable

/**
 * Helpers for the write points that feed the operation log with EFFECTIVE changes only: the state is read
 * before and after the write and only the difference is logged. Every helper does nothing (and runs no query)
 * when the current request is not logged by the server, see [KarasuOperations.record].
 */
object KarasuRecorder {
    private const val CHUNK = 1000

    /** False when the request carries `X-Karasu-Device`: the app publishes its own log then. */
    fun enabled(): Boolean = KarasuRequestContext.device() == null

    // -- categories -----------------------------------------------------------------------------------------

    fun categoryCreated(
        categoryId: Int,
        name: String,
    ) = KarasuOperations.record(
        KarasuEntity.CATEGORY,
        KarasuOperations.categoryKey(categoryId),
        KarasuAction.SET,
        KarasuOperations.nameValue(name),
        skipIfSameAsLast = false,
    )

    fun categoryRenamed(
        categoryId: Int,
        newName: String,
    ) = categoryCreated(categoryId, newName)

    fun categoryDeleted(categoryId: Int) =
        KarasuOperations.record(
            KarasuEntity.CATEGORY,
            KarasuOperations.categoryKey(categoryId),
            KarasuAction.REMOVE,
            skipIfSameAsLast = false,
        )

    // -- manga <-> category links ---------------------------------------------------------------------------

    /** Links (manga id, category id) of the user that exist now, to be compared after a delete. */
    fun existingLinks(
        userId: Int,
        mangaIds: List<Int>,
        categoryIds: List<Int>? = null,
    ): List<Pair<Int, Int>> {
        if (!enabled() || mangaIds.isEmpty() || categoryIds?.isEmpty() == true) return emptyList()
        return transaction {
            mangaIds.chunked(CHUNK).flatMap { chunk ->
                CategoryMangaTable
                    .selectAll()
                    .where {
                        val base = (CategoryMangaTable.user eq userId) and (CategoryMangaTable.manga inList chunk)
                        if (categoryIds != null) base and (CategoryMangaTable.category inList categoryIds) else base
                    }.map { it[CategoryMangaTable.manga].value to it[CategoryMangaTable.category].value }
            }
        }
    }

    /** Links (manga id, category id) that were just added (SET) or removed (REMOVE). */
    fun linksChanged(
        links: Collection<Pair<Int, Int>>,
        action: KarasuAction,
    ) {
        if (!enabled() || links.isEmpty()) return
        val refs = mangaRefs(links.map { it.first }.distinct())
        val operations =
            links.mapNotNull { (mangaId, categoryId) ->
                val (sourceId, url) = refs[mangaId] ?: return@mapNotNull null
                PendingOperation(
                    KarasuEntity.CATEGORY_LINK,
                    KarasuOperations.linkKey(sourceId, url, categoryId),
                    action,
                )
            }
        KarasuOperations.recordAll(operations, skipIfSameAsLast = false)
    }

    private fun mangaRefs(mangaIds: List<Int>): Map<Int, Pair<Long, String>> =
        transaction {
            mangaIds.chunked(CHUNK).flatMap { chunk ->
                MangaTable
                    .select(MangaTable.id, MangaTable.sourceReference, MangaTable.url)
                    .where { MangaTable.id inList chunk }
                    .map { it[MangaTable.id].value to (it[MangaTable.sourceReference] to it[MangaTable.url]) }
            }
        }.toMap()

    // -- chapter progress -----------------------------------------------------------------------------------

    data class Progress(
        val read: Boolean,
        val lastPageRead: Int,
    )

    /** Progress of [chapterIds] for the user; chapters without a row are (unread, page 0). Null when not logging. */
    fun progressBefore(
        userId: Int,
        chapterIds: Collection<Int>,
    ): Map<Int, Progress>? = if (enabled() && chapterIds.isNotEmpty()) readProgress(userId, chapterIds) else null

    private fun readProgress(
        userId: Int,
        chapterIds: Collection<Int>,
    ): Map<Int, Progress> {
        val rows =
            transaction {
                chapterIds.distinct().chunked(CHUNK).flatMap { chunk ->
                    ChapterUserTable
                        .select(ChapterUserTable.chapter, ChapterUserTable.isRead, ChapterUserTable.lastPageRead)
                        .where { (ChapterUserTable.user eq userId) and (ChapterUserTable.chapter inList chunk) }
                        .map {
                            it[ChapterUserTable.chapter].value to
                                Progress(it[ChapterUserTable.isRead], it[ChapterUserTable.lastPageRead])
                        }
                }
            }.toMap()
        return chapterIds.distinct().associateWith { rows[it] ?: Progress(false, 0) }
    }

    /** Logs a PROGRESS operation for each chapter whose (read, lastPageRead) differs from [before]. */
    fun progressChanged(
        userId: Int,
        before: Map<Int, Progress>?,
    ) {
        if (before == null || !enabled()) return
        val after = readProgress(userId, before.keys)
        val changed = after.filter { (id, progress) -> before[id] != progress }
        if (changed.isEmpty()) return
        val refs =
            transaction {
                changed.keys.chunked(CHUNK).flatMap { chunk ->
                    (ChapterTable innerJoin MangaTable)
                        .select(ChapterTable.id, ChapterTable.url, MangaTable.sourceReference, MangaTable.url)
                        .where { ChapterTable.id inList chunk }
                        .map {
                            Triple(
                                it[ChapterTable.id].value,
                                it[MangaTable.sourceReference],
                                it[MangaTable.url] to it[ChapterTable.url],
                            )
                        }
                }
            }
        val operations =
            refs.map { (chapterId, sourceId, urls) ->
                val progress = changed.getValue(chapterId)
                PendingOperation(
                    KarasuEntity.PROGRESS,
                    KarasuOperations.chapterKey("progress", sourceId, urls.first, urls.second),
                    KarasuAction.SET,
                    progressValue(progress),
                )
            }
        KarasuOperations.recordAll(operations, skipIfSameAsLast = false)
    }

    fun progressValue(progress: Progress): JsonObject =
        buildJsonObject {
            put("read", progress.read)
            put("lastPageRead", progress.lastPageRead)
        }
}
