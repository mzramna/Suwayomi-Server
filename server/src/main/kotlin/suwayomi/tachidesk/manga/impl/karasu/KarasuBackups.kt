package suwayomi.tachidesk.manga.impl.karasu

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.Serializable
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.util.SafePath
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class BackupInfoDto(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val device: String?,
)

/**
 * Backups (.tachibk) pushed by Karasu devices. Kept apart from the automated server backups, in
 * "<backupPath>/karasu". A backup is stored as "<epochMillis>__<device>__<original name>" so the
 * device and the time survive without a database; the file name is the id.
 */
object KarasuBackups {
    private const val SEPARATOR = "__"

    private fun dir(): File = File(Injekt.get<ApplicationDirs>().automatedBackupRoot, "karasu").also { it.mkdirs() }

    // buildValidFilename turns an empty name into "(invalid)", so blanks are kept empty on purpose
    private fun clean(value: String): String =
        value
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { SafePath.buildValidFilename(it).replace(SEPARATOR, "_") }
            .orEmpty()

    private fun toInfo(file: File): BackupInfoDto {
        val parts = file.name.split(SEPARATOR, limit = 3)
        val parsed = parts.size == 3 && parts[0].toLongOrNull() != null
        return BackupInfoDto(
            id = file.name,
            name = if (parsed) parts[2] else file.name,
            sizeBytes = file.length(),
            createdAt = if (parsed) parts[0].toLong() else file.lastModified(),
            device = if (parsed) parts[1].takeIf { it.isNotEmpty() } else null,
        )
    }

    fun list(): List<BackupInfoDto> =
        dir()
            .listFiles { f -> f.isFile && !f.name.endsWith(".part") }
            .orEmpty()
            .map(::toInfo)
            .sortedByDescending { it.createdAt }

    fun save(
        originalName: String,
        device: String?,
        content: InputStream,
    ): BackupInfoDto {
        val name = clean(originalName.ifBlank { "backup.tachibk" })
        val target = File(dir(), listOf(System.currentTimeMillis().toString(), clean(device.orEmpty()), name).joinToString(SEPARATOR))
        val partial = File(target.path + ".part")
        content.use { Files.copy(it, partial.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return toInfo(target)
    }

    /** Resolves an id to a file inside the backup directory only, never outside of it. */
    fun find(id: String): File? {
        if (id.contains('/') || id.contains('\\') || id.contains("..")) return null
        return File(dir(), id).takeIf { it.isFile && it.parentFile == dir() }
    }

    fun delete(id: String): Boolean = find(id)?.delete() ?: false
}
