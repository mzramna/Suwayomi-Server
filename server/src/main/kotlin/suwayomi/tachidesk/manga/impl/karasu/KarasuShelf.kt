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

/** Uma entrada como o plugin a enxerga (GET /shelf e a resposta do POST /shelf). */
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

/** O que o app envia na parte `metadata` do POST /shelf, já validado. */
data class ShelfMetadata(
    val chapterId: Long,
    val chapterUrl: String,
    val mangaTitle: String,
    val chapterName: String,
    val chapterNumber: Double,
    val sourceId: String,
)

/** O que fica em "<chapterId>.json": os metadados mais o estado que o plugin devolve. */
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
 * A "shelf" da integração Yatagarasu (KOReader): CBZs que o app Karasu envia para ler no e-reader e o `read` que o
 * plugin do KOReader devolve. Tudo fica em "<dataRoot>/karasu-shelf", dois arquivos por capítulo: "<chapterId>.cbz" e
 * "<chapterId>.json". O chapterId é o id LOCAL do app, sem relação com os capítulos do servidor, e o CBZ é o que o app
 * preparou para o aparelho; por isso nada aqui toca nos downloads do servidor.
 *
 * O nome de arquivo é sempre montado a partir de um [Long], nunca de texto enviado pelo cliente (sem path traversal).
 * Esses uploads não entram no log de operações.
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

    // Serializa a troca dos arquivos pequenos (registro + CBZ); a cópia do upload em si roda fora do lock.
    private val lock = Any()

    private fun dir(): File = File(Injekt.get<ApplicationDirs>().dataRoot, "karasu-shelf").also { it.mkdirs() }

    private fun archiveFile(id: Long) = File(dir(), "$id.cbz")

    private fun recordFile(id: Long) = File(dir(), "$id.json")

    /** Só dígitos decimais formam um id: "../x", "-1", "+1", "1.5" e afins são recusados antes de tocar em qualquer arquivo. */
    fun parseId(raw: String): Long? = raw.takeIf { idPattern.matches(it) }?.toLongOrNull()

    /**
     * Valida o JSON de `metadata`. Devolve null se não for um objeto ou se faltar chapterId / chapterUrl utilizáveis. Os
     * demais campos são descritivos: ausentes viram vazios, e sourceId pode vir como texto ou como número.
     */
    fun parseMetadata(text: String): ShelfMetadata? {
        val obj: JsonObject = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null

        fun primitive(name: String): JsonPrimitive? = (obj[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }

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

    /** Entradas ordenadas por chapterId. Só existe entrada com os dois arquivos; "*.part" nunca conta. */
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
     * Grava (ou substitui) o capítulo. Substituir zera `read`. O conteúdo vai para um ".part" e é renomeado ao final,
     * então um upload que falha ou é interrompido deixa a versão anterior intacta.
     * @throws IllegalArgumentException se o conteúdo for vazio.
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
                // a entrada some enquanto o CBZ é trocado, para ninguém ver o arquivo novo com a marca antiga
                recordFile(id).delete()
                Files.move(partial.toPath(), archiveFile(id).toPath(), StandardCopyOption.REPLACE_EXISTING)
                writeRecord(record)
            }
            return record.toDto()
        } finally {
            partial.delete()
        }
    }

    /** Remove a entrada; true se existia. Pedir um id inexistente não é erro. */
    fun delete(id: Long): Boolean =
        synchronized(lock) {
            val hadRecord = recordFile(id).delete()
            val hadArchive = archiveFile(id).delete()
            hadRecord || hadArchive
        }

    /** O CBZ de uma entrada, ou null se o capítulo não está na shelf. */
    fun archive(id: Long): File? =
        synchronized(lock) {
            archiveFile(id).takeIf { it.isFile && readRecord(id) != null }
        }

    /** Grava a marca que o plugin do KOReader informa; false se o capítulo não está na shelf. */
    fun setRead(
        id: Long,
        read: Boolean,
    ): Boolean =
        synchronized(lock) {
            val record = readRecord(id)?.takeIf { archiveFile(id).isFile } ?: return@synchronized false
            if (record.read != read) writeRecord(record.copy(read = read))
            true
        }

    // Restos de uploads que morreram junto com o processo; um upload vivo continua alterando o seu arquivo.
    private fun purgeStaleParts() {
        val limit = System.currentTimeMillis() - STALE_PART_MS
        dir()
            .listFiles { f -> f.isFile && f.name.endsWith(PART_SUFFIX) && f.lastModified() < limit }
            .orEmpty()
            .forEach { it.delete() }
    }
}
