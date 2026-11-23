package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Operation log of the Karasu ecosystem (contract v2): who changed which key, when and to what.
 * It is a record, not a command: nothing is re-applied from it. [seq] is assigned by the server and is
 * what clients page by.
 *
 * [key] is `text` because it joins a source id and two urls and can exceed what a PostgreSQL btree entry
 * accepts; lookups by key go through [keyHash] (sha-256 of the key), which is the indexed column.
 */
object KarasuOperationTable : LongIdTable("KarasuOperation", columnName = "seq") {
    val opId = varchar("op_id", 64).uniqueIndex()
    val deviceId = varchar("device_id", 128)

    /** Epoch millis UTC of when the change was MADE (not of when it was synced). */
    val ts = long("ts")
    val entity = varchar("entity", 32)
    val key = text("op_key")
    val keyHash = varchar("key_hash", 64)
    val action = varchar("action", 16)

    /** JSON text, null for operations without a value. */
    val value = text("op_value").nullable()
    val receivedAt = long("received_at")

    init {
        index(false, entity, keyHash, id)
        index(false, ts)
        index(false, entity, id)
    }
}
