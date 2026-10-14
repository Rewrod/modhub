package com.modhub.stardew

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmapiVersionTest {

    @Test
    fun parses() {
        assertEquals(SmapiVersion(1, 2, 3), SmapiVersion.parse("1.2.3"))
        assertEquals(SmapiVersion(1, 0, 0), SmapiVersion.parse("1"))
        assertEquals(SmapiVersion(1, 6, 15, 1), SmapiVersion.parse("1.6.15.1"))
        assertEquals(SmapiVersion(4, 3, 2, 0, "beta.2"), SmapiVersion.parse("v4.3.2-beta.2+build5"))
        assertNull(SmapiVersion.parse("latest"))
        assertNull(SmapiVersion.parse(null))
    }

    @Test
    fun orders() {
        val sorted = listOf("1.10.0", "1.2.0", "1.2.0-beta", "1.2.0-alpha", "1.2", "0.9", "1.2.0-beta.10", "1.2.0-beta.2")
            .sortedWith(SmapiVersion.COMPARATOR)
        assertEquals(listOf("0.9", "1.2.0-alpha", "1.2.0-beta", "1.2.0-beta.2", "1.2.0-beta.10", "1.2.0", "1.2", "1.10.0"), sorted)
        assertTrue(SmapiVersion.compare("1.6.15.1", "1.6.15") > 0)
        assertTrue(SmapiVersion.compare("garbage", "1.0") < 0)
    }

    @Test
    fun isAtLeast() {
        assertTrue(SmapiVersion.isAtLeast("2.4.0", "2.0"))
        assertTrue(SmapiVersion.isAtLeast("2.0.0", "2.0.0"))
        assertFalse(SmapiVersion.isAtLeast("2.0.0-beta", "2.0.0"))
        assertFalse(SmapiVersion.isAtLeast("1.6.14", "1.6.15.1"))
        assertTrue(SmapiVersion.isAtLeast("anything", null))
        assertFalse(SmapiVersion.isAtLeast(null, "1.0"))
    }
}
