package com.starlink.scanner.ui.capture

import com.starlink.scanner.domain.BarcodeFormat
import com.starlink.scanner.domain.DishInfo
import com.starlink.scanner.domain.ScanMode
import com.starlink.scanner.domain.ScanTarget
import com.starlink.scanner.domain.StarlinkError
import com.starlink.scanner.fakes.FakeCaptureSettings
import com.starlink.scanner.fakes.FakeDishNetworkSource
import com.starlink.scanner.fakes.FakeDishReachability
import com.starlink.scanner.fakes.FakeDishWifiConnector
import com.starlink.scanner.fakes.FakeScanDao
import com.starlink.scanner.fakes.FakeStarlinkRepository
import com.starlink.scanner.data.local.ScanRecord
import com.starlink.scanner.domain.UploadStatus
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives [CaptureViewModel] through fakes for its six dependencies.
 *
 * Two things shape how these are written:
 *
 *  - The detect loop never terminates, so the tests step the clock with [advanceTimeBy] and
 *    [runCurrent] rather than `advanceUntilIdle`, which would spin forever chasing the next poll.
 *  - No test can produce a real [android.net.Network] (final platform class, stub android.jar), so
 *    the fakes report "no network" throughout. That covers NO_WIFI, CONNECTING and CONNECTED;
 *    telling NO_DISH from NO_WIFI turns on a non-null Network and needs an instrumented test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var starlink: FakeStarlinkRepository
    private lateinit var reachability: FakeDishReachability
    private lateinit var dao: FakeScanDao
    private lateinit var settings: FakeCaptureSettings
    private lateinit var connector: FakeDishWifiConnector
    private var uploadsEnqueued = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Run [body] against a fresh ViewModel, then cancel it.
     *
     * The cancel is not optional: the detect loop polls forever, and `runTest` finishes by draining
     * the scheduler — with a live loop still scheduling the next `delay`, that drain never ends and
     * the test hangs in virtual time rather than failing. Clearing a [ViewModelStore] is the public
     * way to cancel `viewModelScope`, so the ViewModel is parked in one for the duration.
     */
    private fun captureTest(
        reachable: Boolean = false,
        dishResult: Result<DishInfo> = Result.success(DishInfo("ut01000000-00000000-00001234")),
        records: List<ScanRecord> = emptyList(),
        counter: Long = 1L,
        mode: ScanMode = ScanMode.BARCODE,
        body: suspend TestScope.(CaptureViewModel) -> Unit,
    ) = runTest(dispatcher) {
        starlink = FakeStarlinkRepository(dishResult)
        reachability = FakeDishReachability(reachable)
        dao = FakeScanDao(records)
        settings = FakeCaptureSettings(counter, mode = mode)
        connector = FakeDishWifiConnector()
        uploadsEnqueued = 0

        val store = ViewModelStore()
        val vm = CaptureViewModel(
            starlink, FakeDishNetworkSource(), reachability, connector, dao, settings,
            enqueueUpload = { uploadsEnqueued++ },
        )
        store.put("capture", vm)
        try {
            // The loop's first pass runs at t=0, so every test starts from a settled first state.
            runCurrent()
            body(vm)
        } finally {
            store.clear()
            runCurrent()
        }
    }

    private val CaptureViewModel.capturing: CaptureUiState.Capturing
        get() = state.value as CaptureUiState.Capturing

    private fun CaptureViewModel.scan(value: String, format: BarcodeFormat = BarcodeFormat.DATA_MATRIX) =
        onScan(value, format)

    /** Accept a scan the way the UI does: capture, then confirm. */
    private fun CaptureViewModel.captureAndConfirm(value: String) {
        scan(value)
        onConfirmScan()
    }

    // --- Scanning ---

    @Test
    fun scan_holdsTheValueForConfirmationInsteadOfCommittingIt() = captureTest() { vm ->

        vm.scan("KIT-1")

        // The kit and dish labels are identical Data Matrix codes, so a mis-aimed read must be
        // rejectable before it lands in a field.
        assertEquals(CaptureUiState.PendingScan(ScanTarget.KIT, "KIT-1"), vm.capturing.pendingScan)
        assertNull(vm.capturing.checklist.kitNumber)
    }

    @Test
    fun confirmScan_commitsTheValueAndAimsAtTheOtherField() = captureTest() { vm ->

        vm.captureAndConfirm("KIT-1")

        assertEquals("KIT-1", vm.capturing.checklist.kitNumber)
        assertEquals(ScanTarget.DISH, vm.capturing.target)
        assertNull(vm.capturing.pendingScan)
    }

    @Test
    fun rejectScan_dropsTheValueAndSuppressesItUntilADifferentCodeAppears() = captureTest() { vm ->

        vm.scan("WRONG-1")
        vm.onRejectScan()
        assertNull(vm.capturing.pendingScan)

        // The rejected code usually lingers in frame; re-prompting for it would be a loop.
        vm.scan("WRONG-1")
        assertNull(vm.capturing.pendingScan)

        vm.scan("KIT-1")
        assertEquals(CaptureUiState.PendingScan(ScanTarget.KIT, "KIT-1"), vm.capturing.pendingScan)
    }

    @Test
    fun scan_ignoresFormatsOtherThanDataMatrix() = captureTest() { vm ->

        vm.scan("KIT-1", BarcodeFormat.QR)
        vm.scan("KIT-1", BarcodeFormat.CODE_128)

        assertNull(vm.capturing.pendingScan)
    }

    @Test
    fun scan_ignoresRepeatFramesOfAnAlreadyCapturedCode() = captureTest() { vm ->
        vm.captureAndConfirm("KIT-1")

        // The camera reports the same code many times a second, and the just-committed one lingers
        // in frame while the target advances — it must not refill the next field.
        vm.scan("KIT-1")

        assertNull(vm.capturing.pendingScan)
        assertNull(vm.capturing.checklist.dishSerial)
        assertEquals(ScanTarget.DISH, vm.capturing.target)
    }

    @Test
    fun scan_goesIdleOnceBothFieldsAreCaptured() = captureTest() { vm ->
        vm.captureAndConfirm("KIT-1")
        vm.captureAndConfirm("DISH-1")

        assertNull("scanner should idle rather than aim at a correct field", vm.capturing.target)

        // The kit box carries further Data Matrix labels; none may offer to replace a good value.
        vm.scan("SOME-OTHER-LABEL")
        assertNull(vm.capturing.pendingScan)
        assertEquals("KIT-1", vm.capturing.checklist.kitNumber)
        assertEquals("DISH-1", vm.capturing.checklist.dishSerial)
    }

    @Test
    fun scan_ignoresBlankValues() = captureTest() { vm ->

        vm.scan("   ")

        assertNull(vm.capturing.pendingScan)
    }

    @Test
    fun selectTarget_clearsThatFieldAndReopensItForScanning() = captureTest() { vm ->
        vm.captureAndConfirm("KIT-1")
        vm.captureAndConfirm("DISH-1")

        vm.onSelectTarget(ScanTarget.KIT)

        assertNull(vm.capturing.checklist.kitNumber)
        assertEquals("DISH-1", vm.capturing.checklist.dishSerial)
        assertEquals(ScanTarget.KIT, vm.capturing.target)
    }

    @Test
    fun enterDishSerialManually_fillsTheFieldWithoutTheCamera() = captureTest() { vm ->

        vm.onEnterDishSerialManually("  DISH-TYPED  ")

        // Fallback for a label that won't scan — without it the mandatory serial strands the tech.
        assertEquals("DISH-TYPED", vm.capturing.checklist.dishSerial)
    }

    // --- Text (OCR) capture of the kit number ---

    /** A realistic frame: the kit number surrounded by everything else printed on the box. */
    private fun boxText(kit: String) = "STARLINK\nStandard Kit\n$kit\nMade in USA"

    @Test
    fun textScan_offersTheKitNumberOnceItHoldsStill() = captureTest(mode = ScanMode.TEXT) { vm ->
        val frame = boxText("KIT4M06183988NHK")

        // One frame is not enough — OCR flickers, and a single bad frame must not reach the dialog.
        vm.onScanText(frame)
        assertNull(vm.capturing.pendingScan)

        vm.onScanText(frame)
        assertEquals(
            CaptureUiState.PendingScan(ScanTarget.KIT, "KIT4M06183988NHK"),
            vm.capturing.pendingScan,
        )
    }

    @Test
    fun textScan_needsConsecutiveAgreementNotJustTwoSightings() = captureTest(mode = ScanMode.TEXT) { vm ->
        vm.onScanText(boxText("KIT4M06183988NHK"))
        vm.onScanText(boxText("KIT4M06186696RFT")) // a disagreeing frame breaks the run
        vm.onScanText(boxText("KIT4M06183988NHK"))

        assertNull(vm.capturing.pendingScan)
    }

    @Test
    fun textScan_ignoresFramesWithNoKitNumberInThem() = captureTest(mode = ScanMode.TEXT) { vm ->
        repeat(5) { vm.onScanText("STARLINK\nMade in USA\nModel: UTA-303") }
        assertNull(vm.capturing.pendingScan)
    }

    @Test
    fun textScan_isIgnoredInBarcodeMode() = captureTest(mode = ScanMode.BARCODE) { vm ->
        repeat(4) { vm.onScanText(boxText("KIT4M06183988NHK")) }
        assertNull(vm.capturing.pendingScan)
    }

    @Test
    fun textScan_neverFillsTheDishSerial() = captureTest(mode = ScanMode.TEXT) { vm ->
        vm.captureAndConfirm("KIT-SCANNED")
        assertEquals(ScanTarget.DISH, vm.capturing.target)

        // Text mode is kit-only: the dish serial is always read from its Data Matrix label.
        repeat(4) { vm.onScanText(boxText("KIT4M06183988NHK")) }

        assertNull(vm.capturing.pendingScan)
        assertNull(vm.capturing.checklist.dishSerial)
    }

    @Test
    fun textScan_commitsThroughTheSameConfirmPathAsABarcode() = captureTest(mode = ScanMode.TEXT) { vm ->
        repeat(2) { vm.onScanText(boxText("KIT4M06183988NHK")) }
        vm.onConfirmScan()

        assertEquals("KIT4M06183988NHK", vm.capturing.checklist.kitNumber)
        assertEquals(ScanTarget.DISH, vm.capturing.target)
    }

    @Test
    fun textScan_rejectedValueStaysSuppressedUntilADifferentOneIsRead() = captureTest(mode = ScanMode.TEXT) { vm ->
        val wrong = boxText("KIT4M06183988NHK")
        repeat(2) { vm.onScanText(wrong) }
        vm.onRejectScan()

        // The same misread lingers in frame; re-offering it would loop.
        repeat(4) { vm.onScanText(wrong) }
        assertNull(vm.capturing.pendingScan)

        repeat(2) { vm.onScanText(boxText("KIT4M06186696RFT")) }
        assertEquals("KIT4M06186696RFT", vm.capturing.pendingScan?.value)
    }

    @Test
    fun setScanMode_persistsAndReachesTheState() = captureTest(mode = ScanMode.BARCODE) { vm ->
        assertEquals(ScanMode.BARCODE, vm.capturing.scanMode)

        vm.onSetScanMode(ScanMode.TEXT)
        runCurrent()

        assertEquals(ScanMode.TEXT, vm.capturing.scanMode)
        assertEquals(ScanMode.TEXT, settings.scanMode.value)
    }

    @Test
    fun scanMode_survivesSavingAKitAndStartingTheNext() = captureTest(
        reachable = true,
        mode = ScanMode.TEXT,
    ) { vm ->
        repeat(2) { vm.onScanText(boxText("KIT4M06183988NHK")) }
        vm.onConfirmScan()
        vm.captureAndConfirm("DISH-1")
        vm.onReview()
        runCurrent()
        vm.onSave {}
        runCurrent()

        // Dropping back to barcode after every save would be a silent trap on a pallet of bad labels.
        assertEquals(ScanMode.TEXT, vm.capturing.scanMode)
    }

    // --- Detect loop ---

    @Test
    fun detectLoop_reportsNoWifiWhileNothingIsReachable() = captureTest(reachable = false) { vm ->

        assertEquals(CaptureUiState.SearchPhase.NO_WIFI, vm.capturing.phase)
        assertNull(vm.capturing.dishId)
        assertEquals(0, starlink.calls)
    }

    @Test
    fun detectLoop_readsAndNormalizesTheDishIdOnceReachable() = captureTest(reachable = true) { vm ->

        assertEquals(CaptureUiState.SearchPhase.CONNECTED, vm.capturing.phase)
        // Stored and shown without the "ut" prefix.
        assertEquals("01000000-00000000-00001234", vm.capturing.dishId)
        assertEquals(1, starlink.calls)
    }

    @Test
    // Reachable over TCP but gRPC not answering yet — the documented just-powered-on case.
    fun detectLoop_keepsPollingWhileTheDishIsStillBooting() = captureTest(
        reachable = true,
        dishResult = Result.failure(StarlinkError.Unavailable()),
    ) { vm ->

        assertEquals(CaptureUiState.SearchPhase.CONNECTING, vm.capturing.phase)
        assertEquals(1, vm.capturing.attempts)

        advanceTimeBy(2_600)
        assertEquals(2, vm.capturing.attempts)
        assertEquals(2, starlink.calls)
        assertNull(vm.capturing.dishId)
    }

    @Test
    fun detectLoop_revertsTheDishIdWhenTheDishGoesAwayButKeepsScannedFields() = captureTest(reachable = true) { vm ->
        vm.captureAndConfirm("KIT-1")
        assertEquals("01000000-00000000-00001234", vm.capturing.dishId)

        reachability.reachable = false
        advanceTimeBy(2_600)

        // The signalizers must track reality: unplugging the dish clears its ID...
        assertNull(vm.capturing.dishId)
        assertEquals(CaptureUiState.SearchPhase.NO_WIFI, vm.capturing.phase)
        assertEquals(0, vm.capturing.attempts)
        // ...without discarding work the technician already scanned.
        assertEquals("KIT-1", vm.capturing.checklist.kitNumber)
    }

    @Test
    fun detectLoop_rereadsTheDishIdWhenItComesBack() = captureTest(reachable = true) { vm ->
        reachability.reachable = false
        advanceTimeBy(2_600)
        assertNull(vm.capturing.dishId)

        reachability.reachable = true
        advanceTimeBy(2_600)

        assertEquals("01000000-00000000-00001234", vm.capturing.dishId)
        assertEquals(CaptureUiState.SearchPhase.CONNECTED, vm.capturing.phase)
    }

    @Test
    fun detectLoop_doesNotReEmitStateOnIdlePolls() = captureTest(reachable = false) { vm ->
        val emissions = mutableListOf<CaptureUiState>()
        backgroundScope.launch { vm.state.collect { emissions += it } }
        runCurrent()
        emissions.clear()

        // Ten polls with nothing changing. The loop runs for the whole capture, so an idle tick
        // that emitted would recompose the camera screen every few seconds all session long.
        advanceTimeBy(26_000)

        assertTrue("idle polls must not emit, saw ${emissions.size}", emissions.isEmpty())
        assertTrue("but the loop must still be polling", reachability.calls > 5)
    }

    @Test
    fun detectLoop_doesNotReEmitWhileConnectedAndUnchanged() = captureTest(reachable = true) { vm ->
        val emissions = mutableListOf<CaptureUiState>()
        backgroundScope.launch { vm.state.collect { emissions += it } }
        runCurrent()
        emissions.clear()

        advanceTimeBy(26_000)

        assertTrue("connected idle polls must not emit, saw ${emissions.size}", emissions.isEmpty())
        // The ID is already read, so the loop must not keep hammering gRPC either.
        assertEquals(1, starlink.calls)
    }

    @Test
    fun onScreenStopped_stopsPollingAndOnScreenStarted_resumesIt() = captureTest(reachable = true) { vm ->
        val whileVisible = reachability.calls
        assertTrue(whileVisible > 0)

        vm.onScreenStopped()
        advanceTimeBy(26_000)

        // Backgrounded: the camera is unbound, so the loop must not keep opening sockets either.
        assertEquals("polling must stop when the screen does", whileVisible, reachability.calls)

        vm.onScreenStarted()
        runCurrent()
        assertTrue("polling must resume with the screen", reachability.calls > whileVisible)
    }

    @Test
    fun onScreenStarted_doesNotStackASecondLoop() = captureTest(reachable = false) { vm ->
        // The lifecycle can deliver ON_START without an intervening ON_STOP (configuration change,
        // returning to the tab). A second loop would double the poll rate for the whole session.
        vm.onScreenStarted()
        vm.onScreenStarted()
        runCurrent()
        val after = reachability.calls

        advanceTimeBy(2_600)

        assertEquals("one poll per interval, not two", after + 1, reachability.calls)
    }

    @Test
    fun onScreenStarted_doesNotResumePollingOnTheSummary() = captureTest(reachable = true, counter = 7) { vm ->
        vm.captureAndConfirm("KIT-1")
        vm.captureAndConfirm("DISH-1")
        vm.onReview()
        runCurrent()
        val onSummary = reachability.calls

        vm.onScreenStarted()
        advanceTimeBy(26_000)

        // The summary is a read-only recap; re-reading the dish there could only contradict it.
        assertEquals(onSummary, reachability.calls)
        assertTrue(vm.state.value is CaptureUiState.Summary)
    }

    // --- Review and save ---

    @Test
    fun review_isRefusedUntilAllThreeSignalizersAreReady() = captureTest(reachable = false) { vm ->
        vm.captureAndConfirm("KIT-1")
        vm.captureAndConfirm("DISH-1")

        assertFalse("no dish ID yet", vm.capturing.canSave)
        vm.onReview()
        runCurrent()

        assertTrue(vm.state.value is CaptureUiState.Capturing)
    }

    @Test
    fun review_buildsTheRecordAndPreviewsTheCounterWithoutClaimingIt() = captureTest(reachable = true, counter = 42) { vm ->
        vm.captureAndConfirm("KIT-1")
        vm.captureAndConfirm("DISH-1")

        vm.onReview()
        runCurrent()

        val summary = vm.state.value as CaptureUiState.Summary
        assertEquals(42L, summary.record.counter)
        assertEquals("01000000-00000000-00001234", summary.record.dishId)
        assertEquals("KIT-1", summary.record.kitNumber)
        assertEquals("DISH-1", summary.record.dishSerial)
        assertEquals(UploadStatus.PENDING, summary.record.status)
        assertFalse(summary.duplicate)
        // Backing out of the summary must not burn a number.
        assertEquals(42L, settings.nextCounter.value)
    }

    @Test
    fun discard_returnsToCaptureWithoutConsumingTheCounter() = captureTest(reachable = true, counter = 42) { vm ->
        vm.captureAndConfirm("KIT-1")
        vm.captureAndConfirm("DISH-1")
        vm.onReview()
        runCurrent()

        vm.onDiscard()
        runCurrent()

        assertTrue(vm.state.value is CaptureUiState.Capturing)
        assertEquals(42L, settings.nextCounter.value)
        assertTrue(dao.records.value.isEmpty())
        // A fresh capture, not the abandoned one.
        assertNull(vm.capturing.checklist.kitNumber)
    }

    @Test
    fun review_flagsARepeatOfTheSameDishAndKitPair() = captureTest(
        reachable = true,
        records = listOf(
            ScanRecord(
                id = 1,
                counter = 1,
                timestamp = 1_700_000_000_000,
                dishId = "01000000-00000000-00001234",
                kitNumber = "KIT-1",
                dishSerial = "DISH-1",
            ),
        ),
    ) { vm ->
        vm.captureAndConfirm("KIT-1")
        vm.captureAndConfirm("DISH-2")

        vm.onReview()
        runCurrent()

        assertTrue((vm.state.value as CaptureUiState.Summary).duplicate)
    }

    @Test
    fun save_claimsTheCounterPersistsTheRecordAndResetsForTheNextKit() = captureTest(reachable = true, counter = 42) { vm ->
        vm.captureAndConfirm("KIT-1")
        vm.captureAndConfirm("DISH-1")
        vm.onReview()
        runCurrent()

        var saved = false
        vm.onSave { saved = true }
        runCurrent()

        assertTrue(saved)
        val stored = dao.records.value.single()
        assertEquals(42L, stored.counter)
        assertEquals("KIT-1", stored.kitNumber)
        assertEquals(UploadStatus.PENDING, stored.status)
        // Only a stored record consumes a number.
        assertEquals(43L, settings.nextCounter.value)

        // The deferred upload is kicked as part of saving, not left to the next app start.
        assertEquals(1, uploadsEnqueued)

        assertTrue(vm.state.value is CaptureUiState.Capturing)
        assertNull(vm.capturing.checklist.kitNumber)
        assertNull(vm.capturing.checklist.dishSerial)
    }
}
