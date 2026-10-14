package com.modhub.nexus

import com.modhub.core.model.GameId
import com.modhub.core.model.ModId
import com.modhub.core.model.SourceGameRef
import com.modhub.core.source.ModQuery
import com.modhub.core.source.SourceUnsupportedException
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalFileSourceTest {
    private val cache: File = Files.createTempDirectory("local-src").toFile()
    private val source = LocalFileSource()
    private val game = SourceGameRef(GameId("stardew"), "stardewvalley")

    @AfterTest
    fun cleanup() {
        cache.deleteRecursively()
    }

    @Test
    fun copiesStreamIntoCache() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val input = ByteArrayInputStream(bytes)
        val archive = source.archiveFromStream("ContentPatcher 2.4.0.zip", input, cache)
        assertEquals("ContentPatcher 2.4.0.zip", archive.fileName)
        assertContentEquals(bytes, archive.open().use { it.readBytes() })
        // Can be opened again.
        assertContentEquals(bytes, archive.open().use { it.readBytes() })
        val file = (archive as com.modhub.core.install.FileModArchive).file
        assertTrue(file.canonicalPath.startsWith(cache.canonicalPath))
    }

    @Test
    fun sameNameTwiceDoesNotClobber() {
        val a = source.archiveFromStream("m.zip", ByteArrayInputStream(byteArrayOf(1)), cache)
        val b = source.archiveFromStream("m.zip", ByteArrayInputStream(byteArrayOf(2)), cache)
        assertContentEquals(byteArrayOf(1), a.open().use { it.readBytes() })
        assertContentEquals(byteArrayOf(2), b.open().use { it.readBytes() })
    }

    @Test
    fun sanitizesNames() {
        assertEquals("evil.zip", LocalFileSource.sanitizeFileName("../../etc/evil.zip"))
        assertEquals("a_b.zip", LocalFileSource.sanitizeFileName("a:b.zip"))
        assertEquals("mod.zip", LocalFileSource.sanitizeFileName("   "))
        assertEquals("hidden.zip", LocalFileSource.sanitizeFileName(".hidden.zip"))
        val archive = source.archiveFromStream("..\\..\\x.zip", ByteArrayInputStream(byteArrayOf()), cache)
        assertEquals("x.zip", archive.fileName)
    }

    @Test
    fun catalogueCallsAreUnsupported() = runBlocking {
        assertEquals("local", source.id)
        assertEquals("Local file", source.displayName)
        assertTrue(source.isConfigured())
        assertFailsWith<SourceUnsupportedException> { source.search(ModQuery(game, "x")) }
        assertFailsWith<SourceUnsupportedException> { source.listLatest(game) }
        assertFailsWith<SourceUnsupportedException> { source.getMod(game, ModId("1")) }
        assertFailsWith<SourceUnsupportedException> { source.getFiles(game, ModId("1")) }
        Unit
    }
}
