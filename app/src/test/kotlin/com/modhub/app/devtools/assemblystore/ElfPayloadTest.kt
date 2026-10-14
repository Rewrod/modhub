package com.modhub.app.devtools.assemblystore

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElfPayloadTest {
    private val stardew = StoreFixtures.fakeDll("StardewValley", 300)
    private val gameData = StoreFixtures.fakeDll("StardewValley.GameData", 100)
    private val monoGame = StoreFixtures.fakeDll("MonoGame.Framework", 200)

    @Test
    fun findsPayloadSection() {
        val payload = ByteArray(777) { (it * 3).toByte() }
        val elf = StoreFixtures.elf64(payload)
        val s = assertNotNull(ElfPayload.findPayloadSection(elf))
        assertEquals(ElfPayload.Section(0x1000, 777), s)
        val buf = assertNotNull(ElfPayload.extract(elf))
        assertEquals(777, buf.remaining())
        assertContentEquals(payload, ByteArray(buf.remaining()).also { buf.get(it) })
    }

    @Test
    fun returnsNullWithoutPayloadOrForNonElf64() {
        assertNull(ElfPayload.findPayloadSection(StoreFixtures.elf64(ByteArray(8), sectionName = "rodata")))
        val elf32 = StoreFixtures.elf64(ByteArray(8)).also { it[4] = 1 }
        assertNull(ElfPayload.findPayloadSection(elf32))
        assertNull(ElfPayload.findPayloadSection(ByteArray(100)))
        assertNull(ElfPayload.findPayloadSection(StoreFixtures.elf64(ByteArray(8)).copyOf(40)))
        // Section header table pointing past the end of the file.
        val bad = StoreFixtures.elf64(ByteArray(8)).also { it[0x28 + 3] = 0x7F }
        assertNull(ElfPayload.findPayloadSection(bad))
    }

    @Test
    fun scannerReadsDotNet9ElfWrappedStores() {
        val v2arm = StoreFixtures.v2Store(
            listOf(
                "Microsoft.Xna.Other" to gameData,
                "StardewValley" to StoreFixtures.xalz(stardew),
                "StardewValley.GameData" to StoreFixtures.xalz(gameData),
                "MonoGame.Framework" to monoGame,
            ),
        )
        // x86_64 copy with different bytes; arm64 must win regardless of archive order.
        val v2x64 = StoreFixtures.v2Store(
            listOf(
                "Microsoft.Xna.Other" to gameData,
                "StardewValley" to monoGame,
                "StardewValley.GameData" to monoGame,
                "MonoGame.Framework" to gameData,
            ),
        )
        val apk = MapArchiveSource(
            linkedMapOf(
                "classes.dex" to ByteArray(10),
                "lib/x86_64/libassemblies.x86_64.blob.so" to StoreFixtures.elf64(v2x64),
                "lib/arm64-v8a/libmonosgen-2.0.so" to ByteArray(10),
                "lib/arm64-v8a/libassemblies.arm64-v8a.blob.so" to StoreFixtures.elf64(v2arm),
            ),
        )
        val explorer = AssemblyStoreExplorer.fromArchive(apk)
        assertTrue(explorer.usedElfFormat)
        assertEquals(listOf("lib/arm64-v8a/libassemblies.arm64-v8a.blob.so", "lib/x86_64/libassemblies.x86_64.blob.so"), explorer.storeEntries)
        assertEquals("arm64-v8a", explorer.assembliesByName["StardewValley"]!!.store.arch)

        val wanted = listOf("StardewValley.dll", "StardewValley.GameData.dll", "MonoGame.Framework.dll")
        val r = ApkAssemblyScanner().scan(apk, wanted)
        assertEquals(setOf(AssemblyPackaging.ELF_ASSEMBLY_STORE), r.packagings)
        assertEquals(4, r.assemblyNames.size)
        assertContentEquals(stardew, r.extracted["stardewvalley"]!!.bytes)
        assertContentEquals(gameData, r.extracted["stardewvalley.gamedata"]!!.bytes)
        assertContentEquals(monoGame, r.extracted["monogame.framework"]!!.bytes)
        assertEquals("lib/arm64-v8a/libassemblies.arm64-v8a.blob.so", r.extracted["monogame.framework"]!!.entry)
        assertTrue(r.extracted["stardewvalley"]!!.wasCompressed)
    }

    @Test
    fun scannerReadsElfWrappedSingleAssemblies() {
        val apk = MapArchiveSource(
            linkedMapOf("lib/arm64-v8a/lib_StardewValley.dll.so" to StoreFixtures.elf64(StoreFixtures.xalz(stardew))),
        )
        val r = ApkAssemblyScanner().scan(apk, listOf("StardewValley.dll"))
        assertEquals(setOf(AssemblyPackaging.ELF_WRAPPED_DLL), r.packagings)
        assertContentEquals(stardew, r.extracted["stardewvalley"]!!.bytes)
    }

    @Test
    fun scannerReportsNothingForNonDotNetApk() {
        val r = ApkAssemblyScanner().scan(MapArchiveSource(mapOf("classes.dex" to ByteArray(4))), listOf("StardewValley.dll"))
        assertTrue(r.extracted.isEmpty())
        assertEquals("no .NET assemblies", r.describe())
    }
}
