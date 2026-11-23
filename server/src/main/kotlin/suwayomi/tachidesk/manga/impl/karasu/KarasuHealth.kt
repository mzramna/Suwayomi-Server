package suwayomi.tachidesk.manga.impl.karasu

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.Serializable
import suwayomi.tachidesk.global.impl.About
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

@Serializable
data class DatabaseInfoDto(
    val type: String,
)

@Serializable
data class StorageInfoDto(
    val type: String,
    val path: String,
    val freeBytes: Long,
    val totalBytes: Long,
)

@Serializable
data class HealthDto(
    val status: String,
    val serverVersion: String,
    val apiVersion: Int,
    val database: DatabaseInfoDto,
    val storage: StorageInfoDto,
)

object KarasuHealth {
    const val API_VERSION = 1

    fun get(): HealthDto {
        val downloads = File(Injekt.get<ApplicationDirs>().downloadsRoot)
        val probe = generateSequence(downloads) { it.parentFile }.firstOrNull { it.exists() } ?: downloads
        return HealthDto(
            status = "ok",
            serverVersion = About.getAbout().version,
            apiVersion = API_VERSION,
            database = DatabaseInfoDto(serverConfig.databaseType.value.name),
            storage =
                StorageInfoDto(
                    type = if (isNetworkMount(downloads)) "nfs" else "local",
                    path = downloads.path,
                    freeBytes = probe.usableSpace,
                    totalBytes = probe.totalSpace,
                ),
        )
    }

    /** True when the directory lives on an NFS mount (read from /proc/mounts, Linux only). */
    fun isNetworkMount(dir: File): Boolean {
        val mounts = File("/proc/mounts")
        if (!mounts.canRead()) return false
        val path = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        val best =
            mounts
                .readLines()
                .mapNotNull { line ->
                    val parts = line.split(' ')
                    if (parts.size < 3) return@mapNotNull null
                    val mountPoint = parts[1].replace("\\040", " ")
                    if (path == mountPoint || path.startsWith(mountPoint.trimEnd('/') + "/")) mountPoint to parts[2] else null
                }.maxByOrNull { it.first.length }
        return best?.second?.startsWith("nfs") == true
    }
}
