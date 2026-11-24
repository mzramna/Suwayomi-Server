package suwayomi.tachidesk.graphql.mutations

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.directives.RequirePermissions
import suwayomi.tachidesk.graphql.types.ExtensionStoreType
import suwayomi.tachidesk.manga.impl.extension.ExtensionStoreService
import suwayomi.tachidesk.manga.impl.karasu.KarasuRecorder
import suwayomi.tachidesk.manga.impl.karasu.KarasuRequestContext
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.UserPermission
import java.util.concurrent.CompletableFuture

class ExtensionStoreMutation {
    data class AddExtensionStoreInput(
        val clientMutationId: String? = null,
        val indexUrl: String,
    )

    data class AddExtensionStorePayload(
        val clientMutationId: String?,
        val extensionStore: ExtensionStoreType,
    )

    @RequireAuth
    @RequirePermissions(UserPermission.MANAGE_EXTENSION_STORES)
    fun addExtensionStore(input: AddExtensionStoreInput): CompletableFuture<AddExtensionStorePayload?> {
        val (clientMutationId, indexUrl) = input
        // `future` runs on another thread, where the request's device is not visible: read it here
        val device = KarasuRequestContext.device()
        return future {
            val store = ExtensionStoreService.fetch(indexUrl)

            // the log only wants effective changes: adding a store that is already there is a refresh
            val isNew =
                device == null &&
                    transaction {
                        ExtensionStoreTable.selectAll().where { ExtensionStoreTable.indexUrl eq store.indexUrl }.firstOrNull() == null
                    }
            ExtensionStoreService.upsert(store)
            ExtensionStoreService.syncDbToPrefs()
            if (isNew) KarasuRequestContext.withDevice(device) { KarasuRecorder.extensionRepoAdded(store.indexUrl) }
            val row =
                transaction {
                    ExtensionStoreTable
                        .selectAll()
                        .where { ExtensionStoreTable.indexUrl eq store.indexUrl }
                        .first()
                }

            AddExtensionStorePayload(
                clientMutationId = clientMutationId,
                extensionStore = ExtensionStoreType(row),
            )
        }
    }

    data class RemoveExtensionStoreInput(
        val clientMutationId: String? = null,
        val indexUrl: String,
    )

    data class RemoveExtensionStorePayload(
        val clientMutationId: String?,
        val extensionStore: ExtensionStoreType?,
    )

    @RequireAuth
    @RequirePermissions(UserPermission.MANAGE_EXTENSION_STORES)
    fun removeExtensionStore(input: RemoveExtensionStoreInput): CompletableFuture<RemoveExtensionStorePayload?> {
        val (clientMutationId, indexUrl) = input
        val device = KarasuRequestContext.device()
        return future {
            val store =
                transaction {
                    ExtensionStoreTable
                        .selectAll()
                        .where { ExtensionStoreTable.indexUrl eq indexUrl }
                        .firstOrNull()
                        ?.let { ExtensionStoreType(it) }
                }

            store?.let {
                transaction {
                    ExtensionStoreTable.deleteWhere { ExtensionStoreTable.indexUrl eq indexUrl }
                }
            }

            ExtensionStoreService.syncDbToPrefs()
            // only a store that was there is "removed"
            if (store != null) KarasuRequestContext.withDevice(device) { KarasuRecorder.extensionRepoRemoved(indexUrl) }

            RemoveExtensionStorePayload(
                clientMutationId = clientMutationId,
                extensionStore =
                    store?.let {
                        ExtensionStoreType(
                            name = it.name,
                            badgeLabel = it.badgeLabel,
                            signingKey = it.signingKey,
                            contactWebsite = it.contactWebsite,
                            contactDiscord = it.contactDiscord,
                            indexUrl = it.indexUrl,
                            isLegacy = it.isLegacy,
                            extensionListUrl = it.extensionListUrl,
                        )
                    },
            )
        }
    }
}
