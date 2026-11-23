package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/** Server wide key/value settings of the Karasu integration (e.g. the download policy). */
object KarasuSettingTable : IntIdTable() {
    val key = varchar("setting_key", 256).uniqueIndex()
    val value = varchar("setting_value", 4096)
}
