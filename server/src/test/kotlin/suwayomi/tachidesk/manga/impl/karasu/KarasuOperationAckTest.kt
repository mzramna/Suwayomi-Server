package suwayomi.tachidesk.manga.impl.karasu

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.model.table.KarasuDeviceTable
import suwayomi.tachidesk.manga.model.table.KarasuOperationTable
import suwayomi.tachidesk.manga.model.table.KarasuSeenOperationTable
import suwayomi.tachidesk.manga.model.table.KarasuSettingTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Acknowledgement per device, pruning by ack + TTL, compaction on write and re-sent ids (`opsVersion 3`).
 * The database is the one the test run was configured with (H2, or PostgreSQL with `-PtestDatabaseType`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KarasuOperationAckTest : ApplicationTest() {
    @BeforeEach
    @AfterEach
    fun clean() {
        clearTables(KarasuOperationTable)
        transaction {
            KarasuDeviceTable.deleteAll()
            KarasuSeenOperationTable.deleteAll()
        }
        transaction { KarasuSettingTable.deleteWhere { KarasuSettingTable.key eq "operations.prunedUpTo" } }
        KarasuRequestContext.clear()
    }

    private var counter = 0

    private fun op(
        id: String = "op-${counter++}",
        key: String = "category:1",
        entity: String = "CATEGORY",
        action: String = "SET",
        ts: Long = 1_700_000_000_000,
        device: String = "A",
        value: JsonElement? = buildJsonObject { put("name", "X") },
    ) = OperationDto(id, device, ts, entity, key, action, value)

    private fun count() = transaction { KarasuOperationTable.selectAll().count() }

    private fun device(id: String) = transaction { KarasuDeviceTable.selectAll().where { KarasuDeviceTable.deviceId eq id }.singleOrNull() }

    private fun ackOf(id: String) = device(id)!![KarasuDeviceTable.ackSeq]

    /** Registers [id] as a device without acknowledging anything. */
    private fun register(id: String) = KarasuOperations.list(0, null, 5000, id)

    private fun ageDevice(
        id: String,
        days: Long,
    ) = transaction {
        KarasuDeviceTable.update({ KarasuDeviceTable.deviceId eq id }) {
            it[lastSeenAt] =
                System.currentTimeMillis() - days * 24 * 60 * 60 * 1000
        }
    }

    // -- devices --------------------------------------------------------------------------------------------

    @Test
    fun `the first GET and POST with a device header register it, web and blank are never devices`() {
        KarasuOperations.list(0, null, 10, "pixel")
        assertEquals(0L, ackOf("pixel"))
        KarasuOperations.append(listOf(op()), "tablet")
        assertEquals(0L, ackOf("tablet"))
        KarasuOperations.list(0, null, 10, "web", ack = 5)
        KarasuOperations.list(0, null, 10, "WEB", ack = 5)
        KarasuOperations.list(0, null, 10, "  ", ack = 5)
        KarasuOperations.list(0, null, 10, null, ack = 5)
        KarasuOperations.append(listOf(op(key = "category:2")), "web")
        KarasuOperations.record(KarasuEntity.POLICY, "policy", KarasuAction.SET, deviceId = "web")
        assertEquals(setOf("pixel", "tablet"), transaction { KarasuDeviceTable.selectAll().map { it[KarasuDeviceTable.deviceId] }.toSet() })
    }

    @Test
    fun `a request without header or ack behaves as before`() {
        KarasuOperations.append(listOf(op(key = "category:1"), op(key = "category:2")))
        val page = KarasuOperations.list()
        assertEquals(2, page.operations.size)
        assertEquals(page.operations.last().seq, page.lastSeq)
        assertEquals(1L, page.minSeq)
        assertFalse(page.resetRequired)
        assertEquals(0L, transaction { KarasuDeviceTable.selectAll().count() })
        // an ack without a device means nothing and prunes nothing
        KarasuOperations.list(0, null, 10, null, ack = page.lastSeq)
        assertEquals(2L, count())
    }

    @Test
    fun `ack only moves forward, is capped to the head of the log and prunes right away`() {
        KarasuOperations.append(listOf(op(key = "category:1"), op(key = "category:2"), op(key = "category:3")), "A")
        KarasuOperations.list(0, null, 10, "A")
        val head = KarasuOperations.list(0, null, 10, "A").lastSeq
        KarasuOperations.list(head - 1, null, 10, "A", ack = 2)
        assertEquals(2L, ackOf("A"))
        KarasuOperations.list(head, null, 10, "A", ack = 1)
        assertEquals(2L, ackOf("A"), "an older ack never lowers it")
        KarasuOperations.list(head, null, 10, "A", ack = 1_000_000)
        assertEquals(head, ackOf("A"), "an ack beyond the head of the log is capped to it")
        assertEquals(0L, count(), "the only active device confirmed everything")
    }

    // -- compaction -----------------------------------------------------------------------------------------

    @Test
    fun `1000 SETs of the same target leave one row`() {
        register("B")
        val ops =
            (1..1000).map {
                op(
                    key = "progress:1|/m|/c",
                    entity = "PROGRESS",
                    ts = 1_700_000_000_000 + it,
                    value = buildJsonObject { put("lastPageRead", it) },
                )
            }
        val result = KarasuOperations.append(ops, "A")
        assertEquals(OperationsAppendResultDto(1000, 0), result)
        val rows = KarasuOperations.list().operations
        assertEquals(1, rows.size)
        assertEquals(ops.last().id, rows.single().id)
        assertEquals(buildJsonObject { put("lastPageRead", 1000) }, rows.single().value)
    }

    @Test
    fun `recordAll compacts the automatic web operations too`() {
        repeat(50) {
            KarasuOperations.record(
                KarasuEntity.CATEGORY,
                "category:1",
                KarasuAction.SET,
                KarasuOperations.nameValue("n$it"),
                ts =
                    1_700_000_000_000 + it,
            )
        }
        val rows = KarasuOperations.list().operations
        assertEquals(1, rows.size)
        assertEquals(KarasuOperations.nameValue("n49"), rows.single().value)
    }

    @Test
    fun `an older operation of a target is discarded and counts as a duplicate`() {
        KarasuOperations.append(listOf(op("new", ts = 2_000)), "A")
        val result = KarasuOperations.append(listOf(op("old", ts = 1_000, device = "B")), "B")
        assertEquals(OperationsAppendResultDto(0, 1), result)
        assertEquals(listOf("new"), KarasuOperations.list().operations.map { it.id })
        // and it stays a duplicate
        assertEquals(OperationsAppendResultDto(0, 1), KarasuOperations.append(listOf(op("old", ts = 1_000, device = "B")), "B"))
    }

    @Test
    fun `SET then REMOVE made by the only active device cancel out`() {
        val result = KarasuOperations.append(listOf(op("s", ts = 1), op("r", ts = 2, action = "REMOVE", value = null)), "A")
        assertEquals(OperationsAppendResultDto(2, 0), result)
        assertEquals(0L, count())
        // re-sending them is still a duplicate
        assertEquals(
            OperationsAppendResultDto(0, 2),
            KarasuOperations.append(listOf(op("s", ts = 1), op("r", ts = 2, action = "REMOVE", value = null)), "A"),
        )
        assertEquals(0L, count())
    }

    @Test
    fun `SET then REMOVE keeps the tombstone while another device could hold the target, and it goes when they all confirm`() {
        register("B")
        // A publishes SET + REMOVE; B (another active device) has not seen the SET but may hold an older version
        KarasuOperations.append(listOf(op("s", ts = 1), op("r", ts = 2, action = "REMOVE", value = null)), "A")
        val rows = KarasuOperations.list().operations
        assertEquals(listOf("r"), rows.map { it.id })
        val seq = rows.single().seq

        // A pulls it back and confirms, B has not: the tombstone stays
        KarasuOperations.list(0, null, 10, "A", ack = seq)
        assertEquals(1L, count())
        // B confirms: nothing is left
        KarasuOperations.list(0, null, 10, "B", ack = seq)
        assertEquals(0L, count())
    }

    @Test
    fun `SET seen by a device followed by a REMOVE leaves one tombstone row`() {
        register("B")
        KarasuOperations.append(listOf(op("s", ts = 1)), "A")
        val seen = KarasuOperations.list(0, null, 10, "B")
        KarasuOperations.list(seen.lastSeq, null, 10, "B", ack = seen.lastSeq) // B applied the SET
        assertEquals(1L, count())

        KarasuOperations.record(KarasuEntity.CATEGORY, "category:1", KarasuAction.REMOVE, ts = 2, skipIfSameAsLast = false)
        val rows = KarasuOperations.list().operations
        assertEquals(1, rows.size)
        assertEquals("REMOVE", rows.single().action)
        assertTrue(rows.single().seq > seen.lastSeq, "B still has to receive the tombstone")
    }

    @Test
    fun `two devices confirming empties the log`() {
        register("A")
        register("B")
        KarasuOperations.append(
            listOf(
                op(key = "category:1"),
                op(key = "link:1|/m|1", entity = "CATEGORY_LINK", value = null),
                op(key = "progress:1|/m|/c", entity = "PROGRESS"),
            ),
            "A",
        )
        val head = KarasuOperations.list(0, null, 100, "A").lastSeq
        KarasuOperations.list(head, null, 100, "A", ack = head)
        assertEquals(3L, count(), "B has not confirmed")
        KarasuOperations.list(head, null, 100, "B", ack = head)
        assertEquals(0L, count())
    }

    // -- TTL and reset -------------------------------------------------------------------------------------

    @Test
    fun `an expired device stops holding the log and is told to reset when it comes back`() {
        register("A")
        register("B")
        KarasuOperations.append(listOf(op(key = "category:1"), op(key = "category:2")), "A")
        val head = KarasuOperations.list(0, null, 100, "A").lastSeq

        ageDevice("B", 31)
        KarasuOperations.list(head, null, 100, "A", ack = head)
        assertEquals(0L, count(), "B is past the TTL and does not hold the log back")

        // new operation while B is away
        KarasuOperations.append(listOf(op(key = "category:3", ts = 2_000_000_000_000)), "A")

        // B comes back with its old cursor: reset, and it keeps being told until it confirms the head
        val back = KarasuOperations.list(0, null, 100, "B", ack = 99)
        assertTrue(back.resetRequired)
        assertEquals(head + 1, back.minSeq)
        val again = KarasuOperations.list(0, null, 100, "B")
        assertTrue(again.resetRequired, "the flag survives a lost response")
        assertEquals(0L, ackOf("B"), "the ack of the request that detected the reset is ignored")

        val newHead = again.lastSeq
        assertTrue(newHead > head)
        val confirmed = KarasuOperations.list(newHead, null, 100, "B", ack = newHead)
        assertFalse(confirmed.resetRequired)
        assertEquals(newHead, ackOf("B"))
    }

    @Test
    fun `a device inactive past the TTL is told to reset even when the log was not pruned`() {
        register("B")
        KarasuOperations.append(listOf(op()), "A")
        ageDevice("B", 31)
        val back = KarasuOperations.list(0, null, 100, "B")
        assertTrue(back.resetRequired)
        assertEquals(1L, back.minSeq)
    }

    @Test
    fun `a new device is told to reset only if the log was pruned, a client behind minSeq always`() {
        KarasuOperations.append(listOf(op(key = "category:1"), op(key = "category:2")), "A")
        val fresh = KarasuOperations.list(0, null, 100, "C")
        assertFalse(fresh.resetRequired, "the log was never pruned: it is complete")
        assertEquals(1L, fresh.minSeq)

        // A confirms everything, but C (active, ack 0) holds it
        val head = fresh.lastSeq
        KarasuOperations.list(head, null, 100, "A", ack = head)
        assertEquals(2L, count())
        KarasuOperations.list(head, null, 100, "C", ack = head)
        assertEquals(0L, count())

        val late = KarasuOperations.list(0, null, 100, "D")
        assertTrue(late.resetRequired)
        assertEquals(head + 1, late.minSeq)
        assertEquals(head, late.lastSeq, "an empty log still tells where the head is")
        // a plain client behind the pruned part is told too, without registering as a device
        assertTrue(KarasuOperations.list(0).resetRequired)
        assertFalse(KarasuOperations.list(head).resetRequired)
        assertNull(device("web"))
    }

    @Test
    fun `without active devices only the 90 day ceiling prunes`() {
        KarasuOperations.append(listOf(op(key = "category:1"), op(key = "category:2")), "A")
        ageDevice("A", 31)
        assertEquals(0, KarasuOperations.pruneAcked())
        assertEquals(2L, count())
        val day = 24L * 60 * 60 * 1000
        assertEquals(2, KarasuOperations.pruneAcked(System.currentTimeMillis() + 91 * day))
        assertEquals(0L, count())
    }

    // -- re-sent ids ---------------------------------------------------------------------------------------

    @Test
    fun `re-sending an id that was compacted or pruned is a duplicate`() {
        register("B")
        KarasuOperations.append(listOf(op("a", ts = 100)), "A")
        KarasuOperations.append(listOf(op("b", ts = 200)), "A")
        assertEquals(listOf("b"), KarasuOperations.list().operations.map { it.id })
        // "a" was compacted
        assertEquals(OperationsAppendResultDto(0, 1), KarasuOperations.append(listOf(op("a", ts = 100)), "A"))

        // everybody confirms "b" and it is pruned: it must not come back either
        val head = KarasuOperations.list(0, null, 10, "A").lastSeq
        KarasuOperations.list(head, null, 10, "A", ack = head)
        KarasuOperations.list(head, null, 10, "B", ack = head)
        assertEquals(0L, count())
        assertEquals(OperationsAppendResultDto(0, 2), KarasuOperations.append(listOf(op("a", ts = 100), op("b", ts = 200)), "A"))
        assertEquals(0L, count())
        assertTrue(transaction { KarasuSeenOperationTable.selectAll().count() } >= 2)
    }

    // -- legacy data ---------------------------------------------------------------------------------------

    @Test
    fun `compactExisting keeps the latest operation per target in a log written before compaction`() {
        transaction {
            listOf(
                Triple("o1", 10L, "category:1"),
                Triple("o2", 30L, "category:1"),
                Triple("o3", 20L, "category:1"),
                Triple("o4", 5L, "category:2"),
            ).forEach { (id, at, key) ->
                KarasuOperationTable.insert {
                    it[opId] = id
                    it[deviceId] = "A"
                    it[ts] = at
                    it[entity] = "CATEGORY"
                    it[KarasuOperationTable.key] = key
                    it[keyHash] =
                        java.security.MessageDigest
                            .getInstance(
                                "SHA-256",
                            ).digest(key.toByteArray())
                            .joinToString("") { b -> "%02x".format(b) }
                    it[action] = "SET"
                    it[receivedAt] = System.currentTimeMillis()
                }
            }
        }
        assertEquals(2, KarasuOperations.compactExisting())
        assertEquals(
            listOf("o2", "o4"),
            KarasuOperations
                .list()
                .operations
                .map { it.id }
                .sorted(),
        )
        assertEquals(0, KarasuOperations.compactExisting())
    }

    // -- compression ---------------------------------------------------------------------------------------

    @Test
    fun `1000 typical operations gzip to less than a quarter of the JSON`() {
        val ops =
            (1..1000).map {
                OperationEntryDto(
                    seq = 5000L + it,
                    id = UUID.randomUUID().toString(),
                    deviceId = "9f1c3a52-7d1e-4a43-9b56-2d6a4f0c1e11",
                    ts = 1_760_000_000_000 + it * 1234L,
                    entity = "PROGRESS",
                    key = "progress:2499283573021220255|/manga/some-typical-series-name-$it|/chapter/some-chapter-$it",
                    action = "SET",
                    value =
                        buildJsonObject {
                            put("read", true)
                            put("lastPageRead", it % 40)
                        },
                )
            }
        val text = Json.encodeToString(OperationsListDto.serializer(), OperationsListDto(ops, 6000, 1_760_000_000_000))
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()
        val ratio = gz.size.toDouble() / text.toByteArray().size
        println("gzip of 1000 typical operations: ${text.toByteArray().size} -> ${gz.size} bytes (${"%.1f".format(ratio * 100)}%)")
        assertTrue(ratio < 0.25, "ratio was $ratio")
    }
}
