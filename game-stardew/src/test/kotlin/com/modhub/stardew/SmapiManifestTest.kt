package com.modhub.stardew

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmapiManifestTest {

    @Test
    fun parsesFullManifestWithStringVersion() {
        val m = SmapiManifest.parse(
            """
            {
              "Name": "Content Patcher",
              "Author": "Pathoschild",
              "Version": "2.4.0",
              "Description": "Loads content packs.",
              "UniqueID": "Pathoschild.ContentPatcher",
              "EntryDll": "ContentPatcher.dll",
              "MinimumApiVersion": "4.0.0",
              "UpdateKeys": [ "Nexus:1915", "GitHub:Pathoschild/StardewMods" ],
              "Dependencies": [
                { "UniqueID": "Pathoschild.Common", "MinimumVersion": "1.1" },
                { "UniqueID": "Some.Optional", "IsRequired": false }
              ]
            }
            """.trimIndent(),
        )
        assertEquals("Pathoschild.ContentPatcher", m.uniqueId)
        assertEquals("Content Patcher", m.name)
        assertEquals("2.4.0", m.version)
        assertEquals("Pathoschild", m.author)
        assertEquals("4.0.0", m.minimumApiVersion)
        assertEquals("ContentPatcher.dll", m.entryDll)
        assertEquals(listOf("Nexus:1915", "GitHub:Pathoschild/StardewMods"), m.updateKeys)
        assertEquals(
            listOf(
                SmapiManifest.Dependency("Pathoschild.Common", true, "1.1"),
                SmapiManifest.Dependency("Some.Optional", false, null),
            ),
            m.dependencies,
        )

        val core = m.toModManifest("folder")
        assertEquals("Pathoschild.ContentPatcher", core.uniqueId)
        assertEquals("4.0.0", core.minimumLoaderVersion)
        assertEquals(2, core.dependencies.size)
        assertEquals(false, core.dependencies[1].required)
        assertEquals("ContentPatcher.dll", core.extra[SmapiManifest.EXTRA_ENTRY_DLL])
        assertNull(core.extra[SmapiManifest.EXTRA_CONTENT_PACK_FOR])
    }

    @Test
    fun parsesObjectVersion() {
        val m = SmapiManifest.parse(
            """{"Name":"Old","UniqueID":"a.old","Version":{"MajorVersion":1,"MinorVersion":2,"PatchVersion":3,"Build":"beta"}}""",
        )
        assertEquals("1.2.3-beta", m.version)
        val noBuild = SmapiManifest.parse("""{"UniqueID":"x","Version":{"MajorVersion":2,"MinorVersion":0,"PatchVersion":1,"Build":null}}""")
        assertEquals("2.0.1", noBuild.version)
    }

    @Test
    fun toleratesTrailingCommasCommentsBomAndKeyCase() {
        val m = SmapiManifest.parse(
            "﻿" + """
            // a comment
            {
              /* block
                 comment */
              "name": "Pack",
              "uniqueId": "me.pack",
              "version": "1.0.0", // trailing comment
              "ContentPackFor": { "UniqueID": "Pathoschild.ContentPatcher", "MinimumVersion": "2.0.0", },
              "UpdateKeys": [ "Nexus:1", ],
            }
            """.trimIndent(),
        )
        assertEquals("me.pack", m.uniqueId)
        assertEquals("Pack", m.name)
        assertEquals(SmapiManifest.ContentPackFor("Pathoschild.ContentPatcher", "2.0.0"), m.contentPackFor)
        assertEquals(listOf("Nexus:1"), m.updateKeys)
        val core = m.toModManifest("x")
        assertEquals("Pathoschild.ContentPatcher", core.extra[SmapiManifest.EXTRA_CONTENT_PACK_FOR])
        assertEquals("2.0.0", core.extra[SmapiManifest.EXTRA_CONTENT_PACK_FOR_MIN_VERSION])
    }

    @Test
    fun missingFieldsFallBack() {
        val m = SmapiManifest.parse("""{ "Author": "Nobody" }""")
        assertNull(m.uniqueId)
        assertNull(m.name)
        assertNull(m.version)
        assertTrue(m.dependencies.isEmpty())
        val core = m.toModManifest("FolderName")
        assertEquals("FolderName", core.uniqueId)
        assertEquals("FolderName", core.name)
        assertEquals("", core.version)
        assertEquals("Nobody", core.author)
    }

    @Test
    fun dependencyWithoutIdIsSkipped() {
        val m = SmapiManifest.parse("""{"UniqueID":"a","Dependencies":[{"IsRequired":true},{"UniqueID":" b ","IsRequired":"false"}]}""")
        assertEquals(listOf(SmapiManifest.Dependency("b", false, null)), m.dependencies)
    }

    @Test
    fun invalidJsonFails() {
        assertFailsWith<IllegalArgumentException> { SmapiManifest.parse("""{ "Name": "x" """) }
        assertFailsWith<IllegalArgumentException> { SmapiManifest.parse("""[1,2]""") }
        assertNull(SmapiManifest.parseOrNull("not json"))
    }

    @Test
    fun jsonStringEscapes() {
        val v = TolerantJson.parse("""{"a":"x\"y\\zA\n", 'b': 'it\'s', c: -1.5e2, d: [true, false, null]}""") as Map<*, *>
        assertEquals("x\"y\\zA\n", v["a"])
        assertEquals("it's", v["b"])
        assertEquals(-150.0, v["c"])
        assertEquals(listOf(true, false, null), v["d"])
    }
}
