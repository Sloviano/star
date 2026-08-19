package com.starlink.scanner.ui.capture

import android.net.Network
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.starlink.scanner.data.local.ScanDao
import com.starlink.scanner.data.local.ScanRecord
import com.starlink.scanner.data.network.DishNetworkSource
import com.starlink.scanner.data.network.DishReachability
import com.starlink.scanner.data.network.DishWifiConnector
import com.starlink.scanner.data.settings.CaptureSettings
import com.starlink.scanner.data.starlink.StarlinkRepository
import com.starlink.scanner.di.ServiceLocator
import com.starlink.scanner.domain.BarcodeFormat
import com.starlink.scanner.domain.DishInfo
import com.starlink.scanner.domain.KitNumber
import com.starlink.scanner.domain.ScanMode
import com.starlink.scanner.domain.ScanTarget
import com.starlink.scanner.domain.UploadStatus
import com.starlink.scanner.ui.DishConnection
import com.starlink.scanner.ui.SessionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Drives the Capture screen. The camera is live from the start so the technician can scan the kit
 * and dish Data Matrix labels *while* a background loop probes dish-LAN reachability and reads the
 * dish ID over gRPC via [StarlinkRepository]. Once all three signalizers (kit, dish serial, dish ID)
 * are ready the record can be saved. Dependencies come from [ServiceLocator].
 */
class CaptureViewModel(
    private val starlink: StarlinkRepository,
    private val dishNetwork: DishNetworkSource,
    private val reachability: DishReachability,
    private val wifiConnector: DishWifiConnector,
    private val scanDao: ScanDao,
    private val settings: CaptureSettings,
    /**
     * Kick the deferred upload job after a save. Injected rather than reaching for
     * [ServiceLocator] inline, because WorkManager needs a real [android.content.Context] and that
     * put the whole save path out of reach of a JVM test.
     */
    private val enqueueUpload: () -> Unit = { ServiceLocator.enqueueUpload() },
) : ViewModel() {

    private val _state = MutableStateFlow<CaptureUiState>(CaptureUiState.Capturing())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    /** One pulse per accepted new signalizer fill (scan or dish-ID connect); UI beeps + vibrates. */
    private val _scanEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val scanEvents: SharedFlow<Unit> = _scanEvents.asSharedFlow()

    /** WiFi the phone is already on that we can bind to (tech connected manually), or null. */
    private var passiveNetwork: Network? = null

    /** WiFi we actively joined via the one-tap "STARLINK…" connect, or null. Takes precedence. */
    private var connectedNetwork: Network? = null

    /** The network the detection loop binds to: our app-initiated connection, else any usable WiFi. */
    private val currentNetwork: Network? get() = connectedNetwork ?: passiveNetwork

    /** The active dish-ID poll loop, if any. Cancelled whenever capture (re)starts. */
    private var pollJob: Job? = null

    /** The active WiFi-connect request, if any. */
    private var connectJob: Job? = null

    /**
     * The value the technician just rejected in the confirm dialog. The wrong code usually lingers in
     * frame right after "No", so we suppress re-prompting it until a *different* code is seen (or the
     * field is re-selected / capture restarts). Cleared in those places.
     */
    private var lastRejected: String? = null

    /**
     * The kit number OCR most recently reported, and how many frames running it has said the same
     * thing. OCR output flickers between frames, so a candidate has to hold still for
     * [REQUIRED_STABLE_FRAMES] before it is worth interrupting the technician with — otherwise a
     * single bad frame that happens to fit the pattern pops the confirmation dialog.
     */
    private var textCandidate: String? = null
    private var textCandidateFrames = 0

    /**
     * The capture mode, mirrored out of settings so [restartCapture] can carry it into the next
     * kit — building a fresh [CaptureUiState.Capturing] would otherwise silently drop the
     * technician back to barcode after every save.
     */
    private var scanMode = ScanMode.BARCODE

    /** Whether app-initiated one-tap connect is available on this device (API 29+). */
    val canConnectStarlink: Boolean get() = wifiConnector.isSupported

    init {
        // Track any WiFi the phone is already on; the detection loop reads [currentNetwork] each cycle.
        viewModelScope.launch {
            dishNetwork.dishNetworkFlow().collect { passiveNetwork = it }
        }
        // The capture mode is a persisted preference; the UI writes it through [onSetScanMode] and
        // reads it back here, so state stays single-sourced.
        viewModelScope.launch {
            settings.scanMode.collect { mode ->
                scanMode = mode
                resetTextCandidate()
                val current = _state.value
                if (current is CaptureUiState.Capturing && current.scanMode != mode) {
                    _state.value = current.copy(scanMode = mode)
                }
            }
        }
        // No auto-connect on start — the technician triggers joining the dish's "STARLINK…" AP by
        // tapping the Dish ID signalizer (see [onConnectStarlink]).
        restartCapture()
    }

    /**
     * Kick off (or restart) the app-initiated connect to the dish's open "STARLINK…" access point.
     * Android shows a one-time approval dialog; on approval the bound network flows into
     * [connectedNetwork] and the detection loop reads the dish ID over it. No-op on API < 29.
     */
    fun onConnectStarlink() {
        if (!wifiConnector.isSupported) return
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            wifiConnector.connectFlow().collect { network ->
                connectedNetwork = network
                // Remember which "STARLINK…" AP we actually landed on. This connection is app-scoped,
                // so it's also the one moment the SSID is readable without location permission — and
                // Settings ▸ Auto-join needs the exact name to suggest it device-wide later.
                if (network != null) rememberSsid(network)
            }
        }
    }

    /** Persist the connected AP's SSID, if it can be read and it's new. */
    private suspend fun rememberSsid(network: Network) {
        val ssid = wifiConnector.ssidOf(network) ?: return
        if (ssid != settings.dishSsid.first()) settings.setDishSsid(ssid)
    }

    /** Reset to a fresh capture and (re)start the background dish-ID connection loop. */
    fun restartCapture() {
        pollJob?.cancel()
        lastRejected = null
        resetTextCandidate()
        _state.value = CaptureUiState.Capturing(scanMode = scanMode)
        startDetectLoop()
    }

    /**
     * The capture screen became visible again — resume polling for the dish.
     *
     * Paired with [onScreenStopped] and driven from the screen's lifecycle, because
     * [viewModelScope] is not lifecycle-aware: without this the loop keeps opening sockets every
     * few seconds for as long as the ViewModel lives, including while the app sits in the
     * background with the camera already unbound. Over a shift that is continuous radio and CPU
     * work with nothing on screen to show for it.
     *
     * Resuming re-reads the dish rather than trusting what was on screen before, so a phone that
     * left the dish's WiFi while backgrounded reports that on the first poll.
     */
    fun onScreenStarted() {
        if (pollJob?.isActive == true) return
        if (_state.value !is CaptureUiState.Capturing) return // Summary owns the screen; nothing to poll.
        startDetectLoop()
    }

    /** The capture screen went away — stop polling until [onScreenStarted]. */
    fun onScreenStopped() {
        pollJob?.cancel()
    }

    private fun startDetectLoop() {
        pollJob = viewModelScope.launch { detectLoop() }
    }

    /**
     * Background connection loop that drives the dish-ID signalizer and the top status strip. Each
     * cycle probes whether the dish LAN is reachable on the current WiFi. It runs *alongside*
     * scanning, only ever touching the dish-ID/phase fields of [Capturing] — never the scanned
     * checklist — and keeps running for the whole capture (it does **not** stop once the ID is read).
     *
     * That way the signalizers track reality: when the dish is unplugged / the phone leaves the dish
     * WiFi, the probe fails and the dish-ID signalizer and the "Dish connected" strip revert to their
     * disconnected state; when reachable again it re-reads the ID. Polls every [POLL_INTERVAL_MS].
     *
     * The loop runs for the whole capture, so it must not emit a new state on every tick: an idle
     * poll that changes nothing visible would recompose the capture screen every few seconds for the
     * entire session. Both state writers below no-op when the render would be identical.
     */
    private suspend fun detectLoop() {
        var attempts = 0
        while (coroutineContext.isActive) {
            val current = _state.value
            if (current !is CaptureUiState.Capturing) return

            val network = currentNetwork
            if (!reachability.isReachable(network)) {
                // Off the dish LAN. If the ID had already been read, revert it (and the strip) to the
                // disconnected state so the signalizers reflect that the dish is gone.
                val phase = if (network == null) {
                    CaptureUiState.SearchPhase.NO_WIFI
                } else {
                    CaptureUiState.SearchPhase.NO_DISH
                }
                // The counter spans one reachable stretch, so "attempt N" means N tries at *this*
                // dish rather than a tally of every poll since the screen opened.
                attempts = 0
                SessionState.dishConnection.value = DishConnection.NONE
                setDisconnected(phase)
                delay(POLL_INTERVAL_MS)
                continue
            }

            // On the dish LAN and the ID is already read — stay connected, just keep watching.
            if (current.dishId != null) {
                SessionState.dishConnection.value = DishConnection.CONNECTED
                delay(POLL_INTERVAL_MS)
                continue
            }

            // On the dish LAN but no ID yet — read it over gRPC.
            attempts++
            SessionState.dishConnection.value = DishConnection.SEARCHING
            updateConnection(CaptureUiState.SearchPhase.CONNECTING, attempts)
            starlink.getDishInfo(network).onSuccess { info ->
                SessionState.dishConnection.value = DishConnection.CONNECTED
                setDishId(info.dishId)
            }
            // Reachable but gRPC not ready yet (dish still booting) — keep polling.
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * Update only the connection phase/attempts, leaving any scanned fields untouched. The attempt
     * count is on screen during [CaptureUiState.SearchPhase.CONNECTING], so each try really is a new
     * render — but skip the write when neither field moved.
     */
    private fun updateConnection(phase: CaptureUiState.SearchPhase, attempts: Int) {
        val current = _state.value as? CaptureUiState.Capturing ?: return
        if (current.dishId != null) return
        if (current.phase == phase && current.attempts == attempts) return
        _state.value = current.copy(phase = phase, attempts = attempts)
    }

    /**
     * Dish dropped off the LAN: clear the read dish ID and reset the phase/attempts, leaving the
     * scanned kit & dish-serial fields intact. No feedback pulse — that's reserved for new fills.
     *
     * While disconnected this is the common case on every poll, so it returns without emitting once
     * the state already says so.
     */
    private fun setDisconnected(phase: CaptureUiState.SearchPhase) {
        val current = _state.value as? CaptureUiState.Capturing ?: return
        if (current.dishId == null && current.phase == phase && current.attempts == 0) return
        _state.value = current.copy(dishId = null, phase = phase, attempts = 0)
    }

    /** Commit the dish ID into the signalizer and pulse capture feedback. */
    private fun setDishId(id: String) {
        val current = _state.value as? CaptureUiState.Capturing ?: return
        _state.value = current.copy(dishId = DishInfo.normalizeId(id), phase = CaptureUiState.SearchPhase.CONNECTED)
        _scanEvents.tryEmit(Unit)
    }

    /**
     * Feed a raw barcode value (from the ML Kit analyzer) into the **current target** field. The kit
     * and dish labels are both Data Matrix and carry no distinguishing text, so we can't classify a
     * scan by content — instead the UI captures one field at a time and each accepted scan fills
     * [CaptureUiState.Capturing.target], then advances to the next field.
     *
     * The camera reports the same code many times per second and, after we advance, the just-scanned
     * code lingers in frame, so we:
     *  - ignore everything once both fields are captured (null target) — the kit box carries further
     *    Data Matrix labels, and any of them drifting through frame would otherwise prompt to
     *    replace a value that is already correct;
     *  - reject non–Data Matrix reads (both the kit and dish labels are Data Matrix);
     *  - ignore any value already captured in the checklist, which debounces repeat frames *and*
     *    stops the lingering previous code from refilling the next field;
     *  - emit a [scanEvents] feedback pulse only on a real capture.
     */
    fun onScan(raw: String, format: BarcodeFormat) {
        if (format != BarcodeFormat.DATA_MATRIX) return
        offer(raw)
    }

    /**
     * Feed a frame of OCR text (from [TextAnalyzer]) into the kit field — the [ScanMode.TEXT] path,
     * for a Data Matrix label that won't decode.
     *
     * Unlike a barcode scan, which yields one unambiguous value, this arrives as everything the
     * camera could read on the box. [KitNumber.extract] picks the kit number out of it, and the
     * result must then hold still for [REQUIRED_STABLE_FRAMES] consecutive frames: OCR flickers,
     * and offering a one-frame misread would train the technician to dismiss the dialog.
     *
     * Kit only, by design — the dish serial is always scanned. Once a candidate is accepted it goes
     * through exactly the same [offer] path as a barcode, so the confirmation dialog, the
     * already-captured check and the rejected-value suppression all apply unchanged.
     */
    fun onScanText(rawText: String) {
        val current = _state.value as? CaptureUiState.Capturing ?: return
        if (current.scanMode != ScanMode.TEXT) return
        if (current.target != ScanTarget.KIT) return

        val candidate = KitNumber.extract(rawText)
        if (candidate == null) {
            // Nothing kit-shaped in this frame; the run of agreeing frames is broken.
            resetTextCandidate()
            return
        }
        if (candidate != textCandidate) {
            textCandidate = candidate
            textCandidateFrames = 1
            return
        }
        textCandidateFrames++
        if (textCandidateFrames < REQUIRED_STABLE_FRAMES) return
        offer(candidate)
    }

    /**
     * Hold [raw] for confirmation instead of committing it, so a mis-aimed read can be rejected
     * before it fills the field. Beep so the tech knows a code was captured to review.
     *
     * Shared by both capture paths. The camera reports the same value many times per second and,
     * after the target advances, the previous value lingers in frame — hence the guards.
     */
    private fun offer(raw: String) {
        val current = _state.value as? CaptureUiState.Capturing ?: return
        val target = current.target ?: return // everything captured — scanner is idle
        if (current.pendingScan != null) return // a code is already awaiting Accept/No confirmation

        val value = raw.trim()
        if (value.isBlank()) return
        if (value in current.checklist.values) return // repeat frame or the previous field's code
        if (value == lastRejected) return // the just-rejected code still lingering in frame

        _state.value = current.copy(pendingScan = CaptureUiState.PendingScan(target, value))
        _scanEvents.tryEmit(Unit)
    }

    /** Switch between scanning the kit's Data Matrix label and reading its printed number. */
    fun onSetScanMode(mode: ScanMode) {
        if (mode == scanMode) return
        viewModelScope.launch { settings.setScanMode(mode) }
    }

    /** Forget the in-progress OCR agreement run. */
    private fun resetTextCandidate() {
        textCandidate = null
        textCandidateFrames = 0
    }

    /** Accept the pending scan: commit it into its field and advance to the next one. */
    fun onConfirmScan() {
        val current = _state.value as? CaptureUiState.Capturing ?: return
        val pending = current.pendingScan ?: return
        lastRejected = null
        resetTextCandidate()
        val checklist = current.checklist.set(pending.target, pending.value)
        _state.value = current.copy(
            checklist = checklist,
            target = checklist.nextTarget(pending.target),
            pendingScan = null,
        )
    }

    /** Reject the pending scan (wrong code): drop it and keep scanning the same field. */
    fun onRejectScan() {
        val current = _state.value as? CaptureUiState.Capturing ?: return
        lastRejected = current.pendingScan?.value
        _state.value = current.copy(pendingScan = null)
    }

    /**
     * Manually set the dish serial, bypassing the camera. Fallback for a dish label that won't scan;
     * without it the mandatory dish serial could strand the technician. Blank input is ignored.
     */
    fun onEnterDishSerialManually(raw: String) {
        val current = _state.value as? CaptureUiState.Capturing ?: return
        val value = raw.trim()
        if (value.isBlank() || value in current.checklist.values) return
        fill(ScanTarget.DISH, value)
    }

    /**
     * Re-select which field the next scan fills (tapping a signalizer). Clears that field so a
     * fresh scan lands there — used to correct a mis-captured code.
     */
    fun onSelectTarget(target: ScanTarget) {
        val current = _state.value as? CaptureUiState.Capturing ?: return
        lastRejected = null
        resetTextCandidate()
        _state.value = current.copy(
            checklist = current.checklist.clear(target),
            target = target,
            pendingScan = null,
        )
    }

    /** Commit [value] into [target], advance to the next field, and pulse capture feedback. */
    private fun fill(target: ScanTarget, value: String) {
        val current = _state.value as? CaptureUiState.Capturing ?: return
        val checklist = current.checklist.set(target, value)
        _state.value = current.copy(
            checklist = checklist,
            target = checklist.nextTarget(target),
        )
        _scanEvents.tryEmit(Unit)
    }

    /** All three signalizers ready → build the record and move to Summary, flagging duplicates. */
    fun onReview() {
        val current = _state.value
        if (current !is CaptureUiState.Capturing || !current.canSave) return
        viewModelScope.launch {
            val record = ScanRecord(
                // Previewed, not claimed: backing out of the summary must not skip a number. The
                // value actually written is taken at save time (see [onSave]).
                counter = settings.nextCounter.first(),
                timestamp = System.currentTimeMillis(),
                dishId = current.dishId!!,
                kitNumber = current.checklist.kitNumber!!.trim(),
                dishSerial = current.checklist.dishSerial!!.trim(),
                status = UploadStatus.PENDING,
            )
            val duplicate = scanDao.countMatching(record.dishId, record.kitNumber) > 0
            pollJob?.cancel()
            _state.value = CaptureUiState.Summary(record, duplicate)
        }
    }

    /** Persist the record, enqueue the deferred upload, and reset for the next kit. */
    fun onSave(onSaved: () -> Unit) {
        val current = _state.value
        if (current !is CaptureUiState.Summary) return
        viewModelScope.launch {
            // Claim the counter here rather than reusing the one previewed on the summary: only a
            // record that is actually stored consumes a number, and the claim is atomic.
            scanDao.insert(current.record.copy(counter = settings.takeNextCounter()))
            enqueueUpload()
            onSaved()
            restartCapture()
        }
    }

    /** Back out of the summary without saving, resuming capture (and dish-ID connection). */
    fun onDiscard() = restartCapture()

    companion object {
        private const val POLL_INTERVAL_MS = 2_500L

        /** Frames an OCR candidate must repeat before it is offered for confirmation. */
        private const val REQUIRED_STABLE_FRAMES = 2

        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                return CaptureViewModel(
                    starlink = ServiceLocator.starlinkRepository,
                    dishNetwork = ServiceLocator.dishNetworkSource,
                    reachability = ServiceLocator.dishReachability,
                    wifiConnector = ServiceLocator.starlinkWifiConnector,
                    scanDao = ServiceLocator.scanDao,
                    settings = ServiceLocator.settingsRepository,
                ) as T
            }
        }
    }
}
