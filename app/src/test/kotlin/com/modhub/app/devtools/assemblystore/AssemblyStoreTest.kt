package com.modhub.app.devtools.assemblystore

import com.modhub.app.devtools.assemblystore.StoreFixtures.V1Entry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssemblyStoreTest {
    private val stardew = StoreFixtures.fakeDll("StardewValley", 300)
    private val gameData = StoreFixtures.fakeDll("StardewValley.GameData", 100)
    private val monoGame = StoreFixtures.fakeDll("MonoGame.Framework", 200)

    @Test
    fun readsV2StoreHeaderIndexDescriptorsAndNameTable() {
        val store = StoreFixtures.v2Store(
            listOf("StardewValley" to StoreFixtures.xalz(stardew), "MonoGame.Framework" to monoGame),
        )
        val r = AssemblyStoreReader(store, "arm64-v8a")
        assertEquals(2u, r.version)
        assertEquals(0x80010002u, r.rawVersion)
        assertEquals(2u, r.localEntryCount)
        assertEquals(4u, r.globalEntryCount)
        assertEquals(48u, r.indexByteSize)
        assertTrue(r.hasGlobalIndex)
        assertEquals(4, r.globalIndex64.size)
        assertEquals(listOf(0u, 1u, 0u, 1u), r.globalIndex64.map { it.mappingIndex })
        assertEquals(0x1000_0000_0000_0003uL, r.globalIndex64[3].hash)
        assertEquals(listOf("StardewValley", "MonoGame.Framework"), r.assemblies.map { it.name })
        assertEquals("StardewValley.dll", r.assemblies[0].dllName)

        assertTrue(Xalz.isCompressed(r.assemblies[0].rawImage()))
        assertContentEquals(stardew, r.assemblies[0].extractImage())
        assertContentEquals(monoGame, r.assemblies[1].extractImage())
        assertEquals(null, r.assemblies[1].extractDebugData())
    }

    @Test
    fun v2IndexStrideFollowsHeaderIndexSize() {
        // 32-bit ABI stores use u32 hashes: 8-byte index entries.
        val r8 = AssemblyStoreReader(StoreFixtures.v2Store(listOf("A" to monoGame, "B" to gameData), indexStride = 8))
        assertEquals(listOf("A", "B"), r8.assemblies.map { it.name })
        assertContentEquals(gameData, r8.assemblies[1].extractImage())
        // Entries with trailing bytes (13-byte stride).
        val r13 = AssemblyStoreReader(StoreFixtures.v2Store(listOf("A" to monoGame, "B" to gameData), indexStride = 13))
        assertEquals(listOf("A", "B"), r13.assemblies.map { it.name })
        assertContentEquals(monoGame, r13.assemblies[0].extractImage())
    }

    @Test
    fun rejectsBadHeaders() {
        val good = StoreFixtures.v2Store(listOf("A" to monoGame))
        assertFailsWith<AssemblyStoreFormatException> { AssemblyStoreReader(good.copyOf().also { it[0] = 0 }) }
        assertFailsWith<AssemblyStoreFormatException> { // version 3
            AssemblyStoreReader(good.copyOf().also { it[4] = 3 })
        }
        assertFailsWith<AssemblyStoreFormatException> { // version 0
            AssemblyStoreReader(good.copyOf().also { it[4] = 0 })
        }
        assertFailsWith<AssemblyStoreFormatException> { AssemblyStoreReader(good.copyOf(30)) }
        // Data offset past the end is reported on extraction.
        val truncated = AssemblyStoreReader(good.copyOf(good.size - 10))
        assertFailsWith<AssemblyStoreFormatException> { truncated.assemblies[0].extractImage() }
    }

    private val v1Entries = listOf(
        V1Entry("StardewValley", stardew, hash32 = 0x11111111, hash64 = 0x1111111111111111, storeId = 0, localIndex = 0),
        V1Entry("MonoGame.Framework", monoGame, hash32 = 0x22222222, hash64 = 0x2222222222222222, storeId = 0, localIndex = 1),
        V1Entry("StardewValley.GameData", gameData, hash32 = 0x33333333, hash64 = 0x3333333333333333, storeId = 1, localIndex = 0),
    )

    private fun v1Apk(withManifest: Boolean): MapArchiveSource {
        val store0 = StoreFixtures.v1Store(0, listOf(StoreFixtures.xalz(stardew), monoGame), v1Entries)
        val store1 = StoreFixtures.v1Store(1, listOf(gameData), emptyList())
        val entries = linkedMapOf(
            "AndroidManifest.xml" to ByteArray(4),
            "assemblies/assemblies.blob" to store0,
            "assemblies/assemblies.arm64_v8a.blob" to store1,
        )
        if (withManifest) entries["assemblies/assemblies.manifest"] = StoreFixtures.v1Manifest(v1Entries)
        return MapArchiveSource(entries)
    }

    @Test
    fun explorerReadsV1StoreSetAndNamesFromManifest() {
        val e = AssemblyStoreExplorer.fromArchive(v1Apk(withManifest = true))
        assertTrue(e.isCompleteSet)
        assertFalse(e.usedElfFormat)
        assertFalse(e.hasErrors, e.messages.joinToString())
        assertEquals(2, e.numberOfStores)
        assertEquals(setOf(0u, 1u), e.stores.keys)
        assertEquals("arm64_v8a", e.stores[1u]!!.single().arch)
        assertEquals(listOf("MonoGame.Framework", "StardewValley", "StardewValley.GameData"), e.assembliesByName.keys.toList())

        val sv = e.assembliesByName["stardewvalley"]!! // case-insensitive, like the C# SortedDictionary
        assertEquals(0x11111111u, sv.hash32)
        assertEquals(0x1111111111111111uL, sv.hash64)
        assertEquals(0u, sv.runtimeIndex)
        assertContentEquals(stardew, sv.extractImage())
        assertContentEquals(gameData, e.assembliesByHash64[0x3333333333333333uL]!!.extractImage())
        assertEquals("MonoGame.Framework", e.assembliesByHash32[0x22222222u]!!.name)
    }

    @Test
    fun manifestParsing() {
        val m = AssemblyStoreManifestReader(StoreFixtures.v1Manifest(v1Entries) + "\n\nbroken line\n".toByteArray())
        assertEquals(3, m.entries.size)
        assertEquals("StardewValley.GameData", m.entriesByHash32[0x33333333u]!!.name)
        assertEquals(1u, m.entriesByHash64[0x3333333333333333uL]!!.storeId)
    }

    @Test
    fun scannerExtractsWantedAssembliesFromV1Store() {
        val wanted = listOf("StardewValley.dll", "StardewValley.GameData.dll", "MonoGame.Framework.dll")
        val r = ApkAssemblyScanner().scan(v1Apk(withManifest = true), wanted)
        assertEquals(setOf(AssemblyPackaging.ASSEMBLY_STORE), r.packagings)
        assertEquals(3, r.extracted.size)
        val sv = r.extracted["stardewvalley"]!!
        assertEquals("StardewValley.dll", sv.fileName)
        assertTrue(sv.wasCompressed)
        assertContentEquals(stardew, sv.bytes)
        assertEquals("assemblies/assemblies.arm64_v8a.blob", r.extracted["stardewvalley.gamedata"]!!.entry)
    }

    @Test
    fun scannerNamesUnnamedV1EntriesFromImage() {
        val r = ApkAssemblyScanner().scan(v1Apk(withManifest = false), listOf("MonoGame.Framework.dll", "StardewValley.dll"))
        assertEquals(listOf("MonoGame.Framework", "StardewValley", "StardewValley.GameData"), r.assemblyNames)
        assertContentEquals(stardew, r.extracted["stardewvalley"]!!.bytes)
        assertContentEquals(monoGame, r.extracted["monogame.framework"]!!.bytes)
    }

    @Test
    fun scannerReadsClassicAssembliesAndPrefersArm64() {
        val apk = MapArchiveSource(
            linkedMapOf(
                "assemblies/x86/StardewValley.dll" to gameData,
                "assemblies/arm64-v8a/StardewValley.dll" to StoreFixtures.xalz(stardew),
                "assemblies/MonoGame.Framework.dll" to monoGame,
                "assemblies/Other.dll" to gameData,
            ),
        )
        val r = ApkAssemblyScanner().scan(apk, listOf("StardewValley.dll", "MonoGame.Framework.dll"))
        assertEquals(setOf(AssemblyPackaging.CLASSIC_DLL), r.packagings)
        assertEquals(listOf("MonoGame.Framework", "Other", "StardewValley"), r.assemblyNames)
        assertEquals("assemblies/arm64-v8a/StardewValley.dll", r.extracted["stardewvalley"]!!.entry)
        assertContentEquals(stardew, r.extracted["stardewvalley"]!!.bytes)
        assertContentEquals(monoGame, r.extracted["monogame.framework"]!!.bytes)
    }

    @Test
    fun storeArchNames() {
        assertEquals("arm64_v8a", AssemblyStoreExplorer.getStoreArch("assemblies/assemblies.arm64_v8a.blob"))
        assertEquals("", AssemblyStoreExplorer.getStoreArch("assemblies/assemblies.blob"))
        assertEquals("assemblies/assemblies.", AssemblyStoreExplorer.basePathInArchive(".apk"))
        assertEquals("base/root/assemblies/base_assemblies.", AssemblyStoreExplorer.basePathInArchive("aab", "base_assemblies"))
    }
}
