package com.starlink.scanner.ui.capture

import androidx.camera.core.ImageAnalysis
import java.io.Closeable

/**
 * A CameraX analyzer backed by an ML Kit model.
 *
 * [Closeable] is part of the contract because [CameraScanner] swaps analyzers whenever the
 * technician changes [com.starlink.scanner.domain.ScanMode], and each ML Kit client holds a loaded
 * model that should be released rather than left for the garbage collector.
 */
interface FrameAnalyzer : ImageAnalysis.Analyzer, Closeable
