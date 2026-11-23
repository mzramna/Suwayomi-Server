package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.AddTableMigration
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable

/** Tables for merged sources and the Karasu client sync (deletion tombstones, settings). */
@Suppress("ClassName", "unused")
class M0069_KarasuSync : AddTableMigration() {
    private class MergedMangaTable : IntIdTable("MergedManga") {
        val manga = reference("manga", MangaTable, ReferenceOption.CASCADE)
        val sourceId = long("source_id")
        val url = varchar("url", 2048)
        val priority = integer("priority").default(0)
        val updatesEnabled = bool("updates_enabled").default(true)

        init {
            uniqueIndex(manga, sourceId)
        }
    }

    private class MergedChapterRouteTable : IntIdTable("MergedChapterRoute") {
        val chapter = reference("chapter", ChapterTable, ReferenceOption.CASCADE).uniqueIndex()
        val sourceId = long("source_id")
        val isExtra = bool("is_extra").default(false)
    }

    private class KarasuDeletionTable : IntIdTable("KarasuDeletion") {
        val sourceId = long("source_id")
        val mangaUrl = varchar("manga_url", 2048)
        val chapterUrl = varchar("chapter_url", 2048)
        val chapterNumber = float("chapter_number").default(-1f)
        val requestedAt = long("requested_at")
    }

    private class KarasuSettingTable : IntIdTable("KarasuSetting") {
        val key = varchar("setting_key", 256).uniqueIndex()
        val value = varchar("setting_value", 4096)
    }

    override val tables: Array<Table>
        get() =
            arrayOf(
                MergedMangaTable(),
                MergedChapterRouteTable(),
                KarasuDeletionTable(),
                KarasuSettingTable(),
            )
}
