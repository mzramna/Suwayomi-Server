package suwayomi.tachidesk.manga.impl.karasu

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** One entry as the plugin sees it (GET /shelf and the answer of POST /shelf). */
@Serializable
data class ShelfEntryDto(
    val chapterId: Long,
    val chapterUrl: String,
    val read: Boolean,
)

@Serializable
data class ShelfListDto(
    val entries: List<ShelfEntryDto>,
)

/** What the app sends in the `metadata` part of POST /shelf, already validated. */
data class ShelfMetadata(
    val chapterId: Long,
    val chapterUrl: String,
    val mangaTitle: String,
    val chapterName: String,
    val chapterNumber: Double,
    val sourceId: String,
)

/** What is kept in "<chapterId>.json": the metadata plus the state the plugin reports back. */
@Serializable
private data class ShelfRecord(
    val chapterId: Long,
    val chapterUrl: String,
    val mangaTitle: String = "",
    val chapterName: String = "",
    val chapterNumber: Double = 0.0,
    val sourceId: String = "",
    val read: Boolean = false,
    val sizeBytes: Long = 0,
    val savedAt: Long = 0,
) {
    fun toDto() = ShelfEntryDto(chapterId, chapterUrl, read)
}

/**
 * The "shelf" of the Yatagarasu (KOReader) integration: CBZs the Karasu app pushes to be read on the e-reader, and the
 * `read` flag the KOReader plugin reports back. Everything lives in "<dataRoot>/karasu-shelf" as two files per chapter,
 * "<chapterId>.cbz" and "<chapterId>.json". The chapterId is the app's LOCAL id, unrelated to the server's chapters, and
 * the file CBZ is whatever the app prepared for the device, so nothing here touches the downloads of the server.
 *
 * A file name is only ever built from a [Long], never from text sent by a client, so there is no path traversal.
 * These uploads are not recorded in the operation log.
 */
object KarasuShelf {
    const val CBZ_MIME = "application/vnd.comicbook+zip"
    const val MAX_METADATA_BYTES = 64 * 1024

    private const val PART_SUFFIX = ".part"
    private const val STALE_PART_MS = 24L * 60 * 60 * 1000
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }
    private val idPattern = Regex("^[0-9]{1,18}$")
    private val recordName = Regex("^[0-9]{1,18}\\.json$")

    // Serializes the changes of the small files (record + archive swap); the copy of the upload itself runs outside of it.
    private val lock = Any()

    private fun dir(): File = File(Injekt.get<ApplicationDirs>().dataRoot, "karasu-shelf").also { it.mkdirs() }

    private fun archiveFile(id: Long) = File(dir(), "$id.cbz")

    private fun recordFile(id: Long) = File(dir(), "$id.json")

    /** Only plain decimal digits are an id: "../x", "-1", "+1", "1.5" and the like are refused before any file is touched. */
    fun parseId(raw: String): Long? = raw.takeIf { idPattern.matches(it) }?.toLongOrNull()

    /**
     * Validates the `metadata` JSON. Returns null when it is not an object or lacks a usable chapterId / chapterUrl. The
     * other fields are descriptive: missing ones become empty, and sourceId may come as a string or as a number.
     */
    fun parseMetadata(text: String): ShelfMetadata? {
        val obj: JsonObject = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null

        fun primitive(name: String): JsonPrimitive? =
            (obj[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }

        val chapterId = primitive("chapterId")?.content?.let(::parseId) ?: return null
        val chapterUrl = primitive("chapterUrl")?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return null
        return ShelfMetadata(
            chapterId = chapterId,
            chapterUrl = chapterUrl,
            mangaTitle = primitive("mangaTitle")?.takeIf { it.isString }?.content.orEmpty(),
            chapterName = primitive("chapterName")?.takeIf { it.isString }?.content.orEmpty(),
            chapterNumber = primitive("chapterNumber")?.doubleOrNull ?: 0.0,
            sourceId = primitive("sourceId")?.content.orEmpty(),
        )
    }

    private fun readRecord(id: Long): ShelfRecord? {
        val file = recordFile(id)
        if (!file.isFile) return null
        return try {
            json.decodeFromString<ShelfRecord>(file.readText()).takeIf { it.chapterId == id }
        } catch (e: Exception) {
            logger.warn(e) { "Karasu shelf: unreadable record ${file.name}, ignored" }
            null
        }
    }

    private fun writeRecord(record: ShelfRecord) {
        val partial = File(dir(), "${record.chapterId}.json$PART_SUFFIX")
        partial.writeText(json.encodeToString(ShelfRecord.serializer(), record))
        Files.move(partial.toPath(), recordFile(record.chapterId).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Entries ordered by chapterId. An entry exists only when both of its files do; "*.part" never counts. */
    fun list(): List<ShelfEntryDto> =
        dir()
            .listFiles { f -> f.isFile && recordName.matches(f.name) }
            .orEmpty()
            .mapNotNull { f ->
                val id = f.name.removeSuffix(".json").toLongOrNull() ?: return@mapNotNull null
                readRecord(id)?.takeIf { archiveFile(id).isFile }
            }.sortedBy { it.chapterId }
            .map { it.toDto() }

    /**
     * Stores (or replaces) the chapter. Replacing resets `read` to false. The content is written to a ".part" file and
     * renamed at the end, so a failed or interrupted upload leaves the previous version untouched.
     * @throws IllegalArgumentException when the content is empty.
     */
    fun save(
        metadata: ShelfMetadata,
        content: InputStream,
    ): ShelfEntryDto {
        val id = metadata.chapterId
        purgeStaleParts()
        val partial = File(dir(), "$id.cbz.${UUID.randomUUID()}$PART_SUFFIX")
        try {
            val size = content.use { Files.copy(it, partial.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            require(size > 0) { "The file is empty" }
            val record =
                ShelfRecord(
                    chapterId = id,
                    chapterUrl = metadata.chapterUrl,
                    mangaTitle = metadata.mangaTitle,
                    chapterName = metadata.chapterName,
                    chapterNumber = metadata.chapterNumber,
                    sourceId = metadata.sourceId,
                    read = false,
                    sizeBytes = size,
                    savedAt = System.currentTimeMillis(),
                )
            synchronized(lock) {
                // the entry is hidden while its archive is swapped, so no reader sees the new file with the old flag
                recordFile(id).delete()
                Files.move(partial.toPath(), archiveFile(id).toPath(), StandardCopyOption.REPLACE_EXISTING)
                writeRecord(record)
            }
            return record.toDto()
        } finally {
            partial.delete()
        }
    }

    /** Removes the entry; true if there was one. Asking for an id that does not exist is not an error. */
    fun delete(id: Long): Boolean =
        synchronized(lock) {
            val hadRecord = recordFile(id).delete()
            val hadArchive = archiveFile(id).delete()
            hadRecord || hadArchive
        }

    /** The archive of an entry, or null when the chapter is not on the shelf. */
    fun archive(id: Long): File? =
        synchronized(lock) {
            archiveFile(id).takeIf { it.isFile && readRecord(id) != null }
        }

    /** Sets the flag the KOReader plugin reports; false when the chapter is not on the shelf. */
    fun setRead(
        id: Long,
        read: Boolean,
    ): Boolean =
        synchronized(lock) {
            val record = readRecord(id)?.takeIf { archiveFile(id).isFile } ?: return@synchronized false
            if (record.read != read) writeRecord(record.copy(read = read))
            true
        }

    // Leftovers of uploads that died with the process; a live upload keeps touching its file.
    private fun purgeStaleParts() {
        val limit = System.currentTimeMillis() - STALE_PART_MS
        dir()
            .listFiles { f -> f.isFile && f.name.endsWith(PART_SUFFIX) && f.lastModified() < limit }
            .orEmpty()
            .forEach { it.delete() }
    }
}
