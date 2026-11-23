package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/**
 * "Tombstones" asked from the WebUI: chapters that Karasu devices should delete locally.
 * Karasu polls this list (client driven) and remembers the last id it applied.
 * Keyed by source + urls, never by server ids, because that is how a device identifies chapters.
 */
object KarasuDeletionTable : IntIdTable() {
    val sourceId = long("source_id")
    val mangaUrl = varchar("manga_url", 2048)
    val chapterUrl = varchar("chapter_url", 2048)
    val chapterNumber = float("chapter_number").default(-1f)
    val requestedAt = long("requested_at")
}
