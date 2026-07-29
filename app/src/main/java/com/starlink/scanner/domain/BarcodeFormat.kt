package com.starlink.scanner.domain

/**
 * Barcode symbology, mapped from ML Kit at the analyzer boundary so the domain (and the capture
 * state machine) stays free of ML Kit types. Only the formats the kit box uses are distinguished;
 * everything else is [OTHER].
 *
 * The kit-number and dish-serial labels are **both** Data Matrix, so symbology cannot tell them
 * apart — the capture flow disambiguates them by scanning one field at a time (see [ScanTarget]).
 * The format is still used to reject non–Data Matrix reads on those steps.
 */
enum class BarcodeFormat { DATA_MATRIX, CODE_128, QR, OTHER }
