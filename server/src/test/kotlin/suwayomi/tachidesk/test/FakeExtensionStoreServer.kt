package suwayomi.tachidesk.test

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * A tiny HTTP server that plays extension repositories (extension stores), so tests can add and remove stores
 * without internet. Paths that were not registered answer 404, which is how a store that cannot be fetched looks.
 */
class FakeExtensionStoreServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val bodies = java.util.concurrent.ConcurrentHashMap<String, String>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val body = bodies[exchange.requestURI.path]
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
    }

    /** A current-format store at `$baseUrl$path`; returns that URL (it is also the URL the server keeps). */
    fun store(
        path: String,
        name: String = "Store $path",
    ): String {
        bodies[path] =
            """{"name":"$name","badgeLabel":"S","signingKey":"key","contact":{"website":"https://example.test","discord":null},""" +
            """"extensionList":null,"extensionListUrl":null}"""
        return baseUrl + path
    }

    /**
     * A legacy repository in [dir]: asking for `index.min.json` redirects the server to `repo.json`, so a store
     * added with the first URL is kept under the second one. Returns the `repo.json` URL.
     */
    fun legacyStore(
        dir: String,
        name: String = "Legacy $dir",
    ): String {
        bodies["$dir/index.min.json"] = "[]"
        bodies["$dir/repo.json"] =
            """{"index_v2":null,"meta":{"name":"$name","shortName":"L","website":"https://example.test","signingKeyFingerprint":"abc"}}"""
        return "$baseUrl$dir/repo.json"
    }

    fun legacyIndexUrl(dir: String): String = "$baseUrl$dir/index.min.json"

    override fun close() = server.stop(0)
}
