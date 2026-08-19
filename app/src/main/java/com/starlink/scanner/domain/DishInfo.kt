package com.starlink.scanner.domain

/**
 * Dish identity read from the dish's local gRPC API. Only the dish ID (UT ID) is retained — it is
 * the sole dish field the app records, alongside the scanned kit number and dish serial.
 */
data class DishInfo(
    val dishId: String,
) {
    companion object {
        /**
         * Drop the "ut" prefix Starlink dish IDs carry, e.g.
         * "ut01000000-00000000-00001234" → "01000000-00000000-00001234". This is the form the app
         * displays and writes to the sheet.
         *
         * Anything that doesn't start with "ut" is returned unchanged rather than trimmed blindly:
         * firmware that reports an ID in some other shape must reach the sheet intact, since the
         * value is the record's only link back to the physical dish.
         */
        fun normalizeId(raw: String): String =
            if (raw.length > 2 && raw.startsWith("ut", ignoreCase = true)) raw.substring(2) else raw
    }
}
