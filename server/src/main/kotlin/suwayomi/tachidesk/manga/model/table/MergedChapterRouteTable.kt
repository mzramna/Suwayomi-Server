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
 * Says which source actually serves the pages of a chapter when it is not the manga's own source.
 * [isExtra] = true: the chapter itself came from a merged source. false: the main source failed
 * and a reserve answered for it.
 */
object MergedChapterRouteTable : IntIdTable() {
    val chapter = reference("chapter", ChapterTable, ReferenceOption.CASCADE).uniqueIndex()
    val sourceId = long("source_id")
    val isExtra = bool("is_extra").default(false)
}
