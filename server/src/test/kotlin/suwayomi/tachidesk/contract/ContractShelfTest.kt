package suwayomi.tachidesk.contract

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A shelf do plugin do Yatagarasu (KOReader), chamada como o plugin chama: sem X-Karasu-Device, Basic Auth, multipart
 * com `metadata` + `file`, health pelo /health que já existe. A shelf é compartilhada por tudo o que roda no servidor,
 * então cada teste usa ids próprios e os remove no fim.
 */
@EnabledIfSystemProperty(named = "contractUrl", matches = ".+")
class ContractShelfTest {
    private val plugin = ContractClient(null)
    private val ids = mutableSetOf<Long>()

    // ids aleatórios longe de qualquer id do app, para reexecuções e outros testes não colidirem
    private fun newId(): Long = (9_000_000_000L + Random.nextLong(0, 900_000_000L)).also { ids += it }

    @AfterEach
    fun cleanUp() {
        ids.forEach { plugin.delete("shelf/$it") }
        ids.clear()
    }

    private class Part(
        val name: String,
        val content: ByteArray,
        val filename: String? = null,
        val contentType: String? = null,
    )

    private fun multipart(vararg parts: Part): Pair<ByteArray, String> {
        val boundary = "----plugin" + UUID.randomUUID().toString().replace("-", "")
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) {
            val disposition = "form-data; name=\"${p.name}\"" + (p.filename?.let { "; filename=\"$it\"" } ?: "")
            out.write("--$boundary\r\nContent-Disposition: $disposition\r\n".toByteArray())
            p.contentType?.let { out.write("Content-Type: $it\r\n".toByteArray()) }
            out.write("\r\n".toByteArray())
            out.write(p.content)
            out.write("\r\n".toByteArray())
        }
        out.write("--$boundary--\r\n".toByteArray())
        return out.toByteArray() to "multipart/form-data; boundary=$boundary"
    }

    private fun metadata(
        id: Long,
        url: String = "/shelf/c$id",
    ) = """{"chapterId":$id,"chapterUrl":"$url","mangaTitle":"Contract","chapterName":"Cap $id","chapterNumber":1.0,"sourceId":"123"}"""

    private fun post(
        id: Long,
        content: ByteArray,
        url: String = "/shelf/c$id",
        client: ContractClient = plugin,
        withAuth: Boolean = true,
    ): ContractResponse {
        val (body, type) =
            multipart(
                Part("metadata", metadata(id, url).toByteArray(), contentType = "application/json"),
                Part("file", content, "$id.cbz", "application/vnd.comicbook+zip"),
            )
        return client.request("POST", "/api/v1/karasu/shelf", body, type, withAuth)
    }

    private fun entries(): List<JsonObject> =
        plugin
            .get("shelf")
            .obj["entries"]!!
            .jsonArray
            .map { it.jsonObject }

    private fun entry(id: Long): JsonObject? = entries().firstOrNull { it["chapterId"]!!.jsonPrimitive.long == id }

    private fun read(id: Long) = entry(id)!!["read"]!!.jsonPrimitive.content.toBoolean()

    private fun readBody(value: Boolean) = """{"read":$value}""".toByteArray()

    private fun putRead(
        id: String,
        body: ByteArray,
    ) = plugin.request("PUT", "/api/v1/karasu/shelf/$id/read", body)

    private fun cbz(size: Int): ByteArray = Random(size).nextBytes(size)

    @Test
    fun `the plugin flow - health, upload, list, download, read, delete`() {
        assertEquals(
            "ok",
            plugin
                .get("health")
                .obj["status"]!!
                .jsonPrimitive.content,
        )
        val seq = plugin.headSeq()
        val id = newId()
        val bytes = cbz(3_000_000)

        val created = post(id, bytes)
        assertEquals(201, created.status)
        assertEquals(id, created.obj["chapterId"]!!.jsonPrimitive.long)
        assertEquals("/shelf/c$id", created.obj["chapterUrl"]!!.jsonPrimitive.content)
        assertEquals("false", created.obj["read"]!!.jsonPrimitive.content)

        val listed = plugin.get("shelf")
        assertEquals(200, listed.status)
        assertEquals(1, entries().count { it["chapterId"]!!.jsonPrimitive.long == id })
        assertEquals(setOf("chapterId", "chapterUrl", "read"), entry(id)!!.keys)
        assertEquals(false, read(id))

        val file = plugin.get("shelf/$id/file")
        assertEquals(200, file.status)
        assertTrue(bytes.contentEquals(file.body), "the downloaded file must be identical to the uploaded one")
        assertEquals(bytes.size.toString(), file.header("Content-Length"))
        assertEquals("application/vnd.comicbook+zip", file.header("Content-Type"))
        assertTrue(file.header("Content-Disposition")!!.contains("$id.cbz"))
        val head = plugin.head("shelf/$id/file")
        assertEquals(200, head.status)
        assertEquals(bytes.size.toString(), head.header("Content-Length"))
        assertEquals(0, head.body.size)

        assertEquals(204, putRead("$id", readBody(true)).status)
        assertEquals(true, read(id))
        assertEquals(204, putRead("$id", readBody(false)).status)
        assertEquals(false, read(id))

        // reenviar substitui o arquivo e zera read
        assertEquals(204, putRead("$id", readBody(true)).status)
        val replacement = cbz(1_500)
        assertEquals(201, post(id, replacement, url = "/shelf/c$id-v2").status)
        assertEquals(1, entries().count { it["chapterId"]!!.jsonPrimitive.long == id })
        assertEquals(false, read(id))
        assertEquals("/shelf/c$id-v2", entry(id)!!["chapterUrl"]!!.jsonPrimitive.content)
        assertTrue(replacement.contentEquals(plugin.get("shelf/$id/file").body))

        assertEquals(204, plugin.delete("shelf/$id").status)
        assertEquals(204, plugin.delete("shelf/$id").status)
        assertEquals(null, entry(id))
        assertEquals(404, plugin.get("shelf/$id/file").status)
        assertEquals(404, plugin.head("shelf/$id/file").status)
        assertEquals(404, putRead("$id", readBody(true)).status)

        // a shelf não entra no log de operações
        assertTrue(plugin.operationsAfter(seq).isEmpty())
    }

    @Test
    fun `the list is ordered by chapterId`() {
        val base = newId()
        ids += listOf(base + 30, base + 5, base + 17)
        listOf(base + 30, base, base + 17, base + 5).forEach { assertEquals(201, post(it, cbz(300)).status) }
        val mine = entries().map { it["chapterId"]!!.jsonPrimitive.long }.filter { it in ids }
        assertEquals(listOf(base, base + 5, base + 17, base + 30), mine)
        assertEquals(mine, mine.sorted())
    }

    @Test
    fun `bad requests are 400`() {
        val id = newId()
        val file = Part("file", cbz(200), "$id.cbz", "application/vnd.comicbook+zip")
        val meta = Part("metadata", metadata(id).toByteArray(), contentType = "application/json")

        fun send(vararg parts: Part): ContractResponse {
            val (body, type) = multipart(*parts)
            return plugin.request("POST", "/api/v1/karasu/shelf", body, type)
        }
        assertEquals(400, send(meta).status, "missing file")
        assertEquals(400, send(file).status, "missing metadata")
        assertEquals(400, send(Part("metadata", "not json".toByteArray()), file).status)
        assertEquals(400, send(Part("metadata", """{"chapterUrl":"/c"}""".toByteArray()), file).status, "no chapterId")
        assertEquals(400, send(Part("metadata", """{"chapterId":$id}""".toByteArray()), file).status, "no chapterUrl")
        assertEquals(400, send(Part("metadata", """{"chapterId":"../x","chapterUrl":"/c"}""".toByteArray()), file).status)
        assertEquals(400, send(meta, Part("file", ByteArray(0), "$id.cbz")).status, "empty file")
        assertEquals(400, plugin.request("POST", "/api/v1/karasu/shelf", metadata(id).toByteArray()).status, "not multipart")
        assertEquals(null, entry(id), "nothing of the refused requests may stay on the shelf")

        for (bad in listOf("abc", "1.5", "-1", "%2e%2e", "1%2F2", "..%2Fx", "9999999999999999999999")) {
            val delete = plugin.request("DELETE", "/api/v1/karasu/shelf/$bad", null)
            assertTrue(delete.status in setOf(400, 404), "DELETE $bad -> ${delete.status}")
            val get = plugin.request("GET", "/api/v1/karasu/shelf/$bad/file", null)
            assertTrue(get.status in setOf(400, 404), "GET $bad/file -> ${get.status}")
            val put = putRead(bad, readBody(true))
            assertTrue(put.status in setOf(400, 404), "PUT $bad/read -> ${put.status}")
        }
        // um id não numérico simples é 400 (e não só "não encontrado")
        assertEquals(400, plugin.delete("shelf/abc").status)
        assertEquals(400, plugin.get("shelf/abc/file").status)
        assertEquals(400, putRead("abc", readBody(true)).status)

        // corpo inválido no PUT read é 400; capítulo ausente é 404
        assertEquals(201, post(id, cbz(200)).status)
        for (body in listOf("", "x", "{}", """{"read":"true"}""", """{"read":1}""", "[]")) {
            assertEquals(400, putRead("$id", body.toByteArray()).status, "body '$body'")
        }
        assertEquals(false, read(id))
    }

    @Test
    fun `without credentials the shelf is refused`() {
        assumeTrue(ContractConfig.user.isNotEmpty(), "the server runs without authentication")
        val refused = setOf(401, 403, 404)
        val id = newId()
        assertTrue(post(id, cbz(200), withAuth = false).status in refused)
        assertEquals(null, entry(id), "an unauthenticated upload must not reach the shelf")

        assertEquals(201, post(id, cbz(200)).status)
        for ((method, path) in listOf(
            "GET" to "shelf",
            "GET" to "shelf/$id/file",
            "HEAD" to "shelf/$id/file",
            "PUT" to "shelf/$id/read",
            "DELETE" to "shelf/$id",
        )) {
            val body = if (method == "PUT") readBody(true) else null
            val r = plugin.request(method, "/api/v1/karasu/$path", body, withAuth = false)
            assertTrue(r.status in refused, "$method $path without credentials -> ${r.status}")
        }
        assertEquals(false, read(id))
        assertTrue(entry(id) != null, "an unauthenticated delete must not remove it")

        val intruder = ContractClient(null, ContractConfig.user, "wrong-" + ContractConfig.pass)
        assertTrue(intruder.get("shelf").status in refused)
    }
}
