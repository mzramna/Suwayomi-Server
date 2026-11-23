package suwayomi.tachidesk.manga.impl.karasu

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.table.KarasuOperationTable
import java.security.MessageDigest
import java.util.UUID

enum class KarasuEntity { CATEGORY, CATEGORY_LINK, PROGRESS, HISTORY, MERGED, DELETION, POLICY, BACKUP }

enum class KarasuAction { SET, REMOVE }

@Serializable
data class OperationDto(
    val id: String,
    val deviceId: String,
    /** Epoch millis UTC of when the change was made. */
    val ts: Long,
    val entity: String,
    val key: String,
    val action: String,
    val value: JsonElement? = null,
)

@Serializable
data class OperationEntryDto(
    val seq: Long,
    val id: String,
    val deviceId: String,
    val ts: Long,
    val entity: String,
    val key: String,
    val action: String,
    val value: JsonElement? = null,
)

@Serializable
data class OperationsPostDto(
    val operations: List<OperationDto> = emptyList(),
)

@Serializable
data class OperationsAppendResultDto(
    val accepted: Int,
    val duplicates: Int,
)

@Serializable
data class OperationsListDto(
    val operations: List<OperationEntryDto>,
    /** Seq of the last returned operation, or the requested afterSeq when there is none. */
    val lastSeq: Long,
    val serverTime: Long,
)

/** One change waiting to be logged by [KarasuOperations.recordAll]. */
data class PendingOperation(
    val entity: KarasuEntity,
    val key: String,
    val action: KarasuAction,
    val value: JsonElement? = null,
)

/**
 * The operation log (contract v2). The app publishes its own operations ([append]); the server logs by itself
 * ([record]) the effective changes made through requests that do NOT identify a device (WebUI and others), as
 * `deviceId = "web"`. A request that carries `X-Karasu-Device` is not logged here: the app publishes the same
 * change with the real time of the user's action, and logging it again would stamp it with the sync time.
 */
object KarasuOperations {
    private val logger = KotlinLogging.logger {}

    const val RETENTION_DAYS = 90L
    const val MAX_LIST_LIMIT = 5000
    const val DEFAULT_LIST_LIMIT = 1000
    const val MAX_APPEND_SIZE = 10_000
    private const val MAX_KEY_LENGTH = 16_384
    private const val MAX_ID_LENGTH = 64
    private const val MAX_DEVICE_LENGTH = 128

    private val json = Json { encodeDefaults = true }

    // -- key / value helpers, so every writer builds them the same way ----------------------------------

    fun categoryKey(categoryId: Int) = "category:$categoryId"

    fun linkKey(
        sourceId: Long,
        mangaUrl: String,
        categoryId: Int,
    ) = "link:$sourceId|$mangaUrl|$categoryId"

    fun chapterKey(
        prefix: String,
        sourceId: Long,
        mangaUrl: String,
        chapterUrl: String,
    ) = "$prefix:$sourceId|$mangaUrl|$chapterUrl"

    fun mergedKey(
        sourceId: Long,
        mangaUrl: String,
    ) = "merged:$sourceId|$mangaUrl"

    fun backupKey(id: String) = "backup:$id"

    const val POLICY_KEY = "policy"

    fun nameValue(name: String): JsonObject = buildJsonObject { put("name", name) }

    // -- writing ------------------------------------------------------------------------------------------

    /**
     * Logs one change. Returns whether a row was written.
     *
     * - [deviceId] null means "the device of the current request": when that request carries the
     *   `X-Karasu-Device` header nothing is logged (the app publishes it), otherwise it is logged as `web`.
     *   An explicit [deviceId] is always logged.
     * - [skipIfSameAsLast] drops a change identical to the last operation of the same key (same action and
     *   value): a no-op does not enter the log. Callers that already compared against the real state pass false.
     */
    fun record(
        entity: KarasuEntity,
        key: String,
        action: KarasuAction,
        value: JsonElement? = null,
        ts: Long = System.currentTimeMillis(),
        deviceId: String? = null,
        skipIfSameAsLast: Boolean = true,
    ): Boolean = recordAll(listOf(PendingOperation(entity, key, action, value)), ts, deviceId, skipIfSameAsLast) > 0

    /** Same as [record] for several changes made at the same moment; returns how many rows were written. */
    fun recordAll(
        operations: List<PendingOperation>,
        ts: Long = System.currentTimeMillis(),
        deviceId: String? = null,
        skipIfSameAsLast: Boolean = true,
    ): Int {
        if (operations.isEmpty()) return 0
        val device =
            if (deviceId != null) {
                deviceId
            } else if (KarasuRequestContext.device() != null) {
                return 0
            } else {
                KarasuRequestContext.WEB_DEVICE
            }
        return try {
            transaction {
                val lastByKey = if (skipIfSameAsLast) lastOperations(operations) else emptyMap()
                val seen = HashMap<Pair<String, String>, PendingOperation>()
                val toWrite =
                    operations.filter { op ->
                        val id = op.entity.name to op.key
                        val previous = seen[id] ?: lastByKey[id]
                        seen[id] = op
                        !(skipIfSameAsLast && previous != null && previous.action == op.action && sameValue(previous.value, op.value))
                    }
                val now = System.currentTimeMillis()
                KarasuOperationTable.batchInsert(toWrite) { op ->
                    this[KarasuOperationTable.opId] = UUID.randomUUID().toString()
                    this[KarasuOperationTable.deviceId] = device.take(MAX_DEVICE_LENGTH)
                    this[KarasuOperationTable.ts] = ts
                    this[KarasuOperationTable.entity] = op.entity.name
                    this[KarasuOperationTable.key] = op.key
                    this[KarasuOperationTable.keyHash] = hash(op.key)
                    this[KarasuOperationTable.action] = op.action.name
                    this[KarasuOperationTable.value] = op.value?.takeIf { it !is JsonNull }?.let(::encode)
                    this[KarasuOperationTable.receivedAt] = now
                }
                toWrite.size
            }
        } catch (e: Exception) {
            // the log must never break the change that is being logged
            logger.warn(e) { "Could not record ${operations.size} karasu operation(s)" }
            0
        }
    }

    private fun lastOperations(operations: List<PendingOperation>): Map<Pair<String, String>, PendingOperation> {
        val wanted = operations.map { it.entity.name to it.key }.toSet()
        val result = HashMap<Pair<String, String>, PendingOperation>()
        wanted.groupBy({ it.first }, { it.second }).forEach { (entity, keys) ->
            val hashes = keys.map(::hash)
            KarasuOperationTable
                .selectAll()
                .where { (KarasuOperationTable.entity eq entity) and (KarasuOperationTable.keyHash inList hashes) }
                .orderBy(KarasuOperationTable.id to SortOrder.DESC)
                .forEach { row ->
                    val key = row[KarasuOperationTable.key]
                    val id = entity to key
                    if (key in keys && id !in result) {
                        result[id] =
                            PendingOperation(
                                KarasuEntity.valueOf(entity),
                                key,
                                KarasuAction.valueOf(row[KarasuOperationTable.action]),
                                row[KarasuOperationTable.value]?.let(::decode),
                            )
                    }
                }
        }
        return result
    }

    /**
     * Stores the operations the app publishes. Idempotent by id: ids already known (or repeated in the batch)
     * count as duplicates. Nothing is applied to the server's state. Invalid input throws
     * [IllegalArgumentException] before anything is written.
     */
    fun append(operations: List<OperationDto>): OperationsAppendResultDto {
        require(operations.size <= MAX_APPEND_SIZE) { "At most $MAX_APPEND_SIZE operations per request" }
        operations.forEach(::validate)
        if (operations.isEmpty()) return OperationsAppendResultDto(0, 0)

        val unique = operations.distinctBy { it.id }
        var accepted = 0
        repeat(2) { attempt ->
            try {
                accepted =
                    transaction {
                        val known =
                            unique
                                .map { it.id }
                                .chunked(1000)
                                .flatMap { ids ->
                                    KarasuOperationTable
                                        .select(KarasuOperationTable.opId)
                                        .where { KarasuOperationTable.opId inList ids }
                                        .map { it[KarasuOperationTable.opId] }
                                }.toSet()
                        val fresh = unique.filter { it.id !in known }
                        val now = System.currentTimeMillis()
                        KarasuOperationTable.batchInsert(fresh) { op ->
                            this[KarasuOperationTable.opId] = op.id
                            this[KarasuOperationTable.deviceId] = op.deviceId
                            this[KarasuOperationTable.ts] = op.ts
                            this[KarasuOperationTable.entity] = op.entity
                            this[KarasuOperationTable.key] = op.key
                            this[KarasuOperationTable.keyHash] = hash(op.key)
                            this[KarasuOperationTable.action] = op.action
                            this[KarasuOperationTable.value] = op.value?.takeIf { it !is JsonNull }?.let(::encode)
                            this[KarasuOperationTable.receivedAt] = now
                        }
                        fresh.size
                    }
                return OperationsAppendResultDto(accepted, operations.size - accepted)
            } catch (e: org.jetbrains.exposed.v1.exceptions.ExposedSQLException) {
                // a concurrent request inserted one of the ids between our check and our insert: look again
                if (attempt == 1) throw e
            }
        }
        return OperationsAppendResultDto(accepted, operations.size - accepted)
    }

    private fun validate(op: OperationDto) {
        require(op.id.isNotBlank() && op.id.length <= MAX_ID_LENGTH) { "Invalid operation id" }
        require(op.deviceId.isNotBlank() && op.deviceId.length <= MAX_DEVICE_LENGTH) { "Invalid deviceId in operation ${op.id}" }
        require(op.ts >= 0) { "Invalid ts in operation ${op.id}" }
        require(KarasuEntity.entries.any { it.name == op.entity }) { "Unknown entity '${op.entity}' in operation ${op.id}" }
        require(KarasuAction.entries.any { it.name == op.action }) { "Unknown action '${op.action}' in operation ${op.id}" }
        require(op.key.isNotEmpty() && op.key.length <= MAX_KEY_LENGTH) { "Invalid key in operation ${op.id}" }
    }

    // -- reading ------------------------------------------------------------------------------------------

    /** Operations after [afterSeq] ordered by seq. [entity] null/blank means every entity. */
    fun list(
        afterSeq: Long = 0,
        entity: String? = null,
        limit: Int = DEFAULT_LIST_LIMIT,
    ): OperationsListDto {
        val entityFilter = entity?.trim()?.takeIf { it.isNotEmpty() }
        require(entityFilter == null || KarasuEntity.entries.any { it.name == entityFilter }) { "Unknown entity '$entityFilter'" }
        val max = limit.coerceIn(1, MAX_LIST_LIMIT)
        val rows =
            transaction {
                KarasuOperationTable
                    .selectAll()
                    .where {
                        val after = KarasuOperationTable.id greater afterSeq
                        if (entityFilter != null) after and (KarasuOperationTable.entity eq entityFilter) else after
                    }.orderBy(KarasuOperationTable.id to SortOrder.ASC)
                    .limit(max)
                    .map {
                        OperationEntryDto(
                            seq = it[KarasuOperationTable.id].value,
                            id = it[KarasuOperationTable.opId],
                            deviceId = it[KarasuOperationTable.deviceId],
                            ts = it[KarasuOperationTable.ts],
                            entity = it[KarasuOperationTable.entity],
                            key = it[KarasuOperationTable.key],
                            action = it[KarasuOperationTable.action],
                            value = it[KarasuOperationTable.value]?.let(::decode),
                        )
                    }
            }
        return OperationsListDto(rows, rows.lastOrNull()?.seq ?: afterSeq, System.currentTimeMillis())
    }

    /** Drops what was received more than [RETENTION_DAYS] days ago. Returns how many rows went away. */
    fun pruneOld(now: Long = System.currentTimeMillis()): Int {
        val cutoff = now - RETENTION_DAYS * 24 * 60 * 60 * 1000
        return transaction { KarasuOperationTable.deleteWhere { KarasuOperationTable.receivedAt less cutoff } }
    }

    // -- helpers ------------------------------------------------------------------------------------------

    private fun encode(value: JsonElement): String = json.encodeToString(JsonElement.serializer(), value)

    private fun decode(text: String): JsonElement = json.parseToJsonElement(text)

    private fun sameValue(
        a: JsonElement?,
        b: JsonElement?,
    ): Boolean = (a ?: JsonNull) == (b ?: JsonNull)

    private fun hash(key: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
