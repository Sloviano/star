package com.starlink.scanner.domain

/**
 * Picks the kit number out of a frame of OCR text (`ScanMode.TEXT`).
 *
 * A Data Matrix scan yields one unambiguous value; OCR yields *everything* the camera can read —
 * brand names, warnings, addresses, part numbers. Something has to decide which of those is the kit
 * number, and that is this object's whole job. It is deliberately free of ML Kit and Android types
 * so it can be unit-tested directly, like [Checklist] and [DishInfo.normalizeId].
 *
 * Known kit numbers look like `KIT4M06183988NHK`: the fixed prefix [KNOWN_PREFIX] followed by ten
 * more uppercase-alphanumeric characters, [STRONG_LENGTH] in total.
 *
 * Matching is two-tier on purpose. [KNOWN_PREFIX] is almost certainly a product or batch code, and
 * it was derived from a handful of real values — so keying solely on it would mean a future kit
 * generation silently stops being recognised, with no error and nothing on screen to explain why.
 * The fallback keeps such a kit capturable; because every candidate still goes through the
 * technician's Accept/No confirmation, a wrong guess costs one tap.
 */
object KitNumber {

    /** Invariant prefix of every kit number seen so far. */
    const val KNOWN_PREFIX = "KIT4M0"

    /** Total length of a kit number in the known format. */
    const val STRONG_LENGTH = 16

    /** The known format: [KNOWN_PREFIX] plus ten more characters. */
    private val STRONG = Regex("$KNOWN_PREFIX[A-Z0-9]{${STRONG_LENGTH - KNOWN_PREFIX.length}}")

    /** Any plausible `KIT…` code, used only when no [STRONG] match is in frame. */
    private val FALLBACK = Regex("KIT[A-Z0-9]{5,25}")

    /** Splits OCR text into candidate words. */
    private val NON_ALPHANUMERIC = Regex("[^A-Z0-9]+")

    // OCR reliably confuses a few glyphs. Both substitutions below only ever rewrite a *fixed
    // literal*, so they cannot corrupt real data. Nothing normalises the variable part of the code:
    // there, 0/O and 1/I are meaningful, and a handful of samples is no basis for guessing which
    // was meant. A misread there is the confirmation dialog's job to catch.
    private val FUZZY_KIT = Regex("K[I1L]T")
    private val FUZZY_KNOWN_PREFIX = Regex("KIT[4A]M[0OQ]")

    /**
     * The kit number in [rawText], or null when the frame holds nothing that looks like one.
     *
     * @param rawText everything OCR read from one frame, newlines and all.
     */
    fun extract(rawText: String): String? {
        val text = normalize(rawText.uppercase())

        // Strong tier runs against the text with separators removed, because OCR routinely breaks a
        // long code across lines or blocks ("KIT4M0\n6183988NHK"). Rejoining is safe here precisely
        // because this pattern is a fixed length — it cannot run on and swallow the next word.
        STRONG.find(text.filterNot { it.isWhitespace() || it == '-' })?.let { return it.value }

        // Fallback tier matches whole words only. Running it against the rejoined text instead would
        // let it grab an arbitrary 25-character slice of one long blob, which is worse than useless.
        return text.split(NON_ALPHANUMERIC)
            .filter { FALLBACK.matches(it) }
            .maxByOrNull { it.length }
    }

    private fun normalize(upper: String): String =
        FUZZY_KNOWN_PREFIX.replace(FUZZY_KIT.replace(upper, "KIT"), KNOWN_PREFIX)
}
