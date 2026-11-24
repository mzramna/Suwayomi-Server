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
import jakarta.servlet.http.Part
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import suwayomi.tachidesk.manga.impl.karasu.KarasuShelf
import suwayomi.tachidesk.manga.impl.karasu.ShelfListDto
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.requireUser

@Serializable
private data class ShelfErrorDto(
    val error: String,
)

/**
 * REST endpoints of the Yatagarasu (KOReader) shelf, under /api/v1/karasu/shelf. See docs/karasu-integration.md.
 * Same access rule as the other Karasu routes: an authenticated user, nothing more.
 */
object KarasuShelfController {
    private val json = Json { encodeDefaults = true }

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
    ) = respond(ShelfErrorDto(message), status)

    private fun Context.user(): Int = getAttribute(Attribute.TachideskUser).requireUser()

    private fun Context.chapterIdParam(): Long? =
        KarasuShelf.parseId(pathParam("chapterId")).also { if (it == null) fail(HttpStatus.BAD_REQUEST, "Invalid chapterId") }

    fun list(ctx: Context) {
        ctx.user()
        ctx.respond(ShelfListDto(KarasuShelf.list()))
    }

    /**
     * Multipart: part `metadata` (JSON) + part `file` (the CBZ). Jetty keeps every part above 1 byte in a temporary
     * file (Javalin's default multipart config), so a big CBZ never sits in the heap; it is copied once into the shelf.
     */
    fun upload(ctx: Context) {
        ctx.user()
        // forces Javalin to parse the multipart body with its own config; a body that is not multipart fails here
        val parts: Map<String, Part> =
            try {
                ctx.uploadedFiles("metadata")
                ctx.req().parts.associateBy { it.name }
            } catch (_: Exception) {
                return ctx.fail(HttpStatus.BAD_REQUEST, "Multipart body with parts 'metadata' and 'file' is required")
            }
        val metadataPart = parts["metadata"] ?: return ctx.fail(HttpStatus.BAD_REQUEST, "Multipart part 'metadata' is required")
        val filePart = parts["file"] ?: return ctx.fail(HttpStatus.BAD_REQUEST, "Multipart part 'file' is required")
        if (metadataPart.size > KarasuShelf.MAX_METADATA_BYTES) return ctx.fail(HttpStatus.BAD_REQUEST, "Part 'metadata' is too big")
        val metadata =
            metadataPart.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8).let(KarasuShelf::parseMetadata)
                ?: return ctx.fail(HttpStatus.BAD_REQUEST, "Part 'metadata' must be a JSON object with chapterId (number) and chapterUrl")
        val entry =
            try {
                KarasuShelf.save(metadata, filePart.inputStream)
            } catch (e: IllegalArgumentException) {
                return ctx.fail(HttpStatus.BAD_REQUEST, e.message ?: "Invalid file")
            }
        ctx.respond(entry, HttpStatus.CREATED)
    }

    /** Idempotent: 204 whether or not the chapter was there. */
    fun delete(ctx: Context) {
        ctx.user()
        val id = ctx.chapterIdParam() ?: return
        KarasuShelf.delete(id)
        ctx.status(HttpStatus.NO_CONTENT)
    }

    /** GET/HEAD: streams the CBZ the app sent. */
    fun file(ctx: Context) {
        ctx.user()
        val id = ctx.chapterIdParam() ?: return
        val file = KarasuShelf.archive(id) ?: return ctx.fail(HttpStatus.NOT_FOUND, "Chapter $id is not on the shelf")
        ctx.disableCompression()
        ctx.header("Content-Type", KarasuShelf.CBZ_MIME)
        ctx.header("Content-Disposition", "attachment; filename=\"$id.cbz\"")
        if (ctx.method() == HandlerType.HEAD) {
            ctx.header("Content-Length", file.length().toString())
            ctx.status(HttpStatus.OK)
        } else {
            // the size is read from the open stream, so it matches what is sent even if the entry is replaced meanwhile
            val stream = file.inputStream()
            ctx.header("Content-Length", stream.channel.size().toString())
            ctx.result(stream)
        }
    }

    /** Body `{"read":true|false}` → 204; 404 if the chapter is not on the shelf. */
    fun setRead(ctx: Context) {
        ctx.user()
        val id = ctx.chapterIdParam() ?: return
        val read =
            runCatching {
                val body: JsonObject = Json.parseToJsonElement(ctx.body()).jsonObject
                (body["read"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            }.getOrNull() ?: return ctx.fail(HttpStatus.BAD_REQUEST, "Body must be {\"read\":true|false}")
        if (KarasuShelf.setRead(id, read)) {
            ctx.status(HttpStatus.NO_CONTENT)
        } else {
            ctx.fail(HttpStatus.NOT_FOUND, "Chapter $id is not on the shelf")
        }
    }
}
