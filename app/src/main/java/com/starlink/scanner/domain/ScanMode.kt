package com.starlink.scanner.domain

/**
 * How the camera captures the kit number.
 *
 * The kit box carries the number twice — as a Data Matrix label and as printed text. [BARCODE] is
 * the default and the more reliable of the two; [TEXT] exists for the label that won't decode
 * because it is damaged, smudged or wrapped around a corner, which would otherwise leave the
 * technician unable to save the kit at all.
 *
 * Applies to [ScanTarget.KIT] only. The dish serial is always captured from its Data Matrix label
 * (it has [com.starlink.scanner.ui.capture.CaptureViewModel.onEnterDishSerialManually] as its own
 * escape hatch).
 */
enum class ScanMode { BARCODE, TEXT }
