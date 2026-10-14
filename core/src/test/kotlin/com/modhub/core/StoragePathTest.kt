package com.modhub.core

import com.modhub.core.storage.StoragePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoragePathTest {
    @Test
    fun normalises() {
        assertEquals("/a/b", StoragePath.of("/a//./c/../b/").value)
        assertEquals("/", StoragePath.of("/").value)
        assertNull(StoragePath.ROOT.parent)
        assertEquals(StoragePath.of("/a"), StoragePath.of("/a/b").parent)
    }

    @Test
    fun `resolve cannot escape`() {
        val base = StoragePath.of("/mods")
        assertFailsWith<IllegalArgumentException> { base.resolve("../x") }
        assertEquals("/mods/x/y", base.resolve("x/y").value)
        assertTrue(base.resolve("x").isWithin(base))
        assertEquals("x/y", base.resolve("x/y").relativeTo(base))
    }

    @Test
    fun `rejects relative input`() {
        assertFailsWith<IllegalArgumentException> { StoragePath.of("relative") }
    }
}
