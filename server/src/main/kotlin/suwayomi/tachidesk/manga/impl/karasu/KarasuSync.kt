package suwayomi.tachidesk.manga.impl.karasu

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.impl.ChapterDownloadHelper
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.KarasuDeletionTable
import suwayomi.tachidesk.manga.model.table.KarasuSettingTable
import suwayomi.tachidesk.manga.model.table.MangaTable

@Serializable
data class PolicyDto(
    val downloadPolicy: String,
)

@Serializable
data class DeletionRequestDto(
    val chapterIds: List<Int>,
    val deleteFromServer: Boolean = false,
)

@Serializable
data class QueuedDto(
    val queued: Int,
)

@Serializable
data class HistoryEntryDto(
    val chapterId: Int,
    /** Epoch seconds of the last read; 0 removes the chapter from the history. */
    val lastReadAt: Long,
)

@Serializable
data class HistoryRequestDto(
    val entries: List<HistoryEntryDto> = emptyList(),
)

@Serializable
data class UpdatedDto(
    val updated: Int,
)

@Serializable
data class DeletionDto(
    val id: Int,
    val sourceId: String,
    val mangaUrl: String,
    val chapterUrl: String,
    val chapterNumber: Float,
    val requestedAt: Long,
)

@Serializable
data class ChapterLookupDto(
    val chapterId: Int,
    val mangaId: Int,
    val downloaded: Boolean,
    val sizeBytes: Long,
)

@Serializable
data class MangaLookupChapterDto(
    val chapterId: Int,
    val url: String,
    val chapterNumber: Float,
    val downloaded: Boolean,
    val sizeBytes: Long,
)

@Serializable
data class MangaLookupDto(
    val mangaId: Int,
    val title: String,
    val chapters: List<MangaLookupChapterDto>,
)

/** Download policy, remote deletions and lookups used by the Karasu client. */
object KarasuSync {
    const val POLICY_KEY = "download_policy"
    val POLICIES = setOf("SERVER_ONLY", "SERVER_AND_CLIENTS")

    fun getPolicy(): PolicyDto =
        transaction {
            KarasuSettingTable
                .selectAll()
                .where { KarasuSettingTable.key eq POLICY_KEY }
                .firstOrNull()
                ?.get(KarasuSettingTable.value)
        }.let { PolicyDto(it?.takeIf { v -> v in POLICIES } ?: "SERVER_ONLY") }

    fun setPolicy(policy: String): PolicyDto {
        require(policy in POLICIES) { "downloadPolicy must be one of $POLICIES" }
        transaction {
            val updated = KarasuSettingTable.update({ KarasuSettingTable.key eq POLICY_KEY }) { it[value] = policy }
            if (updated == 0) {
                KarasuSettingTable.insert {
                    it[key] = POLICY_KEY
                    it[value] = policy
                }
            }
        }
        return PolicyDto(policy)
    }

    /** Registers tombstones for the chapters and optionally deletes the server copy. */
    suspend fun requestDeletions(
        userId: Int,
        request: DeletionRequestDto,
    ): QueuedDto {
        val now = System.currentTimeMillis()
        val rows =
            transaction {
                (ChapterTable innerJoin MangaTable)
                    .selectAll()
                    .where { ChapterTable.id inList request.chapterIds }
                    .map {
                        listOf(
                            it[MangaTable.sourceReference],
                            it[MangaTable.url],
                            it[ChapterTable.url],
                            it[ChapterTable.chapter_number],
                        )
                    }
            }
        transaction {
            KarasuDeletionTable.batchInsert(rows) { row ->
                this[KarasuDeletionTable.sourceId] = row[0] as Long
                this[KarasuDeletionTable.mangaUrl] = row[1] as String
                this[KarasuDeletionTable.chapterUrl] = row[2] as String
                this[KarasuDeletionTable.chapterNumber] = row[3] as Float
                this[KarasuDeletionTable.requestedAt] = now
            }
        }
        if (request.deleteFromServer) {
            Chapter.deleteDownloadedChapters(userId, request.chapterIds)
        }
        return QueuedDto(rows.size)
    }

    /**
     * Sets the "last read" time that makes a chapter show up in the history, which no other API can do
     * (it is only ever written as "now" when the read page changes). 0 takes the chapter out of it.
     * Chapters that do not exist are ignored.
     */
    fun setHistory(
        userId: Int,
        entries: List<HistoryEntryDto>,
    ): UpdatedDto {
        if (entries.isEmpty()) return UpdatedDto(0)
        val known =
            transaction {
                ChapterTable
                    .select(ChapterTable.id)
                    .where { ChapterTable.id inList entries.map { it.chapterId } }
                    .map { it[ChapterTable.id].value }
                    .toSet()
            }
        val valid = entries.filter { it.chapterId in known }
        transaction {
            valid.forEach { entry ->
                ChapterUserTable.upsert(ChapterUserTable.user, ChapterUserTable.chapter) {
                    it[ChapterUserTable.user] = userId
                    it[ChapterUserTable.chapter] = entry.chapterId
                    it[ChapterUserTable.lastReadAt] = entry.lastReadAt.coerceAtLeast(0)
                }
            }
        }
        return UpdatedDto(valid.size)
    }

    fun listDeletions(afterId: Int): List<DeletionDto> =
        transaction {
            KarasuDeletionTable
                .selectAll()
                .where { KarasuDeletionTable.id greater afterId }
                .orderBy(KarasuDeletionTable.id to SortOrder.ASC)
                .limit(1000)
                .map {
                    DeletionDto(
                        id = it[KarasuDeletionTable.id].value,
                        sourceId = it[KarasuDeletionTable.sourceId].toString(),
                        mangaUrl = it[KarasuDeletionTable.mangaUrl],
                        chapterUrl = it[KarasuDeletionTable.chapterUrl],
                        chapterNumber = it[KarasuDeletionTable.chapterNumber],
                        requestedAt = it[KarasuDeletionTable.requestedAt],
                    )
                }
        }

    fun lookupManga(
        sourceId: Long,
        url: String,
    ): MangaLookupDto? =
        transaction {
            val manga =
                MangaTable
                    .selectAll()
                    .where { (MangaTable.sourceReference eq sourceId) and (MangaTable.url eq url) }
                    .firstOrNull() ?: return@transaction null
            val mangaId = manga[MangaTable.id].value
            MangaLookupDto(
                mangaId = mangaId,
                title = manga[MangaTable.title],
                chapters =
                    ChapterTable
                        .selectAll()
                        .where { ChapterTable.manga eq mangaId }
                        .orderBy(ChapterTable.sourceOrder to SortOrder.DESC)
                        .map {
                            val id = it[ChapterTable.id].value
                            val downloaded = it[ChapterTable.isDownloaded]
                            MangaLookupChapterDto(
                                chapterId = id,
                                url = it[ChapterTable.url],
                                chapterNumber = it[ChapterTable.chapter_number],
                                downloaded = downloaded,
                                sizeBytes = -1L,
                            )
                        },
            )
        }

    /** [ChapterLookupDto.sizeBytes] is the archive size, or -1 when the chapter is not downloaded. */
    suspend fun lookupChapter(
        sourceId: Long,
        mangaUrl: String,
        chapterUrl: String,
    ): ChapterLookupDto? {
        val found =
            transaction {
                (ChapterTable innerJoin MangaTable)
                    .selectAll()
                    .where {
                        (MangaTable.sourceReference eq sourceId) and
                            (MangaTable.url eq mangaUrl) and
                            (ChapterTable.url eq chapterUrl)
                    }.firstOrNull()
                    ?.let { Triple(it[ChapterTable.id].value, it[MangaTable.id].value, it[ChapterTable.isDownloaded]) }
            } ?: return null
        val (chapterId, mangaId, downloaded) = found
        val size =
            if (downloaded) {
                runCatching { ChapterDownloadHelper.getChapterArchiveSize(mangaId, chapterId) }.getOrDefault(-1L)
            } else {
                -1L
            }
        return ChapterLookupDto(chapterId, mangaId, downloaded, size)
    }
}
