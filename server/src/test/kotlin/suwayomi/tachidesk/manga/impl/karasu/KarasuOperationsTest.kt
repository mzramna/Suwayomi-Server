package suwayomi.tachidesk.manga.impl.karasu

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.model.table.KarasuOperationTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KarasuOperationsTest : ApplicationTest() {
    @BeforeEach
    @AfterEach
    fun clean() {
        clearTables(KarasuOperationTable)
        KarasuRequestContext.clear()
    }

    private fun op(
        id: String,
        key: String = "category:1",
        entity: String = "CATEGORY",
        action: String = "SET",
        ts: Long = 1_700_000_000_000,
        device: String = "pixel",
        value: kotlinx.serialization.json.JsonElement? = buildJsonObject { put("name", "X") },
    ) = OperationDto(id, device, ts, entity, key, action, value)

    @Test
    fun `migration created the operation table`() {
        transaction { assertEquals(0L, KarasuOperationTable.selectAll().count()) }
    }

    @Test
    fun `record stores the change as web, with the time of the change and a fresh seq each time`() {
        val before = System.currentTimeMillis()
        assertTrue(KarasuOperations.record(KarasuEntity.CATEGORY, "category:1", KarasuAction.SET, KarasuOperations.nameValue("A")))
        assertTrue(KarasuOperations.record(KarasuEntity.CATEGORY, "category:1", KarasuAction.SET, KarasuOperations.nameValue("B")))

        val page = KarasuOperations.list()
        assertEquals(2, page.operations.size)
        val (first, second) = page.operations
        assertTrue(first.seq < second.seq)
        assertEquals("web", first.deviceId)
        assertEquals("CATEGORY", first.entity)
        assertEquals("category:1", first.key)
        assertEquals("SET", first.action)
        assertEquals(KarasuOperations.nameValue("A"), first.value)
        assertTrue(first.ts >= before && first.ts <= page.serverTime)
        assertNotEqualsIds(first.id, second.id)
        assertEquals(second.seq, page.lastSeq)
    }

    private fun assertNotEqualsIds(
        a: String,
        b: String,
    ) = assertTrue(a != b)

    @Test
    fun `record does not store a no-op, but stores the same value again after something else happened`() {
        val name = KarasuOperations.nameValue("A")
        assertTrue(KarasuOperations.record(KarasuEntity.CATEGORY, "category:1", KarasuAction.SET, name))
        assertFalse(KarasuOperations.record(KarasuEntity.CATEGORY, "category:1", KarasuAction.SET, name))
        // removing twice is a no-op too, and a link SET with no value as well
        assertTrue(KarasuOperations.record(KarasuEntity.CATEGORY, "category:1", KarasuAction.REMOVE))
        assertFalse(KarasuOperations.record(KarasuEntity.CATEGORY, "category:1", KarasuAction.REMOVE))
        // back to the old value: it is a change now
        assertTrue(KarasuOperations.record(KarasuEntity.CATEGORY, "category:1", KarasuAction.SET, name))
        assertTrue(KarasuOperations.record(KarasuEntity.CATEGORY_LINK, "link:1|/m|1", KarasuAction.SET))
        assertFalse(KarasuOperations.record(KarasuEntity.CATEGORY_LINK, "link:1|/m|1", KarasuAction.SET, JsonNull))
        // another key is independent
        assertTrue(KarasuOperations.record(KarasuEntity.CATEGORY, "category:2", KarasuAction.SET, name))
        assertEquals(5, KarasuOperations.list().operations.size)
    }

    @Test
    fun `recordAll drops a repeated change inside the same batch and keeps different ones`() {
        val written =
            KarasuOperations.recordAll(
                listOf(
                    PendingOperation(KarasuEntity.CATEGORY, "category:1", KarasuAction.SET, KarasuOperations.nameValue("A")),
                    PendingOperation(KarasuEntity.CATEGORY, "category:1", KarasuAction.SET, KarasuOperations.nameValue("A")),
                    PendingOperation(KarasuEntity.CATEGORY, "category:1", KarasuAction.SET, KarasuOperations.nameValue("B")),
                ),
            )
        assertEquals(2, written)
    }

    @Test
    fun `a request that identifies its device is not logged by the server, an explicit device always is`() {
        KarasuRequestContext.withDevice("pixel-8") {
            assertFalse(KarasuOperations.record(KarasuEntity.POLICY, "policy", KarasuAction.SET, JsonPrimitive("x")))
            // blank counts as "no header"
            KarasuRequestContext.withDevice("   ") {
                assertTrue(KarasuOperations.record(KarasuEntity.POLICY, "policy", KarasuAction.SET, JsonPrimitive("y")))
            }
            assertEquals("pixel-8", KarasuRequestContext.device())
            assertTrue(
                KarasuOperations.record(KarasuEntity.POLICY, "policy", KarasuAction.SET, JsonPrimitive("z"), deviceId = "server"),
            )
        }
        assertNull(KarasuRequestContext.device())
        assertEquals(listOf("web", "server"), KarasuOperations.list().operations.map { it.deviceId })
    }

    @Test
    fun `the device does not leak to another thread or outlive its block`() {
        var seenElsewhere: String? = "unset"
        KarasuRequestContext.withDevice("pixel") {
            val t = Thread { seenElsewhere = KarasuRequestContext.device() }
            t.start()
            t.join()
        }
        assertNull(seenElsewhere)
        assertNull(KarasuRequestContext.device())
    }

    @Test
    fun `append is idempotent by id and keeps the client's ts and device`() {
        val result = KarasuOperations.append(listOf(op("a", ts = 1_000), op("b", key = "category:2", ts = 2_000, device = "tablet")))
        assertEquals(OperationsAppendResultDto(2, 0), result)

        // same ids again, one repeated inside the batch, one new
        val again = KarasuOperations.append(listOf(op("a"), op("c", key = "category:3"), op("c", key = "category:3")))
        assertEquals(OperationsAppendResultDto(1, 2), again)

        val ops = KarasuOperations.list().operations
        assertEquals(listOf("a", "b", "c"), ops.map { it.id })
        assertEquals(listOf(1_000L, 2_000L, 1_700_000_000_000L), ops.map { it.ts })
        assertEquals(listOf("pixel", "tablet", "pixel"), ops.map { it.deviceId })
        assertEquals(OperationsAppendResultDto(0, 0), KarasuOperations.append(emptyList()))
    }

    @Test
    fun `append stores operations as they are, even identical to the last one, and never touches state`() {
        KarasuOperations.append(listOf(op("a"), op("b")))
        assertEquals(2, KarasuOperations.list().operations.size)
    }

    @Test
    fun `append rejects invalid operations without writing any`() {
        assertFailsWith<IllegalArgumentException> { KarasuOperations.append(listOf(op("ok"), op("bad", entity = "NOPE"))) }
        assertFailsWith<IllegalArgumentException> { KarasuOperations.append(listOf(op("bad", action = "UPSERT"))) }
        assertFailsWith<IllegalArgumentException> { KarasuOperations.append(listOf(op(" "))) }
        assertFailsWith<IllegalArgumentException> { KarasuOperations.append(listOf(op("k", key = ""))) }
        assertFailsWith<IllegalArgumentException> { KarasuOperations.append(listOf(op("d", device = ""))) }
        assertFailsWith<IllegalArgumentException> { KarasuOperations.append(listOf(op("t", ts = -1))) }
        assertTrue(KarasuOperations.list().operations.isEmpty())
    }

    @Test
    fun `values round trip as json and a null or missing value stays null`() {
        val merged =
            buildJsonObject {
                put("sources", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject { put("sourceId", "123") })))
            }
        KarasuOperations.append(
            listOf(
                op("m", entity = "MERGED", key = "merged:1|/m", value = merged),
                op("n", entity = "CATEGORY_LINK", key = "link:1|/m|1", value = null),
                op("j", entity = "CATEGORY_LINK", key = "link:1|/m|2", value = JsonNull),
            ),
        )
        val ops = KarasuOperations.list().operations
        assertEquals(merged, ops[0].value)
        assertNull(ops[1].value)
        assertNull(ops[2].value)
    }

    @Test
    fun `list pages by seq, filters by entity and caps the limit`() {
        KarasuOperations.append((1..7).map { op("id$it", key = "category:$it", entity = if (it % 2 == 0) "CATEGORY" else "POLICY") })

        val first = KarasuOperations.list(0, null, 3)
        assertEquals(listOf("id1", "id2", "id3"), first.operations.map { it.id })
        assertEquals(first.operations.last().seq, first.lastSeq)

        val next = KarasuOperations.list(first.lastSeq, null, 100)
        assertEquals(listOf("id4", "id5", "id6", "id7"), next.operations.map { it.id })

        assertEquals(listOf("id2", "id4", "id6"), KarasuOperations.list(0, "CATEGORY").operations.map { it.id })
        assertEquals(7, KarasuOperations.list(0, "  ").operations.size)
        assertFailsWith<IllegalArgumentException> { KarasuOperations.list(0, "NOPE") }

        // nothing new: the cursor stays where it was
        val empty = KarasuOperations.list(next.lastSeq)
        assertTrue(empty.operations.isEmpty())
        assertEquals(next.lastSeq, empty.lastSeq)
        // zero or huge limits are brought into range
        assertEquals(1, KarasuOperations.list(0, null, 0).operations.size)
        assertEquals(7, KarasuOperations.list(0, null, Int.MAX_VALUE).operations.size)
    }

    @Test
    fun `keys longer than an index entry are stored and found again`() {
        val key = "progress:1|/" + "m".repeat(2000) + "|/" + "c".repeat(2000)
        assertTrue(KarasuOperations.record(KarasuEntity.PROGRESS, key, KarasuAction.SET, JsonObject(emptyMap())))
        assertFalse(KarasuOperations.record(KarasuEntity.PROGRESS, key, KarasuAction.SET, JsonObject(emptyMap())))
        assertEquals(
            key,
            KarasuOperations
                .list()
                .operations
                .single()
                .key,
        )
    }

    @Test
    fun `pruneOld drops what was received more than 90 days ago`() {
        KarasuOperations.append(listOf(op("recent"), op("old")))
        val day = 24L * 60 * 60 * 1000
        transaction {
            KarasuOperationTable.insert {
                it[opId] = "ancient"
                it[deviceId] = "pixel"
                it[ts] = 1
                it[entity] = "POLICY"
                it[key] = "policy"
                it[keyHash] = "x"
                it[action] = "SET"
                it[receivedAt] = System.currentTimeMillis() - 91 * day
            }
        }
        assertEquals(3, KarasuOperations.list().operations.size)
        assertEquals(1, KarasuOperations.pruneOld())
        assertEquals(listOf("recent", "old"), KarasuOperations.list().operations.map { it.id })
        // pretend it is 100 days later: everything goes
        assertEquals(2, KarasuOperations.pruneOld(System.currentTimeMillis() + 100 * day))
    }
}
