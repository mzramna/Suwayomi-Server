package suwayomi.tachidesk.manga.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.http.Context
import io.javalin.http.HandlerType
import io.javalin.http.HttpStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.ChapterDownloadHelper
import suwayomi.tachidesk.manga.impl.karasu.DeletionRequestDto
import suwayomi.tachidesk.manga.impl.karasu.HistoryRequestDto
import suwayomi.tachidesk.manga.impl.karasu.KarasuBackups
import suwayomi.tachidesk.manga.impl.karasu.KarasuHealth
import suwayomi.tachidesk.manga.impl.karasu.KarasuOperations
import suwayomi.tachidesk.manga.impl.karasu.KarasuRequestContext
import suwayomi.tachidesk.manga.impl.karasu.KarasuSync
import suwayomi.tachidesk.manga.impl.karasu.MangaRefDto
import suwayomi.tachidesk.manga.impl.karasu.MergedGroupDto
import suwayomi.tachidesk.manga.impl.karasu.MergedSourceDto
import suwayomi.tachidesk.manga.impl.karasu.MergedSources
import suwayomi.tachidesk.manga.impl.karasu.OperationsPostDto
import suwayomi.tachidesk.manga.impl.karasu.PolicyDto
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.requireUser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

@Serializable
private data class ErrorDto(
    val error: String,
)

@Serializable
private data class MergedPutDto(
    val sources: List<MergedSourceDto> = emptyList(),
)

@Serializable
private data class MergedPutByRefDto(
    val primary: MangaRefDto,
    val sources: List<MergedSourceDto> = emptyList(),
)

/**
 * REST endpoints used by the Karasu app and by the WebUI fork. See docs/karasu-integration.md.
 * Everything requires an authenticated user, like the rest of /api/v1.
 */
object KarasuController {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private inline fun <reified T> Context.respond(
        value: T,
        status: HttpStatus = HttpStatus.OK,
    ) {
        status(status)
        contentType("application/json")
        result(json.encodeToString(value))
    }

    private fun Context.fail(
        status: HttpStatus,
        message: String,
    ) = respond(ErrorDto(message), status)

    private fun Context.user(): Int = getAttribute(Attribute.TachideskUser).requireUser()

    private inline fun <reified T> Context.readBody(): T? =
        try {
            json.decodeFromString<T>(requestText() ?: return null)
        } catch (_: Exception) {
            fail(HttpStatus.BAD_REQUEST, "Invalid JSON body")
            null
        }

    /**
     * The request body as text. Javalin does not decompress requests, so `Content-Encoding: gzip` (what the app sends
     * for big batches of operations) is undone here; the inflated size is capped so a tiny body cannot blow up
     * memory. Any other encoding is a 415, a body that is not valid gzip or too big a 400 (null is returned and the
     * response is already filled in).
     */
    private fun Context.requestText(): String? {
        val encoding = header("Content-Encoding")?.trim()?.lowercase()
        if (encoding.isNullOrEmpty() || encoding == "identity") return body()
        if (encoding != "gzip" && encoding != "x-gzip") {
            fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Content-Encoding '$encoding' (use gzip or none)")
            return null
        }
        return try {
            GZIPInputStream(bodyInputStream()).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_INFLATED_BODY) {
                        fail(HttpStatus.BAD_REQUEST, "Request body is bigger than $MAX_INFLATED_BODY bytes once decompressed")
                        return null
                    }
                    out.write(buffer, 0, read)
                }
                out.toString(Charsets.UTF_8)
            }
        } catch (_: IOException) {
            fail(HttpStatus.BAD_REQUEST, "Invalid gzip body")
            null
        }
    }

    private fun Context.intParam(name: String): Int? =
        pathParam(name).toIntOrNull().also { if (it == null) fail(HttpStatus.BAD_REQUEST, "Invalid $name") }

    fun health(ctx: Context) {
        ctx.user()
        ctx.respond(KarasuHealth.get())
    }

    /** GET/HEAD: streams the already downloaded CBZ. Never starts a download on the server. */
    fun archive(ctx: Context) = runBlocking { archiveSuspending(ctx) }

    private suspend fun archiveSuspending(ctx: Context) {
        val userId = ctx.user()
        val chapterId = ctx.intParam("chapterId") ?: return
        val downloaded =
            transaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.id eq chapterId }
                    .firstOrNull()
                    ?.get(ChapterTable.isDownloaded)
            }
        if (downloaded != true) {
            return ctx.fail(HttpStatus.NOT_FOUND, "Chapter $chapterId is not downloaded on the server")
        }
        ctx.disableCompression()
        if (ctx.method() == HandlerType.HEAD) {
            val (fileName, size) = ChapterDownloadHelper.getCbzMetadataForDownload(chapterId)
            ctx.header("Content-Type", CBZ_MIME)
            ctx.header("Content-Disposition", "attachment; filename=\"$fileName\"")
            ctx.header("Content-Length", size.toString())
            ctx.status(HttpStatus.OK)
        } else {
            val (stream, fileName, size) = ChapterDownloadHelper.getCbzForDownload(userId, chapterId, false)
            ctx.header("Content-Type", CBZ_MIME)
            ctx.header("Content-Disposition", "attachment; filename=\"$fileName\"")
            ctx.header("Content-Length", size.toString())
            ctx.result(stream)
        }
    }

    fun chapterLookup(ctx: Context) = runBlocking { chapterLookupSuspending(ctx) }

    private suspend fun chapterLookupSuspending(ctx: Context) {
        ctx.user()
        val sourceId = ctx.queryParam("sourceId")?.toLongOrNull()
        val mangaUrl = ctx.queryParam("mangaUrl")
        val chapterUrl = ctx.queryParam("chapterUrl")
        if (sourceId == null || mangaUrl == null || chapterUrl == null) {
            return ctx.fail(HttpStatus.BAD_REQUEST, "sourceId, mangaUrl and chapterUrl are required")
        }
        val result = KarasuSync.lookupChapter(sourceId, mangaUrl, chapterUrl)
        if (result == null) ctx.fail(HttpStatus.NOT_FOUND, "Chapter not found") else ctx.respond(result)
    }

    fun mangaLookup(ctx: Context) {
        ctx.user()
        val sourceId = ctx.queryParam("sourceId")?.toLongOrNull()
        val url = ctx.queryParam("url")
        if (sourceId == null || url == null) return ctx.fail(HttpStatus.BAD_REQUEST, "sourceId and url are required")
        val result = KarasuSync.lookupManga(sourceId, url)
        if (result == null) ctx.fail(HttpStatus.NOT_FOUND, "Manga not found") else ctx.respond(result)
    }

    fun backupUpload(ctx: Context) {
        ctx.user()
        val file = ctx.uploadedFile("file") ?: return ctx.fail(HttpStatus.BAD_REQUEST, "Multipart field 'file' is required")
        val info = KarasuBackups.save(file.filename(), ctx.queryParam("device"), file.content())
        ctx.respond(info, HttpStatus.CREATED)
    }

    fun backupList(ctx: Context) {
        ctx.user()
        ctx.respond(KarasuBackups.list())
    }

    fun backupDownload(ctx: Context) {
        ctx.user()
        val file = KarasuBackups.find(ctx.pathParam("id")) ?: return ctx.fail(HttpStatus.NOT_FOUND, "Backup not found")
        ctx.disableCompression()
        ctx.header("Content-Type", "application/octet-stream")
        ctx.header("Content-Disposition", "attachment; filename=\"${file.name.substringAfterLast("__")}\"")
        ctx.header("Content-Length", file.length().toString())
        ctx.result(file.inputStream())
    }

    fun backupDelete(ctx: Context) {
        ctx.user()
        if (KarasuBackups.delete(
                ctx.pathParam("id"),
            )
        ) {
            ctx.status(HttpStatus.NO_CONTENT)
        } else {
            ctx.fail(HttpStatus.NOT_FOUND, "Backup not found")
        }
    }

    fun mergedAll(ctx: Context) {
        ctx.user()
        ctx.respond<List<MergedGroupDto>>(MergedSources.getAllGroups())
    }

    fun mergedGet(ctx: Context) {
        ctx.user()
        val mangaId = ctx.intParam("mangaId") ?: return
        val group = MergedSources.getGroup(mangaId) ?: return ctx.fail(HttpStatus.NOT_FOUND, "Manga not found")
        ctx.respond(group)
    }

    fun mergedPut(ctx: Context) {
        ctx.user()
        val mangaId = ctx.intParam("mangaId") ?: return
        val body = ctx.readBody<MergedPutDto>() ?: return
        val group = MergedSources.replace(mangaId, body.sources) ?: return ctx.fail(HttpStatus.NOT_FOUND, "Manga not found")
        ctx.respond(group)
    }

    fun mergedPutByRef(ctx: Context) {
        ctx.user()
        val body = ctx.readBody<MergedPutByRefDto>() ?: return
        val sourceId = body.primary.sourceId.toLongOrNull() ?: return ctx.fail(HttpStatus.BAD_REQUEST, "Invalid primary.sourceId")
        val mangaId =
            MergedSources.findMangaId(sourceId, body.primary.url)
                ?: return ctx.fail(HttpStatus.NOT_FOUND, "Primary manga is not known by the server")
        val group = MergedSources.replace(mangaId, body.sources) ?: return ctx.fail(HttpStatus.NOT_FOUND, "Manga not found")
        ctx.respond(group)
    }

    fun policyGet(ctx: Context) {
        ctx.user()
        ctx.respond(KarasuSync.getPolicy())
    }

    fun policyPut(ctx: Context) {
        ctx.user()
        val body = ctx.readBody<PolicyDto>() ?: return
        try {
            ctx.respond(KarasuSync.setPolicy(body.downloadPolicy))
        } catch (e: IllegalArgumentException) {
            ctx.fail(HttpStatus.BAD_REQUEST, e.message ?: "Invalid policy")
        }
    }

    fun deletionsPost(ctx: Context) = runBlocking { deletionsPostSuspending(ctx) }

    private suspend fun deletionsPostSuspending(ctx: Context) {
        val userId = ctx.user()
        val body = ctx.readBody<DeletionRequestDto>() ?: return
        ctx.respond(KarasuSync.requestDeletions(userId, body))
    }

    fun historyPut(ctx: Context) {
        val userId = ctx.user()
        val body = ctx.readBody<HistoryRequestDto>() ?: return
        ctx.respond(KarasuSync.setHistory(userId, body.entries))
    }

    fun operationsPost(ctx: Context) {
        ctx.user()
        val body = ctx.readBody<OperationsPostDto>() ?: return
        try {
            ctx.respond(KarasuOperations.append(body.operations, ctx.header(KarasuRequestContext.HEADER)))
        } catch (e: IllegalArgumentException) {
            ctx.fail(HttpStatus.BAD_REQUEST, e.message ?: "Invalid operations")
        }
    }

    fun operationsGet(ctx: Context) {
        ctx.user()
        val afterSeq = ctx.queryParam("afterSeq")?.takeIf { it.isNotBlank() }
        val limit = ctx.queryParam("limit")?.takeIf { it.isNotBlank() }
        val ack = ctx.queryParam("ack")?.takeIf { it.isNotBlank() }
        if ((afterSeq != null && afterSeq.toLongOrNull() == null) ||
            (limit != null && limit.toIntOrNull() == null) ||
            (ack != null && ack.toLongOrNull() == null)
        ) {
            return ctx.fail(HttpStatus.BAD_REQUEST, "afterSeq, limit and ack must be integers")
        }
        try {
            ctx.respond(
                KarasuOperations.list(
                    afterSeq = afterSeq?.toLong() ?: 0L,
                    entity = ctx.queryParam("entity"),
                    limit = limit?.toInt() ?: KarasuOperations.DEFAULT_LIST_LIMIT,
                    deviceId = ctx.header(KarasuRequestContext.HEADER),
                    ack = ack?.toLong(),
                ),
            )
        } catch (e: IllegalArgumentException) {
            ctx.fail(HttpStatus.BAD_REQUEST, e.message ?: "Invalid query")
        }
    }

    fun deletionsGet(ctx: Context) {
        ctx.user()
        val afterId = ctx.queryParam("afterId")?.toIntOrNull() ?: 0
        ctx.respond(KarasuSync.listDeletions(afterId))
    }

    private const val CBZ_MIME = "application/vnd.comicbook+zip"
    private const val MAX_INFLATED_BODY = 64L * 1024 * 1024
}
