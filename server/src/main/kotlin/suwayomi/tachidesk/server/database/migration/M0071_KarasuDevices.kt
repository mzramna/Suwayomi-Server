package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.AddTableMigration
import org.jetbrains.exposed.v1.core.Table

/**
 * Devices of the Karasu operation log (acknowledged cursor per device) and the ids of operations that were accepted
 * but are no longer in the log (compacted or pruned), used to recognise a re-sent operation. Works on both H2 and
 * PostgreSQL.
 */
@Suppress("ClassName", "unused")
class M0071_KarasuDevices : AddTableMigration() {
    private class KarasuDeviceTable : Table("KarasuDevice") {
        val deviceId = varchar("device_id", 128)
        val ackSeq = long("ack_seq")
        val resetSeq = long("reset_seq")
        val lastSeenAt = long("last_seen_at")

        override val primaryKey = PrimaryKey(deviceId)

        init {
            index(false, lastSeenAt)
        }
    }

    private class KarasuSeenOperationTable : Table("KarasuSeenOperation") {
        val opId = varchar("op_id", 64)
        val seenAt = long("seen_at")

        override val primaryKey = PrimaryKey(opId)

        init {
            index(false, seenAt)
        }
    }

    override val tables: Array<Table>
        get() = arrayOf(KarasuDeviceTable(), KarasuSeenOperationTable())
}
