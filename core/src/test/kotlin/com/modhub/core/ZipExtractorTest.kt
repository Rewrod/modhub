package com.modhub.core

import com.modhub.core.storage.FileStorageBackend
import com.modhub.core.storage.StoragePath
import com.modhub.core.util.ArchiveTooLargeException
import com.modhub.core.util.ZipExtractor
import com.modhub.core.util.ZipSlipException
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZipExtractorTest : TempDirTest() {


    private val dest = StoragePath.of("/Mods/Target")

    @Test
    fun `extracts files and directories`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        val zip = zipOf("A/" to "", "A/manifest.json" to "{}", "A/sub/b.txt" to "hello", "root.txt" to "r")

        val result = ZipExtractor.extract(zip.inputStream(), storage, dest)

        assertEquals(setOf("A/manifest.json", "A/sub/b.txt", "root.txt"), result.files.toSet())
        assertEquals("hello", File(tmp, "Mods/Target/A/sub/b.txt").readText())
        assertEquals("{}", File(tmp, "Mods/Target/A/manifest.json").readText())
        assertEquals(8L, result.totalBytes)
    }

    @Test
    fun `skips macOS junk`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        val zip = zipOf("__MACOSX/A/._x" to "junk", "A/.DS_Store" to "junk", "A/x" to "x")
        val result = ZipExtractor.extract(zip.inputStream(), storage, dest)
        assertEquals(listOf("A/x"), result.files)
        assertFalse(File(tmp, "Mods/Target/__MACOSX").exists())
    }

    @Test
    fun `rejects parent traversal (zip slip)`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        val zip = zipOf("ok.txt" to "ok", "../../evil.txt" to "pwned")

        assertFailsWith<ZipSlipException> { ZipExtractor.extract(zip.inputStream(), storage, dest) }
        assertFalse(File(tmp, "evil.txt").exists())
        assertFalse(File(tmp, "Mods/evil.txt").exists())
    }

    @Test
    fun `rejects traversal hidden inside a path`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        val zip = zipOf("A/../../../evil.txt" to "pwned")
        assertFailsWith<ZipSlipException> { ZipExtractor.extract(zip.inputStream(), storage, dest) }
        assertFalse(File(tmp, "Mods/evil.txt").exists())
    }

    @Test
    fun `rejects absolute, backslash and drive-letter names`() {
        assertFailsWith<ZipSlipException> { ZipExtractor.safeEntryPath("/etc/passwd") }
        assertFailsWith<ZipSlipException> { ZipExtractor.safeEntryPath("..\\..\\evil.dll") }
        assertFailsWith<ZipSlipException> { ZipExtractor.safeEntryPath("C:/Windows/evil.dll") }
        assertEquals("a/b", ZipExtractor.safeEntryPath("a/./x/../b"))
    }

    @Test
    fun `path mapper cannot introduce zip slip`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        val zip = zipOf("a.txt" to "a")
        assertFailsWith<ZipSlipException> {
            ZipExtractor.extract(zip.inputStream(), storage, dest) { "../$it" }
        }
    }

    @Test
    fun `enforces total size limit`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        val zip = zipOf("big.txt" to "x".repeat(1000))
        assertFailsWith<ArchiveTooLargeException> {
            ZipExtractor.extract(zip.inputStream(), storage, dest, ZipExtractor.Limits(maxTotalBytes = 100))
        }
    }

    @Test
    fun `lists entries`() {
        val entries = ZipExtractor.listEntries(zipOf("A/" to "", "A/x" to "x").inputStream())
        assertEquals(listOf("A", "A/x"), entries.map { it.path })
        assertTrue(entries[0].isDirectory)
    }

    @Test
    fun `reports progress after each file`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        val zip = zipOf("A/" to "", "A/a.txt" to "a", "A/sub/b.txt" to "b", "c.txt" to "c")
        val calls = mutableListOf<Triple<Int, Int, String>>()

        ZipExtractor.extract(zip.inputStream(), storage, dest, totalEntries = 3, progress = { d, t, p -> calls += Triple(d, t, p) })

        assertEquals(
            listOf(Triple(1, 3, "A/a.txt"), Triple(2, 3, "A/sub/b.txt"), Triple(3, 3, "c.txt")),
            calls,
        )
        assertEquals("b", File(tmp, "Mods/Target/A/sub/b.txt").readText())
    }

    @Test
    fun `creates each parent directory only once`() = runBlocking<Unit> {
        var mkdirs = 0
        val storage = object : FileStorageBackend(tmp) {
            override suspend fun mkdirs(path: StoragePath): Boolean {
                mkdirs++
                return super.mkdirs(path)
            }
        }
        val zip = zipOf(*(1..50).map { "A/sub/f$it.txt" to "x" }.toTypedArray())

        val result = ZipExtractor.extract(zip.inputStream(), storage, dest)

        assertEquals(50, result.files.size)
        assertEquals(2, mkdirs, "destination + A/sub, not one per file")
    }
}
