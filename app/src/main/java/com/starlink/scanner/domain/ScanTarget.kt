package com.starlink.scanner.domain

/**
 * Which checklist field the scanner is currently capturing.
 *
 * Because the kit and dish labels share the same (Data Matrix) symbology and carry no reliable
 * distinguishing text, the app cannot classify a scan by its content — instead it captures one
 * field at a time, and each accepted scan fills the current target. Order is [KIT] → [DISH]; both
 * are required.
 */
enum class ScanTarget { KIT, DISH }
