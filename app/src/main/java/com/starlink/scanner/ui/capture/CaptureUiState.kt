package com.starlink.scanner.ui.capture

import com.starlink.scanner.data.local.ScanRecord
import com.starlink.scanner.domain.Checklist
import com.starlink.scanner.domain.ScanMode
import com.starlink.scanner.domain.ScanTarget

/** The two states of the Capture screen state machine. */
sealed interface CaptureUiState {

    /** Where the background dish-ID connection is. */
    enum class SearchPhase {
        /** Not connected to any WiFi network. */
        NO_WIFI,

        /** On WiFi, but the dish endpoint isn't reachable there (not the Starlink AP). */
        NO_DISH,

        /** Dish LAN reachable — reading its ID over gRPC. */
        CONNECTING,

        /** Dish ID read. */
        CONNECTED,
    }

    /**
     * The single working screen: the camera is live so the technician can scan the kit and dish
     * Data Matrix labels *while* the app connects to the dish in the background to read its ID.
     * Three signalizers — kit number, dish serial, dish ID — light up as each becomes ready, and
     * saving unlocks once all three are captured.
     */
    data class Capturing(
        /** Dish ID from the gRPC connection, or null until connected. */
        val dishId: String? = null,
        /** Progress of the background dish-ID connection. */
        val phase: SearchPhase = SearchPhase.NO_WIFI,
        /** Connection attempt counter, shown while still connecting. */
        val attempts: Int = 0,
        /** Scanned kit number + dish serial. */
        val checklist: Checklist = Checklist(),
        /**
         * The field the next accepted scan fills (guided capture — kit and dish look identical), or
         * null once both are captured: scanning goes idle until a signalizer is tapped to re-open a
         * field. Null therefore implies [Checklist.canFinish].
         */
        val target: ScanTarget? = ScanTarget.KIT,
        /** A just-scanned code awaiting the technician's Accept/No confirmation, or null. */
        val pendingScan: PendingScan? = null,
        /**
         * How the kit number is being captured. Applies to [ScanTarget.KIT] only — the dish serial
         * is always read from its Data Matrix label.
         */
        val scanMode: ScanMode = ScanMode.BARCODE,
    ) : CaptureUiState {
        /** All three signalizers ready — kit, dish serial, and dish ID captured. */
        val canSave: Boolean get() = !dishId.isNullOrBlank() && checklist.canFinish
    }

    /**
     * A scanned value held for confirmation before it's committed to [target]. Guards against a
     * mis-aimed scan grabbing the wrong Data Matrix code — the kit and dish labels look identical.
     */
    data class PendingScan(
        val target: ScanTarget,
        val value: String,
    )

    /** Read-only recap before saving. */
    data class Summary(
        val record: ScanRecord,
        val duplicate: Boolean,
    ) : CaptureUiState
}
