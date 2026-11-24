package suwayomi.tachidesk.graphql

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import suwayomi.tachidesk.manga.impl.extension.ExtensionStoreService
import suwayomi.tachidesk.manga.impl.karasu.KarasuOperations
import suwayomi.tachidesk.manga.impl.karasu.KarasuRequestContext
import suwayomi.tachidesk.manga.impl.karasu.OperationEntryDto
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.KarasuOperationTable
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.FakeExtensionStoreServer
import suwayomi.tachidesk.test.GraphQLTest
import suwayomi.tachidesk.test.clearTables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The operation log of extension repositories (entity `EXTENSION_REPO`, key `repo:<indexUrl>`): adding and
 * removing a store through GraphQL, and the sync of the `extensionStores` setting to the database. Only EFFECTIVE
 * changes are logged, and only for requests that do not carry `X-Karasu-Device`.
 */
class KarasuExtensionRepoLogTest : GraphQLTest() {
    private val stores = FakeExtensionStoreServer()

    @AfterAll
    fun stopStores() = stores.close()

    @BeforeEach
    @AfterEach
    fun clean() {
        clearTables(KarasuOperationTable, ExtensionStoreTable)
        serverConfig.extensionStores.value = emptyList()
        KarasuRequestContext.clear()
    }

    private fun log(): List<OperationEntryDto> = KarasuOperations.list(0, "EXTENSION_REPO").operations

    private fun logLines() = log().map { "${it.entity} ${it.action} ${it.key} ${it.value ?: "null"}" }

    private fun setLine(url: String) = "EXTENSION_REPO SET repo:$url {\"indexUrl\":\"$url\"}"

    private fun removeLine(url: String) = "EXTENSION_REPO REMOVE repo:$url null"

    private fun storeUrls(): Set<String> = transaction { ExtensionStoreTable.selectAll().map { it[ExtensionStoreTable.indexUrl] }.toSet() }

    private fun addGql(
        url: String,
        device: String? = null,
    ) = graphql(
        "mutation(\$input: AddExtensionStoreInput!) { addExtensionStore(input: \$input) { extensionStore { indexUrl } } }",
        mapOf("input" to mapOf("indexUrl" to url)),
        device = device,
    )

    private fun removeGql(
        url: String,
        device: String? = null,
    ) = graphql(
        "mutation(\$input: RemoveExtensionStoreInput!) { removeExtensionStore(input: \$input) { extensionStore { indexUrl } } }",
        mapOf("input" to mapOf("indexUrl" to url)),
        device = device,
    ).also { it.assertNoErrors() }

    // ---- GraphQL mutations ----------------------------------------------------------------------------------

    @Test
    fun `adding and removing a store without the device header is logged as web with the contract's key and value`() {
        val url = stores.store("/a/index.json")
        val t0 = System.currentTimeMillis()

        val added = addGql(url)
        added.assertNoErrors()
        assertEquals(url, added.dataPath("addExtensionStore", "extensionStore", "indexUrl"))
        removeGql(url)

        assertEquals(listOf(setLine(url), removeLine(url)), logLines())
        val ops = log()
        assertTrue(ops.all { it.deviceId == "web" && it.ts >= t0 })
        assertTrue(ops[0].seq < ops[1].seq)
        assertTrue(storeUrls().isEmpty())
    }

    @Test
    fun `removing a store that does not exist logs nothing`() {
        removeGql("http://127.0.0.1:1/never/index.json")
        assertTrue(log().isEmpty())
    }

    @Test
    fun `adding a store that already exists logs nothing`() {
        val url = stores.store("/b/index.json")
        addGql(url).assertNoErrors()
        addGql(url).assertNoErrors()
        assertEquals(listOf(setLine(url)), logLines())
        assertEquals(setOf(url), storeUrls())

        // after a removal it is new again
        removeGql(url)
        removeGql(url)
        addGql(url).assertNoErrors()
        assertEquals(listOf(setLine(url), removeLine(url), setLine(url)), logLines())
    }

    @Test
    fun `a failed add logs nothing`() {
        addGql("http://127.0.0.1:1/offline/index.json").assertHasError()
        assertTrue(log().isEmpty())
    }

    @Test
    fun `a store added with a legacy index url is logged with the url the server keeps`() {
        val kept = stores.legacyStore("/legacy-add")
        addGql(stores.legacyIndexUrl("/legacy-add")).assertNoErrors()
        assertEquals(setOf(kept), storeUrls())
        assertEquals(listOf(setLine(kept)), logLines())
        removeGql(kept)
        assertEquals(listOf(setLine(kept), removeLine(kept)), logLines())
    }

    @Test
    fun `adding and removing a store with the device header is not logged by the server`() {
        val url = stores.store("/c/index.json")
        addGql(url, device = "pixel-8").assertNoErrors()
        assertEquals(setOf(url), storeUrls())
        removeGql(url, device = "pixel-8")
        assertTrue(storeUrls().isEmpty())
        assertTrue(log().isEmpty())

        // the same actions without the header are logged, so the header is what made the difference
        addGql(url).assertNoErrors()
        removeGql(url)
        assertEquals(2, log().size)
    }

    // ---- the extensionStores setting -> database ------------------------------------------------------------

    @Test
    fun `syncing the setting logs the stores that were really added and removed`() {
        val a = stores.store("/s/a.json")
        val b = stores.store("/s/b.json")
        val missing = "${stores.baseUrl}/s/missing.json"

        serverConfig.extensionStores.value = listOf(a)
        runBlocking { ExtensionStoreService.syncPrefsToDb() }
        assertEquals(listOf(setLine(a)), logLines())

        // nothing changed: nothing logged
        runBlocking { ExtensionStoreService.syncPrefsToDb() }
        assertEquals(1, log().size)

        // b is added; "missing" cannot be fetched, so it never entered the database
        serverConfig.extensionStores.value = listOf(a, b, missing)
        runBlocking { ExtensionStoreService.syncPrefsToDb() }
        assertEquals(setOf(a, b), storeUrls())
        assertEquals(listOf(setLine(a), setLine(b)), logLines())

        serverConfig.extensionStores.value = listOf(b)
        runBlocking { ExtensionStoreService.syncPrefsToDb() }
        assertEquals(listOf(setLine(a), setLine(b), removeLine(a)), logLines())
        assertTrue(log().all { it.deviceId == "web" })
    }

    @Test
    fun `the sync of the setting is not logged when the request carries the device header`() {
        val a = stores.store("/h/a.json")
        serverConfig.extensionStores.value = listOf(a)
        KarasuRequestContext.withDevice("pixel") { runBlocking { ExtensionStoreService.syncPrefsToDb() } }
        assertEquals(setOf(a), storeUrls())
        assertTrue(log().isEmpty())
    }

    @Test
    fun `adding a store through the setting with a legacy index url logs the final url once`() {
        val kept = stores.legacyStore("/legacy-pref")
        serverConfig.extensionStores.value = listOf(stores.legacyIndexUrl("/legacy-pref"))
        runBlocking { ExtensionStoreService.syncPrefsToDb() }
        assertEquals(setOf(kept), storeUrls())
        assertEquals(listOf(setLine(kept)), logLines())
        // the setting now holds the url the server keeps: nothing else to do
        assertEquals(listOf(kept), serverConfig.extensionStores.value)
        runBlocking { ExtensionStoreService.syncPrefsToDb() }
        assertEquals(1, log().size)
    }

    // ---- url swaps the server makes by itself ---------------------------------------------------------------

    @Test
    fun `a url the server swaps while syncing the setting is not a removal plus an addition`() {
        val kept = stores.legacyStore("/swap-pref")
        addGql(kept).assertNoErrors()
        clearTables(KarasuOperationTable)

        // the setting still names the store by its old index url; the server maps it to the stored repo.json
        serverConfig.extensionStores.value = listOf(stores.legacyIndexUrl("/swap-pref"))
        runBlocking { ExtensionStoreService.syncPrefsToDb() }

        assertEquals(setOf(kept), storeUrls())
        assertEquals(listOf(kept), serverConfig.extensionStores.value)
        assertTrue(log().isEmpty(), "got ${logLines()}")
    }

    @Test
    fun `a url the server swaps while refreshing the stores logs nothing`() {
        val kept = stores.legacyStore("/swap-refresh")
        val old = stores.legacyIndexUrl("/swap-refresh")
        // a store saved under its old index url, as an older server version kept it
        transaction {
            ExtensionStoreTable.insert {
                it[name] = "Old"
                it[badgeLabel] = "O"
                it[signingKey] = "abc"
                it[contactWebsite] = "https://example.test"
                it[contactDiscord] = null
                it[indexUrl] = old
                it[isLegacy] = true
                it[extensionListUrl] = null
            }
        }
        serverConfig.extensionStores.value = listOf(old)

        val refreshed = runBlocking { ExtensionStoreService.getAndRefresh() }

        assertEquals(listOf(kept), refreshed.map { it.indexUrl })
        assertEquals(setOf(kept), storeUrls())
        assertEquals(listOf(kept), serverConfig.extensionStores.value)
        assertTrue(log().isEmpty(), "got ${logLines()}")
        // and the setting, now equal to the database, has nothing left to sync
        runBlocking { ExtensionStoreService.syncPrefsToDb() }
        assertTrue(log().isEmpty())
    }
}
