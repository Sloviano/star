package com.starlink.scanner.domain

/**
 * Live capture checklist shown on the scanning screen. Kit number and dish serial are both
 * required to finish.
 */
data class Checklist(
    val kitNumber: String? = null,
    val dishSerial: String? = null,
) {
    /** The technician may finish (enable "Done") once the kit number and dish serial are captured. */
    val canFinish: Boolean get() = !kitNumber.isNullOrBlank() && !dishSerial.isNullOrBlank()

    /** The non-blank values captured so far — used to reject re-reads of an already-captured code. */
    val values: List<String> get() = listOfNotNull(kitNumber, dishSerial)

    /** The value currently held in [target], if any. */
    fun valueOf(target: ScanTarget): String? = when (target) {
        ScanTarget.KIT -> kitNumber
        ScanTarget.DISH -> dishSerial
    }

    /** Put [value] into the field named by [target], returning the updated copy. */
    fun set(target: ScanTarget, value: String): Checklist = when (target) {
        ScanTarget.KIT -> copy(kitNumber = value)
        ScanTarget.DISH -> copy(dishSerial = value)
    }

    /** Empty the field named by [target] (so it can be re-scanned), returning the updated copy. */
    fun clear(target: ScanTarget): Checklist = when (target) {
        ScanTarget.KIT -> copy(kitNumber = null)
        ScanTarget.DISH -> copy(dishSerial = null)
    }
}
