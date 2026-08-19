package com.starlink.scanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The kit number is the one field with no manual-entry fallback, and OCR hands us every word on the
 * box, so this pattern is what stands between the technician and a sheet full of "STARLINK".
 *
 * The three values used here are real.
 */
class KitNumberTest {

    private val real = listOf(
        "KIT4M06183988NHK",
        "KIT4M06186696RFT",
        "KIT4M061763617VS",
    )

    @Test
    fun extract_findsARealKitNumberOnItsOwn() {
        real.forEach { assertEquals(it, KitNumber.extract(it)) }
    }

    @Test
    fun extract_findsItAmongTheRestOfTheBoxText() {
        val frame = """
            STARLINK
            Standard Kit
            KIT4M06183988NHK
            Made in USA
            Model: UTA-303
        """.trimIndent()
        assertEquals("KIT4M06183988NHK", KitNumber.extract(frame))
    }

    @Test
    fun extract_acceptsLowercaseOcrOutput() {
        assertEquals("KIT4M06186696RFT", KitNumber.extract("kit4m06186696rft"))
    }

    @Test
    fun extract_rejoinsACodeOcrSplitAcrossLines() {
        // The common OCR failure on a long code: the line breaks mid-value.
        assertEquals("KIT4M06183988NHK", KitNumber.extract("KIT4M0\n6183988NHK"))
        assertEquals("KIT4M06183988NHK", KitNumber.extract("KIT4M0 6183988 NHK"))
    }

    @Test
    fun extract_ignoresGroupingHyphensPrintedOnTheLabel() {
        assertEquals("KIT4M061763617VS", KitNumber.extract("KIT4M0-6176361-7VS"))
    }

    @Test
    fun extract_repairsTheFixedPrefixWhenOcrMisreadsIt() {
        // Only the fixed literal is repaired — never the variable part, where 0/O is real data.
        assertEquals("KIT4M06183988NHK", KitNumber.extract("K1T4M06183988NHK"))
        assertEquals("KIT4M06183988NHK", KitNumber.extract("KIT4MO6183988NHK"))
        assertEquals("KIT4M06183988NHK", KitNumber.extract("KlT4M06183988NHK"))
        assertEquals("KIT4M06186696RFT", KitNumber.extract("KITAM06186696RFT"))
    }

    @Test
    fun extract_returnsNullForABoxWithNoKitNumberInFrame() {
        assertNull(KitNumber.extract("STARLINK"))
        assertNull(KitNumber.extract("Made in USA"))
        assertNull(KitNumber.extract(""))
        assertNull(KitNumber.extract("Model: UTA-303\nInput: 48V"))
    }

    @Test
    fun extract_ignoresAKitTokenTooShortToBeACode() {
        assertNull(KitNumber.extract("KIT"))
        assertNull(KitNumber.extract("KIT123"))
    }

    @Test
    fun extract_prefersTheKnownFormatOverAnyOtherKitToken() {
        // A box carrying both a long unrelated KIT code and the real one must yield the real one,
        // regardless of which OCR reported first.
        val frame = "KITZZZZZZZZZZZZZZZZZZZZ\nKIT4M06183988NHK"
        assertEquals("KIT4M06183988NHK", KitNumber.extract(frame))
        assertEquals("KIT4M06183988NHK", KitNumber.extract("KIT4M06183988NHK\nKITZZZZZZZZZZZZZZZZZZZZ"))
    }

    @Test
    fun extract_fallsBackToAnUnrecognisedKitFormat() {
        // A future kit generation with a different prefix must still be capturable — silently
        // refusing to see it would strand the technician with no way to save the kit.
        assertEquals("KIT9X99123456ABC", KitNumber.extract("STARLINK\nKIT9X99123456ABC\nMade in USA"))
    }

    @Test
    fun extract_fallbackTakesTheLongestCandidateAndStaysWithinOneWord() {
        // Whole words only: the fallback must never slice an arbitrary run out of the joined text.
        assertEquals("KIT9X99123456ABC", KitNumber.extract("KIT12345 KIT9X99123456ABC"))
    }
}
