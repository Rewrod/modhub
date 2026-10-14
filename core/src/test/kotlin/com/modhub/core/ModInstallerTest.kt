package com.modhub.core

import com.modhub.core.install.ByteArrayModArchive
import com.modhub.core.install.InstallOptions
import com.modhub.core.install.ModAlreadyInstalledException
import com.modhub.core.install.ModInstaller
import com.modhub.core.model.GameId
import com.modhub.core.model.ModId
import com.modhub.core.model.ModSourceRef
import com.modhub.core.storage.FileStorageBackend
import com.modhub.core.storage.StoragePath
import com.modhub.core.util.ZipSlipException
import com.modhub.core.install.InstallProgress
import com.modhub.core.install.ModArchive
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModInstallerTest : TempDirTest() {


    private val game = GameId("stardew")
    private val modsDir = StoragePath.of("/StardewValley/Mods")
    private val installer = ModInstaller(clock = { 1234L })

    private fun modsFile(rel: String) = File(tmp, "StardewValley/Mods/$rel")

    @Test
    fun `single top-level folder is installed under its own name`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        val archive = ByteArrayModArchive(
            "Content Patcher 2.4.0.zip",
            zipOf("ContentPatcher/manifest.json" to "{\"UniqueID\":\"Pathoschild.ContentPatcher\"}", "ContentPatcher/ContentPatcher.dll" to "dll"),
        )
        val src = ModSourceRef("nexus", ModId("1915"), fileId = "99", version = "2.4.0")

        val mod = installer.install(archive, storage, modsDir, game, src)

        assertEquals("ContentPatcher", mod.id)
        assertEquals(modsDir / "ContentPatcher", mod.location)
        assertEquals(game, mod.gameId)
        assertEquals("2.4.0", mod.version)
        assertEquals(src, mod.source)
        assertEquals(1234L, mod.installedAt)
        assertTrue(modsFile("ContentPatcher/manifest.json").isFile)
        assertTrue(modsFile("ContentPatcher/ContentPatcher.dll").isFile)
        assertFalse(modsFile("ContentPatcher/ContentPatcher").exists(), "wrapper folder must be stripped once")
        assertEquals(listOf("ContentPatcher"), File(tmp, "StardewValley/Mods").list()!!.toList(), "no staging leftovers")
    }

    @Test
    fun `loose files go into a folder named after the archive`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        val archive = ByteArrayModArchive("CoolMod-1.0.zip", zipOf("manifest.json" to "{}", "CoolMod.dll" to "x"))

        val mod = installer.install(archive, storage, modsDir, game)

        assertEquals("CoolMod-1.0", mod.id)
        assertTrue(modsFile("CoolMod-1.0/manifest.json").isFile)
    }

    @Test
    fun `multiple top-level folders are kept together`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        val archive = ByteArrayModArchive("Bundle.zip", zipOf("[CP] A/manifest.json" to "{}", "[CP] B/manifest.json" to "{}"))

        val mod = installer.install(archive, storage, modsDir, game)

        assertEquals("Bundle", mod.id)
        assertTrue(modsFile("Bundle/[CP] A/manifest.json").isFile)
        assertTrue(modsFile("Bundle/[CP] B/manifest.json").isFile)
    }

    @Test
    fun `folderName option overrides layout`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        val archive = ByteArrayModArchive("x.zip", zipOf("Inner/a.txt" to "a"))
        val mod = installer.install(archive, storage, modsDir, game, options = InstallOptions(folderName = "Renamed"))
        assertEquals(modsDir / "Renamed", mod.location)
        assertTrue(modsFile("Renamed/a.txt").isFile)
    }

    @Test
    fun `reinstall replaces old files`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        installer.install(ByteArrayModArchive("m.zip", zipOf("M/old.txt" to "old")), storage, modsDir, game)
        installer.install(ByteArrayModArchive("m.zip", zipOf("M/new.txt" to "new")), storage, modsDir, game)

        assertTrue(modsFile("M/new.txt").isFile)
        assertFalse(modsFile("M/old.txt").exists())
    }

    @Test
    fun `refuses to replace when replaceExisting is false`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        installer.install(ByteArrayModArchive("m.zip", zipOf("M/a.txt" to "a")), storage, modsDir, game)
        assertFailsWith<ModAlreadyInstalledException> {
            installer.install(
                ByteArrayModArchive("m.zip", zipOf("M/b.txt" to "b")),
                storage, modsDir, game, options = InstallOptions(replaceExisting = false),
            )
        }
        assertTrue(modsFile("M/a.txt").isFile)
    }

    @Test
    fun `malicious archive leaves nothing behind`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        val archive = ByteArrayModArchive("evil.zip", zipOf("Evil/ok.txt" to "ok", "Evil/../../../pwned.txt" to "x"))

        assertFailsWith<ZipSlipException> { installer.install(archive, storage, modsDir, game) }

        assertFalse(File(tmp, "pwned.txt").exists())
        assertFalse(File(tmp, "StardewValley/pwned.txt").exists())
        assertTrue(File(tmp, "StardewValley/Mods").list().orEmpty().isEmpty(), "staging must be cleaned up")
    }

    @Test
    fun `empty archive is rejected`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        assertFailsWith<java.io.IOException> {
            installer.install(ByteArrayModArchive("e.zip", zipOf("Dir/" to "")), storage, modsDir, game)
        }
    }

    private class CountingArchive(private val inner: ModArchive) : ModArchive by inner {
        var opens = 0
        override fun open(): java.io.InputStream {
            opens++
            return inner.open()
        }
    }

    @Test
    fun `install reuses a given plan and reports progress`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        val archive = CountingArchive(
            ByteArrayModArchive("m.zip", zipOf("M/" to "", "M/a.txt" to "a", "M/b/c.txt" to "c", "M/d.txt" to "d")),
        )
        val plan = installer.plan(archive)
        assertEquals(1, archive.opens)
        assertEquals(3, plan.fileCount)

        val calls = mutableListOf<Pair<Int, Int>>()
        val mod = installer.install(
            archive, storage, modsDir, game,
            plan = plan,
            progress = InstallProgress { done, total, _ -> calls += done to total },
        )

        assertEquals(2, archive.opens, "plan() is the only listing pass; install() only extracts")
        assertEquals("M", mod.location.name)
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), calls)
        assertEquals("c", modsFile("M/b/c.txt").readText())
    }

    @Test
    fun `folderName option overrides a given plan`() = runBlocking<Unit> {
        val storage = FileStorageBackend(tmp)
        val archive = ByteArrayModArchive("m.zip", zipOf("M/a.txt" to "a"))
        val plan = installer.plan(archive)
        val mod = installer.install(archive, storage, modsDir, game, options = InstallOptions(folderName = "Other"), plan = plan)
        assertEquals("Other", mod.location.name)
        assertEquals("a", modsFile("Other/a.txt").readText())
    }
}
