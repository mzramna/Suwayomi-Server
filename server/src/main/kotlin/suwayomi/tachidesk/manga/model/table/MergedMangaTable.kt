package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/**
 * Extra sources linked to a manga ("merged sources"). The manga row itself stays on its original
 * source; each row here is another source (+ the manga url on it) that can add chapters
 * ([updatesEnabled] = true) or only act as a reserve for pages when the main source fails.
 */
object MergedMangaTable : IntIdTable() {
    val manga = reference("manga", MangaTable, ReferenceOption.CASCADE)
    val sourceId = long("source_id")
    val url = varchar("url", 2048)

    /** 0 is the highest priority. */
    val priority = integer("priority").default(0)
    val updatesEnabled = bool("updates_enabled").default(true)

    init {
        uniqueIndex(manga, sourceId)
    }
}
