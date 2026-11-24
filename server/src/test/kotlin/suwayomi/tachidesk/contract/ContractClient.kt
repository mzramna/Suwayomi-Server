package suwayomi.tachidesk.contract

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID

/** Where the server under test is. Set by `-PcontractUrl=http://127.0.0.1:4592 -PcontractUser= -PcontractPass=`. */
object ContractConfig {
    val url: String? =
        System
            .getProperty("contractUrl")
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
    val user: String = System.getProperty("contractUser").orEmpty()
    val pass: String = System.getProperty("contractPass").orEmpty()
    const val SOURCE_ID = "0"
}

class ContractResponse(
    val status: Int,
    val body: ByteArray,
    private val headers: Map<String, List<String>>,
) {
    val text: String get() = body.toString(Charsets.UTF_8)
    val json: JsonElement get() = Json.parseToJsonElement(text)
    val obj: JsonObject get() = json.jsonObject

    fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, true) }
            ?.value
            ?.firstOrNull()
}

/**
 * A scripted client of the server. [device] null is the WebUI (and anything else that does not identify itself);
 * a device id is the Karasu app, which sends `X-Karasu-Device` on every call, REST and GraphQL.
 */
class ContractClient(
    val device: String?,
    private val user: String = ContractConfig.user,
    private val pass: String = ContractConfig.pass,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val base = ContractConfig.url!!

    fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String? = "application/json",
        withAuth: Boolean = true,
    ): ContractResponse {
        val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
        if (withAuth && user.isNotEmpty()) {
            builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray()))
        }
        device?.let { builder.header("X-Karasu-Device", it) }
        if (body != null && contentType != null) builder.header("Content-Type", contentType)
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        return ContractResponse(response.statusCode(), response.body(), response.headers().map())
    }

    private fun karasu(path: String) = "/api/v1/karasu/$path"

    fun get(
        path: String,
        vararg query: Pair<String, Any?>,
    ) = request("GET", karasu(path + queryString(query)))

    fun head(path: String) = request("HEAD", karasu(path))

    fun delete(path: String) = request("DELETE", karasu(path))

    fun post(
        path: String,
        body: JsonElement,
    ) = request("POST", karasu(path), body.toString().toByteArray())

    fun put(
        path: String,
        body: JsonElement,
    ) = request("PUT", karasu(path), body.toString().toByteArray())

    fun postRaw(
        path: String,
        body: String,
    ) = request("POST", karasu(path), body.toByteArray())

    fun upload(
        fileName: String,
        content: ByteArray,
        deviceName: String?,
    ): ContractResponse {
        val boundary = "----contract" + UUID.randomUUID().toString().replace("-", "")
        val head =
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        val payload = head.toByteArray() + content + tail.toByteArray()
        val query = if (deviceName != null) "?device=" + URLEncoder.encode(deviceName, Charsets.UTF_8) else ""
        return request("POST", karasu("backups/upload$query"), payload, "multipart/form-data; boundary=$boundary")
    }

    private fun queryString(query: Array<out Pair<String, Any?>>): String =
        query
            .filter { it.second != null }
            .joinToString("&", prefix = if (query.any { it.second != null }) "?" else "") {
                it.first + "=" + URLEncoder.encode(it.second.toString(), Charsets.UTF_8)
            }

    // ---- GraphQL, with the documents the Karasu app writes (ServerGraphQl.kt) ------------------------------

    /** Runs one GraphQL operation as this client; returns `data`, failing on `errors`. */
    fun graphql(
        query: String,
        variables: JsonObject = JsonObject(emptyMap()),
    ): JsonObject {
        val payload =
            buildJsonObject {
                put("query", query)
                put("variables", variables)
            }
        val response = request("POST", "/api/graphql", payload.toString().toByteArray())
        check(response.status == 200) { "GraphQL HTTP ${response.status}: ${response.text.take(500)}" }
        val root = response.obj
        val errors = root["errors"]?.takeIf { it !is JsonNull }?.jsonArray
        check(errors == null || errors.isEmpty()) { "GraphQL errors: $errors" }
        return root["data"]!!.jsonObject
    }

    fun createCategory(name: String): Int =
        graphql(CREATE_CATEGORY, buildJsonObject { put("name", name) })["createCategory"]!!
            .jsonObject["category"]!!
            .jsonObject["id"]!!
            .jsonPrimitive.int

    fun renameCategory(
        id: Int,
        name: String,
    ): String =
        graphql(
            RENAME_CATEGORY,
            buildJsonObject {
                put("id", id)
                put("name", name)
            },
        )["updateCategory"]!!
            .jsonObject["category"]!!
            .jsonObject["name"]!!
            .jsonPrimitive.content

    /** The app's `deleteCategory` equivalent: the app never deletes, the WebUI does. */
    fun deleteCategory(id: Int) {
        graphql(DELETE_CATEGORY, buildJsonObject { put("id", id) })
    }

    fun categories(): Map<Int, String> =
        graphql(CATEGORIES)["categories"]!!
            .jsonObject["nodes"]!!
            .jsonArray
            .associate { it.jsonObject["id"]!!.jsonPrimitive.int to it.jsonObject["name"]!!.jsonPrimitive.content }

    /** The WebUI's and the app's `addExtensionStore`; returns the index url the server keeps for the store. */
    fun addExtensionStore(indexUrl: String): String =
        graphql(ADD_EXTENSION_STORE, buildJsonObject { put("indexUrl", indexUrl) })["addExtensionStore"]!!
            .jsonObject["extensionStore"]!!
            .jsonObject["indexUrl"]!!
            .jsonPrimitive.content

    fun removeExtensionStore(indexUrl: String) {
        graphql(REMOVE_EXTENSION_STORE, buildJsonObject { put("indexUrl", indexUrl) })
    }

    fun extensionStoreUrls(): Set<String> =
        graphql(EXTENSION_STORES)["extensionStores"]!!
            .jsonObject["nodes"]!!
            .jsonArray
            .map { it.jsonObject["indexUrl"]!!.jsonPrimitive.content }
            .toSet()

    fun updateMangaCategories(
        mangaId: Int,
        add: Collection<Int>,
        remove: Collection<Int>,
    ) {
        graphql(
            UPDATE_MANGA_CATEGORIES,
            buildJsonObject {
                put("id", mangaId)
                if (add.isNotEmpty()) put("add", JsonArray(add.map { JsonPrimitive(it) }))
                if (remove.isNotEmpty()) put("remove", JsonArray(remove.map { JsonPrimitive(it) }))
            },
        )
    }

    /** One chapter's new state, patched the way the app does: aliased `updateChapter` calls in one document. */
    fun updateChaptersLikeTheApp(patches: List<Triple<Int, Boolean, Int>>) {
        val variables = patches.size
        val declarations = (0 until variables).joinToString(", ") { "\$id$it: Int!, \$patch$it: UpdateChapterPatchInput!" }
        val calls =
            (0 until variables).joinToString(
                "\n",
            ) { "  c$it: updateChapter(input: {id: \$id$it, patch: \$patch$it}) { clientMutationId }" }
        val vars =
            buildJsonObject {
                patches.forEachIndexed { index, (id, read, page) ->
                    put("id$index", id)
                    put(
                        "patch$index",
                        buildJsonObject {
                            put("isRead", read)
                            put("lastPageRead", page)
                        },
                    )
                }
            }
        graphql("mutation KarasuUpdateChapters($declarations) {\n$calls\n}", vars)
    }

    /** The WebUI's way: one patch applied to many chapters. */
    fun updateChaptersLikeTheWeb(
        ids: List<Int>,
        patch: JsonObject,
    ) {
        graphql(
            "mutation(\$ids: [Int!]!, \$patch: UpdateChapterPatchInput!) { updateChapters(input: {ids: \$ids, patch: \$patch}) { clientMutationId } }",
            buildJsonObject {
                put("ids", JsonArray(ids.map { JsonPrimitive(it) }))
                put("patch", patch)
            },
        )
    }

    /** What the app reads back: the whole library with category links and per-chapter state, paged. */
    fun library(): List<LibraryManga> {
        val result = mutableListOf<LibraryManga>()
        var offset = 0
        while (true) {
            val page =
                graphql(
                    LIBRARY,
                    buildJsonObject {
                        put("first", 50)
                        put("offset", offset)
                    },
                )["mangas"]!!.jsonObject
            val nodes = page["nodes"]!!.jsonArray
            nodes.forEach { n ->
                val o = n.jsonObject
                result +=
                    LibraryManga(
                        id = o["id"]!!.jsonPrimitive.int,
                        sourceId = o["sourceId"]!!.jsonPrimitive.content,
                        url = o["url"]!!.jsonPrimitive.content,
                        categoryIds =
                            o["categories"]!!
                                .jsonObject["nodes"]!!
                                .jsonArray
                                .map { it.jsonObject["id"]!!.jsonPrimitive.int }
                                .toSet(),
                        chapters =
                            o["chapters"]!!.jsonObject["nodes"]!!.jsonArray.map {
                                val c = it.jsonObject
                                LibraryChapter(
                                    c["id"]!!.jsonPrimitive.int,
                                    c["url"]!!.jsonPrimitive.content,
                                    c["isRead"]!!.jsonPrimitive.content.toBoolean(),
                                    c["lastPageRead"]!!.jsonPrimitive.int,
                                    c["lastReadAt"]!!.jsonPrimitive.long,
                                )
                            },
                    )
            }
            if (nodes.isEmpty() ||
                !page["pageInfo"]!!
                    .jsonObject["hasNextPage"]!!
                    .jsonPrimitive.content
                    .toBoolean()
            ) {
                return result
            }
            offset += nodes.size
        }
    }

    fun manga(url: String): LibraryManga = library().first { it.url == url }

    // ---- the operation log ---------------------------------------------------------------------------------

    /** Seq of the newest operation: the baseline a test compares against, since the log is shared by all tests. */
    fun headSeq(): Long {
        var seq = 0L
        while (true) {
            val page = get("operations", "afterSeq" to seq, "limit" to 5000).obj
            if (page["operations"]!!.jsonArray.isEmpty()) return seq
            seq = page["lastSeq"]!!.jsonPrimitive.long
        }
    }

    fun operationsAfter(
        seq: Long,
        entity: String? = null,
    ): List<Op> {
        val result = mutableListOf<Op>()
        var cursor = seq
        while (true) {
            val response = get("operations", "afterSeq" to cursor, "entity" to entity, "limit" to 5000)
            check(response.status == 200) { "GET operations -> ${response.status} ${response.text}" }
            val page = response.obj
            val ops = page["operations"]!!.jsonArray.map { Op.of(it.jsonObject) }
            if (ops.isEmpty()) return result
            result += ops
            cursor = page["lastSeq"]!!.jsonPrimitive.long
        }
    }

    companion object {
        const val CATEGORIES = "query KarasuCategories { categories(orderBy: ORDER, orderByType: ASC) { nodes { id name order default } } }"
        const val CREATE_CATEGORY =
            "mutation KarasuCreateCategory(\$name: String!) { createCategory(input: {name: \$name}) { category { id name order default } } }"
        const val RENAME_CATEGORY =
            "mutation KarasuRenameCategory(\$id: Int!, \$name: String!) { updateCategory(input: {id: \$id, patch: {name: \$name}}) { category { id name order default } } }"
        const val DELETE_CATEGORY =
            "mutation(\$id: Int!) { deleteCategory(input: {categoryId: \$id}) { category { id } } }"
        const val UPDATE_MANGA_CATEGORIES =
            "mutation KarasuUpdateMangaCategories(\$id: Int!, \$add: [Int!], \$remove: [Int!]) { " +
                "updateMangaCategories(input: {id: \$id, patch: {addToCategories: \$add, removeFromCategories: \$remove}}) { clientMutationId } }"
        const val ADD_EXTENSION_STORE =
            "mutation KarasuAddExtensionStore(\$indexUrl: String!) { addExtensionStore(input: {indexUrl: \$indexUrl}) { extensionStore { indexUrl } } }"
        const val REMOVE_EXTENSION_STORE =
            "mutation KarasuRemoveExtensionStore(\$indexUrl: String!) { removeExtensionStore(input: {indexUrl: \$indexUrl}) { extensionStore { indexUrl } } }"
        const val EXTENSION_STORES = "query KarasuExtensionStores { extensionStores { nodes { indexUrl } } }"
        const val LIBRARY =
            "query KarasuLibrary(\$first: Int!, \$offset: Int!) { " +
                "mangas(filter: {inLibrary: {equalTo: true}}, orderBy: ID, orderByType: ASC, " +
                "first: \$first, offset: \$offset) { nodes { id sourceId url categories { nodes { id } } " +
                "chapters { nodes { id url isRead lastPageRead lastReadAt } } } pageInfo { hasNextPage } } }"
    }
}

data class LibraryChapter(
    val id: Int,
    val url: String,
    val isRead: Boolean,
    val lastPageRead: Int,
    val lastReadAt: Long,
)

data class LibraryManga(
    val id: Int,
    val sourceId: String,
    val url: String,
    val categoryIds: Set<Int>,
    val chapters: List<LibraryChapter>,
) {
    fun chapter(url: String) = chapters.first { it.url == url }
}

/** An operation as the server returns it. */
data class Op(
    val seq: Long,
    val id: String,
    val deviceId: String,
    val ts: Long,
    val entity: String,
    val key: String,
    val action: String,
    val value: JsonElement?,
) {
    /** "ENTITY ACTION key value", the shape the tests compare. */
    val summary: String get() = "$entity $action $key ${value ?: "null"}"

    companion object {
        fun of(o: JsonObject) =
            Op(
                seq = o["seq"]!!.jsonPrimitive.long,
                id = o["id"]!!.jsonPrimitive.content,
                deviceId = o["deviceId"]!!.jsonPrimitive.content,
                ts = o["ts"]!!.jsonPrimitive.long,
                entity = o["entity"]!!.jsonPrimitive.content,
                key = o["key"]!!.jsonPrimitive.content,
                action = o["action"]!!.jsonPrimitive.content,
                value = o["value"]?.takeIf { it !is JsonNull },
            )
    }
}

/** The body the app POSTs to /operations for something it did itself. */
fun appOperation(
    deviceId: String,
    ts: Long,
    entity: String,
    key: String,
    action: String,
    value: JsonElement? = null,
    id: String = UUID.randomUUID().toString(),
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("deviceId", deviceId)
        put("ts", ts)
        put("entity", entity)
        put("key", key)
        put("action", action)
        put("value", value ?: JsonNull)
        put("seq", 0)
    }
