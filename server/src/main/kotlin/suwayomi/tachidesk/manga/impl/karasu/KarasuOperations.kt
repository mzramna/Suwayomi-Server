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
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.model.table.KarasuDeviceTable
import suwayomi.tachidesk.manga.model.table.KarasuOperationTable
import suwayomi.tachidesk.manga.model.table.KarasuSeenOperationTable
import suwayomi.tachidesk.manga.model.table.KarasuSettingTable
import java.security.MessageDigest
import java.util.UUID

enum class KarasuEntity { CATEGORY, CATEGORY_LINK, PROGRESS, HISTORY, MERGED, DELETION, POLICY, BACKUP, EXTENSION_REPO }

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
    /**
     * Seq of the last returned operation, or the requested afterSeq when there is none (never below [minSeq] - 1: a
     * client that was told to reset continues from there).
     */
    val lastSeq: Long,
    val serverTime: Long,
    /**
     * Lowest `afterSeq + 1` for which the log is still complete: everything with a `seq` below it may have been
     * pruned (after every active device confirmed it, or by the 90 day ceiling). 1 while nothing was ever pruned.
     * It is NOT the lowest retained `seq`: compaction leaves holes (a superseded operation is replaced by a newer
     * one) that lose nothing.
     */
    val minSeq: Long = 1,
    /**
     * True when the caller may have missed operations that no longer exist: it asked for an `afterSeq` below
     * [minSeq] - 1, or it is a device that was inactive for longer than the TTL / new after the log was pruned, and
     * it has not confirmed (`ack`) the head of the log since. It must reconcile by state (not by the log) and then
     * confirm [lastSeq].
     */
    val resetRequired: Boolean = false,
)

/** One change waiting to be logged by [KarasuOperations.recordAll]. */
data class PendingOperation(
    val entity: KarasuEntity,
    val key: String,
    val action: KarasuAction,
    val value: JsonElement? = null,
)

/**
 * The operation log (contract v2/v3). The app publishes its own operations ([append]); the server logs by itself
 * ([record]) the effective changes made through requests that do NOT identify a device (WebUI and others), as
 * `deviceId = "web"`. A request that carries `X-Karasu-Device` is not logged here: the app publishes the same
 * change with the real time of the user's action, and logging it again would stamp it with the sync time.
 *
 * The log is the source of truth of what has to reach each device, and it only keeps what some device still has to
 * receive:
 *
 * - **Acknowledgement.** Every app installation is a device ([KarasuDeviceTable], created by its first request; `web`
 *   is never one). `GET /operations?ack=N` records that the device APPLIED everything up to `seq` N. [pruneAcked]
 *   deletes what every *active* device confirmed. Active = seen within [deviceTtlDays]; a silent device stops
 *   holding the log and, when it comes back, is told to reconcile by state (`resetRequired`).
 * - **Compaction on write.** Per target `(entity, key)` only the latest operation is kept (see [store]).
 * - **Ceiling.** [pruneOld] drops what was received more than [RETENTION_DAYS] days ago whatever the devices did.
 */
object KarasuOperations {
    private val logger = KotlinLogging.logger {}

    /** Ceiling: nothing stays in the log longer than this, acknowledged or not. */
    const val RETENTION_DAYS = 90L

    /** A device not seen for this long stops holding the log back (`karasu.deviceTtlDays` / `KARASU_DEVICE_TTL_DAYS`). */
    const val DEFAULT_DEVICE_TTL_DAYS = 30L
    const val MAX_LIST_LIMIT = 5000
    const val DEFAULT_LIST_LIMIT = 1000
    const val MAX_APPEND_SIZE = 10_000
    private const val MAX_KEY_LENGTH = 16_384
    private const val MAX_ID_LENGTH = 64
    private const val MAX_DEVICE_LENGTH = 128
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val CHUNK = 500
    private const val PRUNE_WATERMARK_SETTING = "operations.prunedUpTo"

    private val json = Json { encodeDefaults = true }

    /**
     * Compaction on write ([store]). Always on; only tests that check WHAT the server records (every change, in
     * order) rather than what the log keeps turn it off.
     */
    @Volatile
    internal var compactOnWrite = true

    /** Days a device may stay silent before it stops holding the log back. System property or environment variable, else 30. */
    fun deviceTtlDays(): Long =
        (System.getProperty("karasu.deviceTtlDays") ?: System.getenv("KARASU_DEVICE_TTL_DAYS"))
            ?.trim()
            ?.toLongOrNull()
            ?.coerceIn(1, RETENTION_DAYS)
            ?: DEFAULT_DEVICE_TTL_DAYS

    private fun deviceTtlMillis() = deviceTtlDays() * DAY_MILLIS

    /** The device id of a request, or null for the WebUI and anything that does not identify itself (`web` is never a device). */
    fun trackedDevice(raw: String?): String? =
        KarasuRequestContext.normalize(raw)?.takeIf { !it.equals(KarasuRequestContext.WEB_DEVICE, ignoreCase = true) }

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

    /** [indexUrl] is the URL the server stores for the extension store (the one `extensionStores` returns). */
    fun extensionRepoKey(indexUrl: String) = "repo:$indexUrl"

    fun extensionRepoValue(indexUrl: String): JsonObject = buildJsonObject { put("indexUrl", indexUrl) }

    const val POLICY_KEY = "policy"

    fun nameValue(name: String): JsonObject = buildJsonObject { put("name", name) }

    // -- writing ------------------------------------------------------------------------------------------

    /**
     * Logs one change. Returns whether the change was accepted (it may then be compacted with an older one).
     *
     * - [deviceId] null means "the device of the current request": when that request carries the
     *   `X-Karasu-Device` header nothing is logged (the app publishes it), otherwise it is logged as `web`.
     *   An explicit [deviceId] is always logged.
     * - [skipIfSameAsLast] drops a change identical to the last operation of the same key (same action and
     *   value): a no-op does not enter the log. Callers that already compared against the real state pass false.
     *   The comparison only sees what is still in the log: after the operation was pruned a repeated change is
     *   logged once more (harmless, applying it again changes nothing).
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

    /** Same as [record] for several changes made at the same moment; returns how many were accepted. */
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
                val incoming =
                    toWrite.map { op ->
                        Incoming(
                            id = UUID.randomUUID().toString(),
                            deviceId = device.take(MAX_DEVICE_LENGTH),
                            ts = ts,
                            entity = op.entity.name,
                            key = op.key,
                            keyHash = hash(op.key),
                            action = op.action.name,
                            value = op.value?.takeIf { it !is JsonNull }?.let(::encode),
                        )
                    }
                store(incoming, System.currentTimeMillis()).accepted
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
     * Stores the operations the app publishes. Idempotent by id: ids already known (retained, or compacted/pruned
     * earlier and remembered in `KarasuSeenOperation`), repeated in the batch, or older than what the log already
     * holds for the same target, count as duplicates. Nothing is applied to the server's state. Invalid input throws
     * [IllegalArgumentException] before anything is written. [deviceId] (the `X-Karasu-Device` of the request, if any)
     * is registered as a device and marked as seen now.
     */
    fun append(
        operations: List<OperationDto>,
        deviceId: String? = null,
    ): OperationsAppendResultDto {
        require(operations.size <= MAX_APPEND_SIZE) { "At most $MAX_APPEND_SIZE operations per request" }
        operations.forEach(::validate)
        val device = trackedDevice(deviceId)
        if (operations.isEmpty() && device == null) return OperationsAppendResultDto(0, 0)

        val unique = operations.distinctBy { it.id }
        return retryOnConflict {
            transaction {
                val now = System.currentTimeMillis()
                if (device != null) {
                    val watermark = readWatermark()
                    touchDevice(device, afterSeq = null, ack = null, now = now, watermark = watermark, head = headSeq(watermark))
                }
                val known =
                    unique
                        .map { it.id }
                        .chunked(CHUNK)
                        .flatMap { ids ->
                            KarasuOperationTable
                                .select(KarasuOperationTable.opId)
                                .where { KarasuOperationTable.opId inList ids }
                                .map { it[KarasuOperationTable.opId] } +
                                KarasuSeenOperationTable
                                    .select(KarasuSeenOperationTable.opId)
                                    .where { KarasuSeenOperationTable.opId inList ids }
                                    .map { it[KarasuSeenOperationTable.opId] }
                        }.toSet()
                val fresh =
                    unique.filter { it.id !in known }.map { op ->
                        Incoming(
                            id = op.id,
                            deviceId = op.deviceId,
                            ts = op.ts,
                            entity = op.entity,
                            key = op.key,
                            keyHash = hash(op.key),
                            action = op.action,
                            value = op.value?.takeIf { it !is JsonNull }?.let(::encode),
                        )
                    }
                val accepted = store(fresh, now).accepted
                OperationsAppendResultDto(accepted, operations.size - accepted)
            }
        }
    }

    private fun validate(op: OperationDto) {
        require(op.id.isNotBlank() && op.id.length <= MAX_ID_LENGTH) { "Invalid operation id" }
        require(op.deviceId.isNotBlank() && op.deviceId.length <= MAX_DEVICE_LENGTH) { "Invalid deviceId in operation ${op.id}" }
        require(op.ts >= 0) { "Invalid ts in operation ${op.id}" }
        require(KarasuEntity.entries.any { it.name == op.entity }) { "Unknown entity '${op.entity}' in operation ${op.id}" }
        require(KarasuAction.entries.any { it.name == op.action }) { "Unknown action '${op.action}' in operation ${op.id}" }
        require(op.key.isNotEmpty() && op.key.length <= MAX_KEY_LENGTH) { "Invalid key in operation ${op.id}" }
    }

    // -- compaction ---------------------------------------------------------------------------------------

    private class Incoming(
        val id: String,
        val deviceId: String,
        val ts: Long,
        val entity: String,
        val key: String,
        val keyHash: String,
        val action: String,
        val value: String?,
    )

    /** What the log holds (or is about to hold) for one target; [seq] is null for a row still to be inserted. */
    private class Held(
        val seq: Long?,
        val incoming: Incoming?,
        val opId: String,
        val ts: Long,
        val action: String,
    )

    private class StoreResult(
        val accepted: Int,
    )

    /**
     * Writes [incoming] compacting per target `(entity, key_hash)`, in the same transaction:
     *
     * - An operation whose `ts` is OLDER than one the log already holds for the target is discarded (not counted as
     *   accepted: the app reports it as a duplicate). So is a re-sent id that was compacted or pruned (see [append]).
     * - Otherwise it supersedes every held operation of the target (all have `ts <=` its own): SET over SET leaves
     *   the last SET, a SET over a REMOVE tombstone leaves the SET, PROGRESS/HISTORY keep only the last one.
     * - A REMOVE over a SET deletes the SET and keeps itself as a tombstone: another device may hold the target (the
     *   SET's effect, or an older state of it that was already pruned or came by reconciliation) and has to be told.
     *   The tombstone goes away once every active device confirmed it ([pruneAcked]). Only when no active device other
     *   than the one that made the REMOVE exists, nobody is left to tell: SET and REMOVE cancel out and neither is
     *   stored (a device that registers later reconciles by state, `resetRequired`). Deciding by "nobody saw the SET"
     *   would not be safe: a device that never saw it may hold an older version of the target.
     *
     * Every id that ends up not stored is remembered in `KarasuSeenOperation`. Must run inside a transaction.
     */
    private fun store(
        incoming: List<Incoming>,
        now: Long,
    ): StoreResult {
        if (incoming.isEmpty()) return StoreResult(0)
        if (!compactOnWrite) {
            insertRows(incoming, now)
            return StoreResult(incoming.size)
        }

        val held = HashMap<Pair<String, String>, MutableList<Held>>()
        incoming.groupBy({ it.entity }, { it.keyHash }).forEach { (entity, hashes) ->
            hashes.distinct().chunked(CHUNK).forEach { chunk ->
                KarasuOperationTable
                    .selectAll()
                    .where { (KarasuOperationTable.entity eq entity) and (KarasuOperationTable.keyHash inList chunk) }
                    .forEach { row ->
                        held
                            .getOrPut(entity to row[KarasuOperationTable.keyHash]) { mutableListOf() }
                            .add(
                                Held(
                                    row[KarasuOperationTable.id].value,
                                    null,
                                    row[KarasuOperationTable.opId],
                                    row[KarasuOperationTable.ts],
                                    row[KarasuOperationTable.action],
                                ),
                            )
                    }
            }
        }

        val activeDevices = activeDeviceIds(now)
        val toInsert = LinkedHashMap<String, Incoming>()
        val toDelete = LinkedHashSet<Long>()
        val remember = LinkedHashSet<String>()
        var accepted = 0

        for (op in incoming) {
            val rows = held.getOrPut(op.entity to op.keyHash) { mutableListOf() }
            val newest = rows.maxOfOrNull { it.ts }
            if (newest != null && op.ts < newest) {
                remember += op.id
                continue
            }
            accepted++
            val hadSet = rows.any { it.action == KarasuAction.SET.name }
            rows.forEach { old ->
                if (old.seq != null) toDelete += old.seq else toInsert.remove(old.opId)
                remember += old.opId
            }
            rows.clear()
            if (op.action == KarasuAction.REMOVE.name && hadSet && activeDevices.none { it != op.deviceId }) {
                remember += op.id
            } else {
                toInsert[op.id] = op
                rows += Held(null, op, op.id, op.ts, op.action)
            }
        }

        toDelete.chunked(CHUNK).forEach { ids -> KarasuOperationTable.deleteWhere { KarasuOperationTable.id inList ids } }
        insertRows(toInsert.values.toList(), now)
        rememberIds(remember, now)
        return StoreResult(accepted)
    }

    private fun insertRows(
        rows: List<Incoming>,
        now: Long,
    ) {
        if (rows.isEmpty()) return
        KarasuOperationTable.batchInsert(rows) { op ->
            this[KarasuOperationTable.opId] = op.id
            this[KarasuOperationTable.deviceId] = op.deviceId
            this[KarasuOperationTable.ts] = op.ts
            this[KarasuOperationTable.entity] = op.entity
            this[KarasuOperationTable.key] = op.key
            this[KarasuOperationTable.keyHash] = op.keyHash
            this[KarasuOperationTable.action] = op.action
            this[KarasuOperationTable.value] = op.value
            this[KarasuOperationTable.receivedAt] = now
        }
    }

    private fun rememberIds(
        ids: Collection<String>,
        now: Long,
    ) {
        if (ids.isEmpty()) return
        val already =
            ids
                .chunked(CHUNK)
                .flatMap { chunk ->
                    KarasuSeenOperationTable
                        .select(KarasuSeenOperationTable.opId)
                        .where { KarasuSeenOperationTable.opId inList chunk }
                        .map { it[KarasuSeenOperationTable.opId] }
                }.toSet()
        val fresh = ids.filter { it !in already }
        if (fresh.isEmpty()) return
        KarasuSeenOperationTable.batchInsert(fresh) { id ->
            this[KarasuSeenOperationTable.opId] = id
            this[KarasuSeenOperationTable.seenAt] = now
        }
    }

    /**
     * One-off compaction of a log written before compaction existed: per target only the latest operation by `ts`
     * (then by `seq`) stays. Cheap when there is nothing to do; run on startup. Returns how many rows went away.
     */
    fun compactExisting(now: Long = System.currentTimeMillis()): Int =
        transaction {
            val groups =
                KarasuOperationTable
                    .select(KarasuOperationTable.entity, KarasuOperationTable.keyHash)
                    .groupBy(KarasuOperationTable.entity, KarasuOperationTable.keyHash)
                    .having { KarasuOperationTable.id.count() greater 1L }
                    .map { it[KarasuOperationTable.entity] to it[KarasuOperationTable.keyHash] }
            var removed = 0
            groups.forEach { (entity, keyHash) ->
                val rows =
                    KarasuOperationTable
                        .selectAll()
                        .where { (KarasuOperationTable.entity eq entity) and (KarasuOperationTable.keyHash eq keyHash) }
                        .orderBy(KarasuOperationTable.ts to SortOrder.DESC, KarasuOperationTable.id to SortOrder.DESC)
                        .map { it[KarasuOperationTable.id].value to it[KarasuOperationTable.opId] }
                val superseded = rows.drop(1)
                superseded.map { it.first }.chunked(CHUNK).forEach { ids ->
                    KarasuOperationTable.deleteWhere { KarasuOperationTable.id inList ids }
                }
                rememberIds(superseded.map { it.second }, now)
                removed += superseded.size
            }
            removed
        }

    // -- devices ------------------------------------------------------------------------------------------

    private fun readWatermark(): Long =
        KarasuSettingTable
            .selectAll()
            .where { KarasuSettingTable.key eq PRUNE_WATERMARK_SETTING }
            .firstOrNull()
            ?.get(KarasuSettingTable.value)
            ?.toLongOrNull() ?: 0L

    private fun bumpWatermark(deletedUpTo: Long) {
        val current =
            KarasuSettingTable
                .selectAll()
                .where { KarasuSettingTable.key eq PRUNE_WATERMARK_SETTING }
                .firstOrNull()
        if (current == null) {
            KarasuSettingTable.insert {
                it[key] = PRUNE_WATERMARK_SETTING
                it[value] = deletedUpTo.toString()
            }
        } else if ((current[KarasuSettingTable.value].toLongOrNull() ?: 0L) < deletedUpTo) {
            KarasuSettingTable.update({ KarasuSettingTable.key eq PRUNE_WATERMARK_SETTING }) { it[value] = deletedUpTo.toString() }
        }
    }

    /** Highest seq the log can have handed out and still matters: the newest retained operation or the pruned mark. */
    private fun headSeq(watermark: Long): Long {
        val maxId = KarasuOperationTable.id.max()
        val retained =
            KarasuOperationTable
                .select(maxId)
                .firstOrNull()
                ?.get(maxId)
                ?.value ?: 0L
        return maxOf(retained, watermark)
    }

    /** The devices that count: seen within the TTL. */
    private fun activeDeviceIds(now: Long): Set<String> =
        KarasuDeviceTable
            .select(KarasuDeviceTable.deviceId)
            .where { KarasuDeviceTable.lastSeenAt greaterEq now - deviceTtlMillis() }
            .map { it[KarasuDeviceTable.deviceId] }
            .toSet()

    /**
     * Creates or refreshes [device] and applies [ack]. Returns whether the device has to reconcile by state: it is new
     * and the log was pruned already, it was silent for longer than the TTL, or its cursor ([afterSeq]) is behind the
     * pruned part; in those cases the head of the log is stored as `reset_seq` (the ack of that very request is
     * ignored, the state of the device is not trustworthy) and the flag stays until the device confirms it.
     */
    private fun touchDevice(
        device: String,
        afterSeq: Long?,
        ack: Long?,
        now: Long,
        watermark: Long,
        head: Long,
    ): Boolean {
        val existing =
            KarasuDeviceTable
                .selectAll()
                .where { KarasuDeviceTable.deviceId eq device }
                .firstOrNull()
        if (existing == null) {
            val resetSeq = if (watermark > 0) head else 0L
            KarasuDeviceTable.insert {
                it[deviceId] = device
                it[ackSeq] = 0L
                it[KarasuDeviceTable.resetSeq] = resetSeq
                it[lastSeenAt] = now
            }
            return resetSeq > 0
        }
        val untrusted = now - existing[KarasuDeviceTable.lastSeenAt] > deviceTtlMillis() || (afterSeq != null && afterSeq < watermark)
        val resetSeq = if (untrusted) maxOf(existing[KarasuDeviceTable.resetSeq], head) else existing[KarasuDeviceTable.resetSeq]
        val previousAck = existing[KarasuDeviceTable.ackSeq]
        val newAck = if (untrusted || ack == null) previousAck else maxOf(previousAck, ack.coerceIn(0L, head))
        KarasuDeviceTable.update({ KarasuDeviceTable.deviceId eq device }) {
            it[ackSeq] = newAck
            it[KarasuDeviceTable.resetSeq] = resetSeq
            it[lastSeenAt] = now
        }
        return newAck < resetSeq
    }

    private fun <T> retryOnConflict(block: () -> T): T =
        try {
            block()
        } catch (e: org.jetbrains.exposed.v1.exceptions.ExposedSQLException) {
            // a concurrent request created the same device (or inserted one of the ids) between our check and our
            // insert: look again, now it is there
            block()
        }

    // -- reading ------------------------------------------------------------------------------------------

    /**
     * Operations after [afterSeq] ordered by seq. [entity] null/blank means every entity.
     *
     * With a [deviceId] (the `X-Karasu-Device` of the request; `web` and blank are ignored) the device is registered
     * and marked as seen, [ack] (everything up to that `seq` was APPLIED by the device; it never moves back) is stored
     * and the log is pruned right away, and `resetRequired` also covers a device that must reconcile by state.
     */
    fun list(
        afterSeq: Long = 0,
        entity: String? = null,
        limit: Int = DEFAULT_LIST_LIMIT,
        deviceId: String? = null,
        ack: Long? = null,
    ): OperationsListDto {
        val entityFilter = entity?.trim()?.takeIf { it.isNotEmpty() }
        require(entityFilter == null || KarasuEntity.entries.any { it.name == entityFilter }) { "Unknown entity '$entityFilter'" }
        val max = limit.coerceIn(1, MAX_LIST_LIMIT)
        val device = trackedDevice(deviceId)
        return retryOnConflict {
            transaction {
                val now = System.currentTimeMillis()
                val watermarkBefore = readWatermark()
                val deviceMustReset =
                    device != null &&
                        touchDevice(device, afterSeq, ack, now, watermarkBefore, headSeq(watermarkBefore))
                if (device != null && ack != null) pruneInTransaction(now)
                val watermark = readWatermark()
                val rows =
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
                OperationsListDto(
                    operations = rows,
                    lastSeq = rows.lastOrNull()?.seq ?: maxOf(afterSeq, watermark),
                    serverTime = System.currentTimeMillis(),
                    minSeq = watermark + 1,
                    resetRequired = deviceMustReset || afterSeq < watermarkBefore,
                )
            }
        }
    }

    // -- pruning ------------------------------------------------------------------------------------------

    /**
     * Drops what was received more than [RETENTION_DAYS] days ago (the ceiling, acknowledged or not). Returns how many
     * rows went away.
     */
    fun pruneOld(now: Long = System.currentTimeMillis()): Int =
        transaction {
            deleteOperations(now) {
                KarasuOperationTable.receivedAt less
                    now - RETENTION_DAYS * DAY_MILLIS
            }
        }

    /**
     * Drops what every active device confirmed (`seq <= min(ack)`; with no active device nothing is dropped here) and
     * what passed the [RETENTION_DAYS] ceiling, forgets devices silent for longer than the ceiling and remembered ids
     * older than the device TTL. Returns how many operations went away.
     */
    fun pruneAcked(now: Long = System.currentTimeMillis()): Int = transaction { pruneInTransaction(now) }

    private fun pruneInTransaction(now: Long): Int {
        val ttl = deviceTtlMillis()
        val bound =
            KarasuDeviceTable
                .selectAll()
                .where { KarasuDeviceTable.lastSeenAt greaterEq now - ttl }
                .minOfOrNull { maxOf(it[KarasuDeviceTable.ackSeq], it[KarasuDeviceTable.resetSeq]) }
        var removed = 0
        if (bound != null && bound > 0) removed += deleteOperations(now) { KarasuOperationTable.id lessEq bound }
        removed += deleteOperations(now) { KarasuOperationTable.receivedAt less now - RETENTION_DAYS * DAY_MILLIS }
        KarasuDeviceTable.deleteWhere { KarasuDeviceTable.lastSeenAt less now - RETENTION_DAYS * DAY_MILLIS }
        KarasuSeenOperationTable.deleteWhere { KarasuSeenOperationTable.seenAt less now - ttl }
        return removed
    }

    /** Deletes the rows matching [condition], remembering their ids and moving the pruned mark. */
    private fun deleteOperations(
        now: Long,
        condition: () -> Op<Boolean>,
    ): Int {
        val doomed =
            KarasuOperationTable
                .select(KarasuOperationTable.id, KarasuOperationTable.opId)
                .where(condition())
                .map { it[KarasuOperationTable.id].value to it[KarasuOperationTable.opId] }
        if (doomed.isEmpty()) return 0
        doomed.map { it.first }.chunked(CHUNK).forEach { ids -> KarasuOperationTable.deleteWhere { KarasuOperationTable.id inList ids } }
        rememberIds(doomed.map { it.second }, now)
        bumpWatermark(doomed.maxOf { it.first })
        return doomed.size
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
