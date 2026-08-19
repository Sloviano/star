package com.starlink.scanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DishInfoTest {

    @Test
    fun normalizeId_dropsTheUtPrefix() {
        assertEquals(
            "01000000-00000000-00001234",
            DishInfo.normalizeId("ut01000000-00000000-00001234"),
        )
    }

    @Test
    fun normalizeId_isCaseInsensitiveAboutThePrefix() {
        assertEquals("01000000", DishInfo.normalizeId("UT01000000"))
        assertEquals("01000000", DishInfo.normalizeId("Ut01000000"))
    }

    @Test
    fun normalizeId_leavesAnythingElseIntact() {
        // The dish ID is the record's only link back to the physical unit, so an ID that doesn't
        // carry the prefix must reach the sheet whole rather than losing its first two characters.
        assertEquals("01000000-0000", DishInfo.normalizeId("01000000-0000"))
        assertEquals("SN-1234", DishInfo.normalizeId("SN-1234"))
        assertEquals("ut", DishInfo.normalizeId("ut"))
        assertEquals("", DishInfo.normalizeId(""))
    }
}
