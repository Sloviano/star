package com.starlink.scanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistTest {

    @Test
    fun set_putsValueInTargetField() {
        val list = Checklist()
            .set(ScanTarget.KIT, "KIT-1")
            .set(ScanTarget.DISH, "DISH-1")
        assertEquals("KIT-1", list.kitNumber)
        assertEquals("DISH-1", list.dishSerial)
    }

    @Test
    fun canFinish_needsKitAndDish() {
        assertFalse(Checklist().canFinish)
        assertFalse(Checklist().set(ScanTarget.KIT, "KIT-1").canFinish)
        assertFalse(Checklist().set(ScanTarget.DISH, "DISH-1").canFinish)
        val ready = Checklist().set(ScanTarget.KIT, "KIT-1").set(ScanTarget.DISH, "DISH-1")
        assertTrue(ready.canFinish)
    }

    @Test
    fun clear_emptiesTargetField() {
        val cleared = Checklist(kitNumber = "KIT-1", dishSerial = "DISH-1").clear(ScanTarget.DISH)
        assertEquals("KIT-1", cleared.kitNumber)
        assertNull(cleared.dishSerial)
    }

    @Test
    fun values_containsOnlyCapturedFields() {
        val list = Checklist(kitNumber = "KIT-1", dishSerial = "DISH-1")
        assertEquals(listOf("KIT-1", "DISH-1"), list.values)
        // The dedup check that stops a lingering code from refilling the next field relies on this.
        assertTrue("DISH-1" in list.values)
    }

    @Test
    fun valueOf_readsBackTheTargetField() {
        val list = Checklist(kitNumber = "KIT-1")
        assertEquals("KIT-1", list.valueOf(ScanTarget.KIT))
        assertNull(list.valueOf(ScanTarget.DISH))
    }
}
