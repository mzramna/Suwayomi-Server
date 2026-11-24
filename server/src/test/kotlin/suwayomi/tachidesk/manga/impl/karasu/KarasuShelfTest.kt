package suwayomi.tachidesk.manga.impl.karasu

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.test.ApplicationTest
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The storage and rules of the Yatagarasu shelf; the HTTP side of it is covered by the contract battery. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KarasuShelfTest : ApplicationTest() {
    private val shelfDir get() = File(Injekt.get<ApplicationDirs>().dataRoot, "karasu-shelf")

    @BeforeEach
    @AfterEach
    fun clean() {
        shelfDir.deleteRecursively()
    }

    private fun meta(
        id: Long,
        url: String = "/c$id",
    ) = ShelfMetadata(id, url, "Manga", "Chapter $id", id.toDouble(), "123")

    private fun cbz(seed: Int): ByteArray = ByteArray(5000) { (it * 31 + seed).toByte() }

    private fun upload(
        id: Long,
        content: ByteArray = cbz(id.toInt()),
        url: String = "/c$id",
    ) = KarasuShelf.save(meta(id, url), ByteArrayInputStream(content))

    @Test
    fun `posting the same chapter twice leaves one unread entry`() {
        val first = upload(7)
        val second = upload(7)
        assertEquals(ShelfEntryDto(7, "/c7", false), first)
        assertEquals(first, second)
        assertEquals(listOf(ShelfEntryDto(7, "/c7", false)), KarasuShelf.list())
    }

    @Test
    fun `sending again after it was read replaces the file and resets read`() {
        upload(7, cbz(1))
        assertTrue(KarasuShelf.setRead(7, true))
        assertTrue(KarasuShelf.list().single().read)

        val replacement = cbz(2)
        val entry = upload(7, replacement, url = "/c7-new")
        assertFalse(entry.read)
        assertEquals(ShelfEntryDto(7, "/c7-new", false), KarasuShelf.list().single())
        assertContentEquals(replacement, KarasuShelf.archive(7)!!.readBytes())
    }

    @Test
    fun `delete is idempotent`() {
        upload(1)
        assertTrue(KarasuShelf.delete(1))
        assertFalse(KarasuShelf.delete(1))
        assertTrue(KarasuShelf.list().isEmpty())
        assertNull(KarasuShelf.archive(1))
        assertFalse(KarasuShelf.delete(999))
        assertEquals(emptyList(), shelfDir.listFiles().orEmpty().toList())
    }

    @Test
    fun `marking read shows up in the list, and can be undone`() {
        upload(3)
        upload(4)
        assertTrue(KarasuShelf.setRead(3, true))
        assertEquals(listOf(true, false), KarasuShelf.list().map { it.read })
        assertTrue(KarasuShelf.setRead(3, false))
        assertEquals(listOf(false, false), KarasuShelf.list().map { it.read })
    }

    @Test
    fun `marking read a chapter that is not on the shelf reports it`() {
        assertFalse(KarasuShelf.setRead(42, true))
        assertTrue(KarasuShelf.list().isEmpty())
    }

    @Test
    fun `the stored file is byte for byte what was sent`() {
        val bytes = cbz(9)
        upload(12, bytes)
        val stored = KarasuShelf.archive(12)
        assertNotNull(stored)
        assertEquals("12.cbz", stored.name)
        assertEquals(shelfDir.canonicalFile, stored.canonicalFile.parentFile)
        assertContentEquals(bytes, stored.readBytes())
    }

    @Test
    fun `the list is ordered by chapterId whatever the upload order`() {
        listOf(30L, 4L, 100L, 9L).forEach { upload(it) }
        assertEquals(listOf(4L, 9L, 30L, 100L), KarasuShelf.list().map { it.chapterId })
    }

    @Test
    fun `an empty file is refused and nothing is left behind`() {
        assertFailsWith<IllegalArgumentException> { upload(5, ByteArray(0)) }
        assertTrue(KarasuShelf.list().isEmpty())
        assertEquals(emptyList(), shelfDir.listFiles().orEmpty().toList())
    }

    @Test
    fun `an interrupted upload keeps the previous version and leaves no part file`() {
        val original = cbz(1)
        upload(8, original)
        val broken =
            object : java.io.InputStream() {
                private var sent = 0

                override fun read(): Int = if (sent++ < 100) 1 else throw java.io.IOException("connection reset")
            }
        assertFailsWith<java.io.IOException> { KarasuShelf.save(meta(8), broken) }
        assertContentEquals(original, KarasuShelf.archive(8)!!.readBytes())
        assertEquals(listOf(ShelfEntryDto(8, "/c8", false)), KarasuShelf.list())
        assertTrue(shelfDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `part files never show up in the list`() {
        upload(1)
        File(shelfDir, "2.cbz.123e4567-e89b-12d3-a456-426614174000.part").writeBytes(cbz(2))
        File(shelfDir, "2.json.part").writeText("""{"chapterId":2,"chapterUrl":"/c2"}""")
        File(shelfDir, "3.cbz").writeBytes(cbz(3)) // archive without a record
        File(shelfDir, "4.json").writeText("""{"chapterId":4,"chapterUrl":"/c4"}""") // record without archive
        File(shelfDir, "5.json").writeText("not json")
        File(shelfDir, "5.cbz").writeBytes(cbz(5))
        assertEquals(listOf(1L), KarasuShelf.list().map { it.chapterId })
        assertNull(KarasuShelf.archive(3))
        assertNull(KarasuShelf.archive(4))
        assertNull(KarasuShelf.archive(5))
    }

    @Test
    fun `only plain digits are an id, so a path cannot be built from client text`() {
        assertEquals(123L, KarasuShelf.parseId("123"))
        assertEquals(0L, KarasuShelf.parseId("0"))
        listOf("../x", "..", "../../etc/passwd", "1/2", "1\\2", "-1", "+1", "1.5", "abc", "", " 1", "1 ", "%2e%2e", "1e3", "9".repeat(30))
            .forEach { assertNull(KarasuShelf.parseId(it), "'$it' must not be an id") }
    }

    @Test
    fun `metadata with a path as chapterId is invalid and writes nothing`() {
        listOf("../x", "../../escape", "1/../2", "-3").forEach {
            assertNull(KarasuShelf.parseMetadata("""{"chapterId":"$it","chapterUrl":"/c"}"""), it)
        }
        assertTrue(shelfDir.listFiles().orEmpty().isEmpty())
        assertFalse(File(shelfDir.parentFile, "x.cbz").exists())
    }

    @Test
    fun `metadata is validated`() {
        val ok = KarasuShelf.parseMetadata("""{"chapterId":123,"chapterUrl":"/c","mangaTitle":"M","chapterName":"N","chapterNumber":1.5,"sourceId":99}""")
        assertEquals(ShelfMetadata(123, "/c", "M", "N", 1.5, "99"), ok)
        // chapterId may come quoted, sourceId as text
        assertEquals(5L, KarasuShelf.parseMetadata("""{"chapterId":"5","chapterUrl":"/c","sourceId":"7"}""")?.chapterId)
        // the descriptive fields are optional
        assertEquals(ShelfMetadata(1, "/c", "", "", 0.0, ""), KarasuShelf.parseMetadata("""{"chapterId":1,"chapterUrl":"/c"}"""))
        listOf(
            "",
            "not json",
            "[]",
            "null",
            "{}",
            """{"chapterUrl":"/c"}""",
            """{"chapterId":1}""",
            """{"chapterId":1,"chapterUrl":""}""",
            """{"chapterId":1,"chapterUrl":null}""",
            """{"chapterId":1,"chapterUrl":5}""",
            """{"chapterId":null,"chapterUrl":"/c"}""",
            """{"chapterId":1.5,"chapterUrl":"/c"}""",
            """{"chapterId":true,"chapterUrl":"/c"}""",
            """{"chapterId":{"a":1},"chapterUrl":"/c"}""",
        ).forEach { assertNull(KarasuShelf.parseMetadata(it), "'$it' must be invalid") }
    }

    @Test
    fun `nothing about the shelf is written to the operation log`() {
        val before = KarasuOperations.list().lastSeq
        upload(1)
        KarasuShelf.setRead(1, true)
        KarasuShelf.delete(1)
        assertEquals(before, KarasuOperations.list().lastSeq)
    }
}
