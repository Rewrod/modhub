package com.modhub.stardew

import com.modhub.core.install.ByteArrayModArchive
import com.modhub.core.model.LoaderStatus
import com.modhub.core.model.ModId
import com.modhub.core.model.ModSourceRef
import com.modhub.core.storage.StorageException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StardewPluginTest : StardewTestBase() {

    // ------------------------------------------------------------ metadata / dirs

    @Test
    fun metadata() {
        assertEquals("stardew", plugin.id.value)
        assertEquals("Stardew Valley", plugin.displayName)
        assertEquals(listOf("com.chucklefish.stardewvalley", "abc.smapi.gameloader"), plugin.packageNames)
        assertEquals(mapOf("nexus" to "stardewvalley"), plugin.sourceGameKeys)
        assertFalse(plugin.supportsLoadOrder)
        assertTrue(plugin.loaderRequirements.single().description.contains("Unofficial"))
    }

    @Test
    fun modsDirectoryPrefersFirstExistingThenOverride() = runBlocking<Unit> {
        val dirs = plugin.defaultModsDirectories(context())
        assertEquals(
            listOf(
                "/storage/emulated/0/StardewValley/Mods",
                "/storage/emulated/0/Android/data/abc.smapi.gameloader/files/Mods",
                "/storage/emulated/0/Android/data/com.chucklefish.stardewvalley/files/Mods",
            ),
            dirs.map { it.value },
        )
        assertEquals(dirs[0], plugin.modsDirectory(context()))
        storage.mkdirs(dirs[1])
        assertEquals(dirs[1], plugin.modsDirectory(context()))
        val custom = ext.resolve("MyMods")
        assertEquals(custom, plugin.modsDirectory(context(override = custom)))
    }

    // ------------------------------------------------------------ detect

    @Test
    fun detectNothingInstalled() = runBlocking<Unit> {
        val d = plugin.detect(context())
        assertFalse(d.gameInstalled)
        assertFalse(d.modsDirectoryAccessible)
        assertIs<LoaderStatus.Missing>(d.loaders.single())
        assertNotNull((d.loaders.single() as LoaderStatus.Missing).hint)
        assertFalse(d.ready)
        assertTrue(d.notes.any { "not installed" in it })
    }

    @Test
    fun detectReady() = runBlocking<Unit> {
        storage.mkdirs(modsDir)
        val d = plugin.detect(
            context(mapOf(StardewPlugin.PACKAGE_GAME to "1.6.15.1", StardewPlugin.PACKAGE_SMAPI_LAUNCHER to "1.2")),
        )
        assertTrue(d.gameInstalled)
        assertEquals("1.6.15.1", d.gameVersion)
        assertEquals(StardewPlugin.PACKAGE_GAME, d.installedPackage)
        assertEquals(LoaderStatus.Installed(StardewPlugin.SMAPI_REQUIREMENT, "1.2"), d.loaders.single())
        assertEquals(modsDir, d.modsDirectory)
        assertTrue(d.modsDirectoryAccessible)
        assertTrue(d.ready)
        assertTrue(d.notes.isEmpty(), d.notes.toString())
    }

    @Test
    fun detectOldGameAndRestrictedDir() = runBlocking<Unit> {
        val restricted = ext.resolve("Android/data/abc.smapi.gameloader/files/Mods")
        val d = plugin.detect(context(mapOf(StardewPlugin.PACKAGE_GAME to "1.5.6"), override = restricted))
        assertTrue(d.notes.any { "1.6.15.1" in it })
        assertTrue(d.notes.any { "Android/data" in it && "Shizuku" in it })
        assertFalse(d.modsDirectoryAccessible)
    }

    // ------------------------------------------------------------ install

    @Test
    fun installSingleWrappedMod() = runBlocking<Unit> {
        val zip = zipOf(
            "ContentPatcher/manifest.json" to manifestJson("Pathoschild.ContentPatcher", "Content Patcher", "2.4.0"),
            "ContentPatcher/ContentPatcher.dll" to "dll",
        )
        val source = ModSourceRef("nexus", ModId("1915"), "99", "2.4.0")
        val mods = plugin.installMod(context(), ByteArrayModArchive("CP-1915.zip", zip), source)
        val mod = mods.single()
        assertEquals("Pathoschild.ContentPatcher", mod.id)
        assertEquals("Content Patcher", mod.name)
        assertEquals("2.4.0", mod.version)
        assertEquals(modsDir.resolve("ContentPatcher"), mod.location)
        assertEquals(source, mod.source)
        assertTrue(mod.enabled)
        assertTrue(file(modsDir.resolve("ContentPatcher/ContentPatcher.dll")).isFile)
    }

    @Test
    fun installMultiModZipWithWrapperFolder() = runBlocking<Unit> {
        val zip = zipOf(
            "[CP] Bundle/" to "",
            "[CP] Bundle/ModA/manifest.json" to manifestJson("me.a", "Mod A"),
            "[CP] Bundle/ModA/content.json" to "{}",
            "[CP] Bundle/ModB/manifest.json" to manifestJson("me.b", "Mod B", "2.0.0"),
            "[CP] Bundle/README.txt" to "readme",
        )
        val mods = plugin.installMod(context(), ByteArrayModArchive("Bundle.zip", zip))
        assertEquals(listOf("me.a", "me.b"), mods.map { it.id }.sorted())
        val b = mods.first { it.id == "me.b" }
        assertEquals(modsDir.resolve("[CP] Bundle/ModB"), b.location)
        assertEquals("2.0.0", b.version)

        // listInstalled sees both through the grouped folder.
        assertEquals(listOf("me.a", "me.b"), plugin.listInstalled(context()).map { it.id })
    }

    @Test
    fun installMultiModZipWithoutWrapper() = runBlocking<Unit> {
        val zip = zipOf(
            "ModA/manifest.json" to manifestJson("me.a"),
            "ModB/manifest.json" to manifestJson("me.b"),
        )
        val mods = plugin.installMod(context(), ByteArrayModArchive("Two Mods-123-1-0.zip", zip))
        assertEquals(
            setOf(modsDir.resolve("Two Mods-123-1-0/ModA"), modsDir.resolve("Two Mods-123-1-0/ModB")),
            mods.map { it.location }.toSet(),
        )
    }

    @Test
    fun installLooseManifestAtRootUsesManifestName() = runBlocking<Unit> {
        val zip = zipOf(
            "manifest.json" to manifestJson("me.loose", "Loose Mod"),
            "assets/a.png" to "png",
        )
        val mod = plugin.installMod(context(), ByteArrayModArchive("loose-55-1-0.zip", zip)).single()
        assertEquals("me.loose", mod.id)
        assertEquals(modsDir.resolve("Loose Mod"), mod.location)
        assertTrue(file(modsDir.resolve("Loose Mod/assets/a.png")).isFile)
    }

    @Test
    fun installWithoutManifestStillReportsFolder() = runBlocking<Unit> {
        val zip = zipOf("Thing/readme.txt" to "hi")
        val mod = plugin.installMod(context(), ByteArrayModArchive("thing.zip", zip)).single()
        assertNull(mod.manifest)
        assertEquals(modsDir.resolve("Thing"), mod.location)
    }

    @Test
    fun reinstallReplacesDisabledCopy() = runBlocking<Unit> {
        val zip = zipOf("ModA/manifest.json" to manifestJson("me.a", version = "1.0.0"))
        val first = plugin.installMod(context(), ByteArrayModArchive("a.zip", zip)).single()
        plugin.setEnabled(context(), first, false)
        val zip2 = zipOf("ModA/manifest.json" to manifestJson("me.a", version = "1.1.0"))
        plugin.installMod(context(), ByteArrayModArchive("a.zip", zip2))
        val listed = plugin.listInstalled(context())
        assertEquals(1, listed.size)
        assertEquals("1.1.0", listed.single().version)
        assertTrue(listed.single().enabled)
    }

    // ------------------------------------------------------------ list / enable / uninstall

    @Test
    fun listInstalledHandlesDisabledGroupedAndBrokenFolders() = runBlocking<Unit> {
        write(modsDir.resolve("Alpha/manifest.json"), manifestJson("me.alpha", "Alpha"))
        write(modsDir.resolve(".Beta/manifest.json"), manifestJson("me.beta", "Beta"))
        write(modsDir.resolve("Group/Gamma/manifest.json"), manifestJson("me.gamma", "Gamma"))
        write(modsDir.resolve("Group/.Delta/manifest.json"), manifestJson("me.delta", "Delta"))
        write(modsDir.resolve(".OffGroup/Eps/manifest.json"), manifestJson("me.eps", "Eps"))
        write(modsDir.resolve("NoManifest/file.txt"), "x")
        write(modsDir.resolve("Broken/manifest.json"), "{ not json")
        write(modsDir.resolve("stray.txt"), "x")
        storage.mkdirs(modsDir.resolve(".modhub-staging-X-1"))

        val byName = plugin.listInstalled(context()).associateBy { it.name }
        assertEquals(setOf("Alpha", "Beta", "Gamma", "Delta", "Eps", "NoManifest", "Broken"), byName.keys)
        assertTrue(byName.getValue("Alpha").enabled)
        assertFalse(byName.getValue("Beta").enabled)
        assertEquals("me.beta", byName.getValue("Beta").id)
        assertTrue(byName.getValue("Gamma").enabled)
        assertFalse(byName.getValue("Delta").enabled)
        assertFalse(byName.getValue("Eps").enabled)
        assertNull(byName.getValue("NoManifest").manifest)
        assertNull(byName.getValue("Broken").manifest)
    }

    @Test
    fun listInstalledEmptyWhenNoModsDir() = runBlocking<Unit> {
        assertTrue(plugin.listInstalled(context()).isEmpty())
    }

    @Test
    fun setEnabledRenamesFolder() = runBlocking<Unit> {
        write(modsDir.resolve("Alpha/manifest.json"), manifestJson("me.alpha", "Alpha"))
        val mod = plugin.listInstalled(context()).single()

        val disabled = plugin.setEnabled(context(), mod, false)
        assertEquals(modsDir.resolve(".Alpha"), disabled.location)
        assertFalse(disabled.enabled)
        assertFalse(file(modsDir.resolve("Alpha")).exists())
        assertTrue(file(modsDir.resolve(".Alpha/manifest.json")).isFile)
        assertFalse(plugin.listInstalled(context()).single().enabled)

        // Idempotent.
        assertEquals(disabled.location, plugin.setEnabled(context(), disabled, false).location)

        val enabled = plugin.setEnabled(context(), disabled, true)
        assertEquals(modsDir.resolve("Alpha"), enabled.location)
        assertTrue(enabled.enabled)
        assertTrue(plugin.listInstalled(context()).single().enabled)
    }

    @Test
    fun setEnabledRefusesToClobber() = runBlocking<Unit> {
        write(modsDir.resolve("Alpha/manifest.json"), manifestJson("me.alpha"))
        write(modsDir.resolve(".Alpha/manifest.json"), manifestJson("me.alpha"))
        val enabled = plugin.listInstalled(context()).first { it.enabled }
        assertFailsWith<StorageException> { plugin.setEnabled(context(), enabled, false) }
        assertTrue(file(modsDir.resolve("Alpha")).isDirectory)
    }

    @Test
    fun uninstallDeletesFolderOnlyInsideModsDir() = runBlocking<Unit> {
        write(modsDir.resolve("Alpha/manifest.json"), manifestJson("me.alpha"))
        val mod = plugin.listInstalled(context()).single()
        plugin.uninstall(context(), mod)
        assertFalse(file(modsDir.resolve("Alpha")).exists())
        assertTrue(plugin.listInstalled(context()).isEmpty())

        val outside = mod.copy(location = ext.resolve("DCIM"))
        assertFailsWith<StorageException> { plugin.uninstall(context(), outside) }
    }

    // ------------------------------------------------------------ dependencies

    @Test
    fun dependencyReport() = runBlocking<Unit> {
        write(modsDir.resolve("CP/manifest.json"), manifestJson("Pathoschild.ContentPatcher", version = "2.0.0"))
        write(
            modsDir.resolve("NeedsCP/manifest.json"),
            manifestJson(
                "me.needs", version = "1.0.0",
                extra = """, "Dependencies": [
                    {"UniqueID": "pathoschild.contentpatcher", "MinimumVersion": "1.9"},
                    {"UniqueID": "SMAPI", "MinimumVersion": "4.0.0"},
                    {"UniqueID": "me.missing", "MinimumVersion": "3.0"},
                    {"UniqueID": "me.optional", "IsRequired": false},
                    {"UniqueID": "me.off"}
                ]""",
            ),
        )
        write(
            modsDir.resolve("[CP] Pack/manifest.json"),
            manifestJson("me.pack", extra = """, "ContentPackFor": {"UniqueID": "Pathoschild.ContentPatcher", "MinimumVersion": "2.5.0"}"""),
        )
        write(
            modsDir.resolve("[JA] Pack/manifest.json"),
            manifestJson("me.japack", extra = """, "ContentPackFor": {"UniqueID": "spacechase0.JsonAssets"}"""),
        )
        write(modsDir.resolve(".Off/manifest.json"), manifestJson("me.off"))
        // A disabled mod's own missing deps are not reported.
        write(
            modsDir.resolve(".DisabledNeedy/manifest.json"),
            manifestJson("me.dneedy", extra = """, "Dependencies": [{"UniqueID": "nope"}]"""),
        )

        val report = plugin.dependencyReport(context())
        val summary = report.map { Triple(it.mod.id, it.missingUniqueId, it.minimumVersion) }.toSet()
        assertEquals(
            setOf(
                Triple("me.needs", "me.missing", "3.0"),
                Triple("me.needs", "me.off", null),
                Triple("me.pack", "Pathoschild.ContentPatcher", "2.5.0"),
                Triple("me.japack", "spacechase0.JsonAssets", null),
            ),
            summary,
        )
        val outdated = report.single { it.mod.id == "me.pack" }
        assertTrue(outdated.isOutdated)
        assertEquals("2.0.0", outdated.installedVersion)
        assertEquals(StardewPlugin.MissingDependency.Kind.CONTENT_PACK_FOR, outdated.kind)
        assertFalse(report.single { it.missingUniqueId == "me.missing" }.isOutdated)
    }
}
