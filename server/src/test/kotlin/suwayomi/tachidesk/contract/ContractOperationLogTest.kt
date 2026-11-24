package suwayomi.tachidesk.contract

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The log as the source of truth (`opsVersion 3`) over real HTTP: `ack`, `minSeq`, `resetRequired`, compaction and gzip.
 * The server is shared with the other contract tests (other devices are active), so nothing here relies on the log
 * being pruned empty: that is covered by the unit tests, which control every device.
 */
@EnabledIfSystemProperty(named = "contractUrl", matches = ".+")
class ContractOperationLogTest {
    private val app = ContractClient("contract-log-" + UUID.randomUUID().toString().take(8))
    private val web = ContractClient(null)

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                GZIPOutputStream(out).use {
                    it.write(text.toByteArray())
                }
            }.toByteArray()

    private fun gunzip(bytes: ByteArray): String = GZIPInputStream(ByteArrayInputStream(bytes)).use { String(it.readBytes()) }

    private fun body(vararg ops: kotlinx.serialization.json.JsonObject) =
        buildJsonObject { put("operations", JsonArray(ops.toList())) }.toString()

    private fun progress(
        key: String,
        ts: Long,
        page: Int,
        device: String = app.device!!,
    ) = appOperation(
        device,
        ts,
        "PROGRESS",
        key,
        "SET",
        buildJsonObject {
            put("read", false)
            put("lastPageRead", page)
        },
    )

    @Test
    fun `health announces opsVersion 3`() {
        assertEquals(
            3,
            app
                .get("health")
                .obj["opsVersion"]!!
                .jsonPrimitive.int,
        )
    }

    @Test
    fun `the listing carries minSeq and resetRequired, with or without a device, and ack must be a number`() {
        for (client in listOf(app, web)) {
            val page = client.get("operations", "afterSeq" to client.headSeq(), "limit" to 10).obj
            assertTrue(page["minSeq"]!!.jsonPrimitive.long >= 1)
            assertFalse(page["resetRequired"]!!.jsonPrimitive.content.toBoolean())
            assertNotNull(page["serverTime"])
            assertNotNull(page["lastSeq"])
        }
        assertEquals(400, app.request("GET", "/api/v1/karasu/operations?ack=abc").status)
        // an ack that goes past the head of the log is taken, capped, and the answer is the usual one
        assertEquals(200, app.get("operations", "afterSeq" to app.headSeq(), "ack" to 999_999_999_999L).status)
    }

    @Test
    fun `a device never seen is told to reset exactly when the log was pruned`() {
        val newcomer = ContractClient("contract-new-" + UUID.randomUUID().toString().take(8))
        val page = newcomer.get("operations", "afterSeq" to 0, "limit" to 1).obj
        val minSeq = page["minSeq"]!!.jsonPrimitive.long
        assertEquals(minSeq > 1, page["resetRequired"]!!.jsonPrimitive.content.toBoolean())
        // a plain client behind the pruned part is told too
        assertEquals(
            minSeq > 1,
            web
                .get("operations", "afterSeq" to 0, "limit" to 1)
                .obj["resetRequired"]!!
                .jsonPrimitive.content
                .toBoolean(),
        )
    }

    @Test
    fun `many operations on the same target are compacted into the last one`() {
        val seq = app.headSeq()
        val key = "progress:0|/contract-compact-${UUID.randomUUID()}|/c1"
        val base = System.currentTimeMillis()
        val ops = (1..60).map { progress(key, base + it, it) }
        val first = app.post("operations", buildJsonObject { put("operations", JsonArray(ops)) })
        assertEquals(200, first.status)
        assertEquals(60, first.obj["accepted"]!!.jsonPrimitive.int)
        val kept = app.operationsAfter(seq, "PROGRESS").filter { it.key == key }
        assertEquals(1, kept.size)
        assertEquals(
            60,
            kept
                .single()
                .value!!
                .jsonObject["lastPageRead"]!!
                .jsonPrimitive.int,
        )

        // re-sending a compacted id and an older operation are duplicates
        val again = app.post("operations", buildJsonObject { put("operations", JsonArray(listOf(ops[0], progress(key, base - 5, 0)))) })
        assertEquals(0, again.obj["accepted"]!!.jsonPrimitive.int)
        assertEquals(2, again.obj["duplicates"]!!.jsonPrimitive.int)
    }

    @Test
    fun `SET then REMOVE from the app keeps a tombstone while other devices are active`() {
        val other = ContractClient("contract-other-" + UUID.randomUUID().toString().take(8))
        other.get("operations", "afterSeq" to 0, "limit" to 1)
        val seq = app.headSeq()
        val key = "progress:0|/contract-tomb-${UUID.randomUUID()}|/c1"
        val now = System.currentTimeMillis()
        app.post(
            "operations",
            buildJsonObject {
                put(
                    "operations",
                    JsonArray(listOf(progress(key, now, 3), appOperation(app.device!!, now + 1, "PROGRESS", key, "REMOVE"))),
                )
            },
        )
        val kept = app.operationsAfter(seq, "PROGRESS").filter { it.key == key }
        assertEquals(listOf("REMOVE"), kept.map { it.action })
    }

    @Test
    fun `gzip request body is accepted, a broken or unknown encoding is refused`() {
        val seq = app.headSeq()
        val key = "progress:0|/contract-gzip-${UUID.randomUUID()}|/c1"
        val ok =
            app.postEncoded(
                "operations",
                gzip(body(progress(key, System.currentTimeMillis(), 7))),
                mapOf("Content-Encoding" to "gzip"),
            )
        assertEquals(200, ok.status, ok.text)
        assertEquals(1, ok.obj["accepted"]!!.jsonPrimitive.int)
        assertEquals(
            7,
            app
                .operationsAfter(seq, "PROGRESS")
                .single { it.key == key }
                .value!!
                .jsonObject["lastPageRead"]!!
                .jsonPrimitive.int,
        )

        val broken = app.postEncoded("operations", "not gzip".toByteArray(), mapOf("Content-Encoding" to "gzip"))
        assertEquals(400, broken.status)
        val unknown = app.postEncoded("operations", body().toByteArray(), mapOf("Content-Encoding" to "br"))
        assertEquals(415, unknown.status)
        // no encoding: as before
        assertEquals(200, app.post("operations", buildJsonObject { put("operations", JsonArray(emptyList())) }).status)
    }

    @Test
    fun `GET operations answers gzip when asked, the same JSON, and under a quarter of its size for 1000 operations`() {
        val seq = app.headSeq()
        val base = System.currentTimeMillis()
        val tag = UUID.randomUUID().toString().take(8)
        val ops =
            (1..1000).map {
                progress(
                    "progress:2499283573021220255|/manga/typical-series-name-$tag-$it|/chapter/typical-chapter-$it",
                    base + it,
                    it % 40,
                )
            }
        ops.chunked(500).forEach { chunk ->
            val r = app.postEncoded("operations", gzip(body(*chunk.toTypedArray())), mapOf("Content-Encoding" to "gzip"))
            assertEquals(200, r.status, r.text)
        }

        val plain = app.get("operations", "afterSeq" to seq, "limit" to 5000)
        assertNull(plain.header("Content-Encoding"), "no Accept-Encoding, no compression")
        val packed = app.getWith("operations", mapOf("Accept-Encoding" to "gzip"), "afterSeq" to seq, "limit" to 5000)
        assertEquals("gzip", packed.header("Content-Encoding")?.lowercase())
        assertEquals(
            plain.obj["operations"]!!.jsonArray.size,
            Json
                .parseToJsonElement(gunzip(packed.body))
                .jsonObject["operations"]!!
                .jsonArray.size,
        )
        val ratio = packed.body.size.toDouble() / plain.body.size
        println(
            "contract gzip: ${plain.body.size} -> ${packed.body.size} bytes (${"%.1f".format(
                ratio * 100,
            )}%) for ${plain.obj["operations"]!!.jsonArray.size} operations",
        )
        assertTrue(ratio < 0.25, "ratio was $ratio")
    }
}
