package suwayomi.tachidesk.manga.impl.karasu

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Who is making the current request, as far as the operation log is concerned.
 *
 * The Karasu app sends `X-Karasu-Device` on every call (REST and GraphQL) and publishes its own log with the
 * real time of each action, so a request that carries the header is NOT logged by the server. A request without
 * it (WebUI, scripts) is logged by the server as `web`.
 *
 * The value lives in a [ThreadLocal] that is only ever set around synchronous code on the thread that runs it:
 * a Javalin `before` handler (cleared by `after`) for REST, and the `@RequireAuth` data fetcher wrapper for
 * GraphQL, which reads the device from the GraphQL context so it does not depend on which thread executes the
 * resolver. Code that hops threads (futures, `Dispatchers.IO`) does not see it: such code has to read the device
 * first and pass it explicitly (see the `deviceId` parameters).
 */
object KarasuRequestContext {
    const val HEADER = "X-Karasu-Device"
    const val WEB_DEVICE = "web"
    private const val MAX_DEVICE_LENGTH = 128

    private val current = ThreadLocal<String?>()

    /** Device of the request being handled on this thread, or null when it did not send the header. */
    fun device(): String? = current.get()

    /** Cleans a raw header value; blank means "no device". */
    fun normalize(raw: String?): String? =
        raw
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_DEVICE_LENGTH)

    fun set(raw: String?) {
        val device = normalize(raw)
        if (device == null) current.remove() else current.set(device)
    }

    fun clear() = current.remove()

    /** Runs [block] as a request of [rawDevice] and restores whatever was set before. */
    inline fun <T> withDevice(
        rawDevice: String?,
        block: () -> T,
    ): T {
        val previous = device()
        set(rawDevice)
        try {
            return block()
        } finally {
            set(previous)
        }
    }
}
