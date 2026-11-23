package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.AddTableMigration
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/** Operation log of the Karasu sync (contract v2). Works on both H2 and PostgreSQL. */
@Suppress("ClassName", "unused")
class M0070_KarasuOperations : AddTableMigration() {
    private class KarasuOperationTable : LongIdTable("KarasuOperation", columnName = "seq") {
        val opId = varchar("op_id", 64).uniqueIndex()
        val deviceId = varchar("device_id", 128)
        val ts = long("ts")
        val entity = varchar("entity", 32)
        val key = text("op_key")
        val keyHash = varchar("key_hash", 64)
        val action = varchar("action", 16)
        val value = text("op_value").nullable()
        val receivedAt = long("received_at")

        init {
            index(false, entity, keyHash, id)
            index(false, ts)
            index(false, entity, id)
        }
    }

    override val tables: Array<Table>
        get() = arrayOf(KarasuOperationTable())
}
