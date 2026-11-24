package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.Table

/**
 * A Karasu app installation that talks to the operation log (the `X-Karasu-Device` of its requests; `web` never
 * counts as a device). Created on its first request.
 *
 * [ackSeq] is the highest `seq` the device confirmed to have APPLIED (only ever grows). [resetSeq] is the head of the log when
 * the server found out the device cannot rely on the log (new device after the log was pruned, inactive for longer
 * than the TTL, cursor behind the pruned part): until the device confirms [resetSeq] it must reconcile by state.
 * [lastSeenAt] (epoch millis) is refreshed by every request; a device not seen within the TTL stops holding the log.
 */
object KarasuDeviceTable : Table("KarasuDevice") {
    val deviceId = varchar("device_id", 128)
    val ackSeq = long("ack_seq")
    val resetSeq = long("reset_seq")
    val lastSeenAt = long("last_seen_at")

    override val primaryKey = PrimaryKey(deviceId)

    init {
        index(false, lastSeenAt)
    }
}

/**
 * Ids of operations that were accepted but are not in `KarasuOperation` any more (superseded by a newer one, pruned,
 * or annihilated). A re-sent operation with one of these ids is a duplicate. Entries older than the device TTL go away.
 */
object KarasuSeenOperationTable : Table("KarasuSeenOperation") {
    val opId = varchar("op_id", 64)
    val seenAt = long("seen_at")

    override val primaryKey = PrimaryKey(opId)

    init {
        index(false, seenAt)
    }
}
