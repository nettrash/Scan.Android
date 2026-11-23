package me.nettrash.scan.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import me.nettrash.scan.data.db.ScanRecord
import me.nettrash.scan.data.db.ScanRecordDao
import me.nettrash.scan.data.payload.QrRiskAssessor
import me.nettrash.scan.data.payload.QrRiskReport
import me.nettrash.scan.data.payload.ScanPayloadParser
import me.nettrash.scan.data.settings.SettingsRepository
import me.nettrash.scan.data.settings.SettingsState
import me.nettrash.scan.data.settings.ScanModePreference
import me.nettrash.scan.scanner.BulkScanSession
import me.nettrash.scan.scanner.RecognizedTextBlock
import me.nettrash.scan.scanner.ScannedCode
import me.nettrash.scan.scanner.Symbology
import me.nettrash.scan.scanner.TextFrame
import me.nettrash.scan.widget.ScanEntryDispatcher
import javax.inject.Inject

/**
 * Backs ScannerScreen. Owns the latest scan, debounces duplicate decodes
 * (so the camera analyzer can fire at full frame-rate without spamming the
 * UI), persists saved scans to Room, and exposes the user's feedback /
 * continuous-scan preferences.
 */
@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val dao: ScanRecordDao,
    private val settingsRepo: SettingsRepository,
    quickEntry: ScanEntryDispatcher,
) : ViewModel() {

    data class State(
        val lastScan: ScannedCode? = null,
        val importError: String? = null,
        val isDecodingImage: Boolean = false,
        /// Most recent code auto-saved while continuous-scan mode is
        /// on. Drives the on-screen banner so the user has feedback
        /// without a sheet interruption. `null` clears the banner.
        val lastContinuous: ScannedCode? = null,
        /// When the camera reports more than one code in a single
        /// frame, we don't auto-pick — we render numbered chips and
        /// let the user choose. The chooser stays up until the user
        /// taps a chip, taps to dismiss, or the frame goes back to
        /// having ≤ 1 code.
        val multiCodeChoices: List<ScannedCode> = emptyList(),
        /// How many distinct codes the camera saw in the latest frame.
        /// More than one is the classic sticker-over-sticker tamper.
        /// Mirrors iOS `lastFrameCodeCount`; live, so it keeps moving
        /// while a sheet is up — see [sheetCodesInFrame].
        val codesInFrame: Int = 1,
        /// [codesInFrame] as it was when [lastScan] was handled — the
        /// count the risk assessor grades the open sheet with. The
        /// camera keeps running under the sheet, so the live count
        /// would otherwise rewrite the verdict on a code the user is
        /// already reading. NFC reads and imports count as one code.
        /// Mirrors iOS `frameCounts[scan.id]`.
        val sheetCodesInFrame: Int = 1,
        /// The same snapshot for [lastContinuous], carried into the
        /// sheet when the continuous-scan banner is tapped.
        val continuousCodesInFrame: Int = 1,
        /// Risk verdict for a multi-code frame, or null while every
        /// frame has held a single code. Drives the caution chip on
        /// the bulk-capture HUD.
        val frameRisk: QrRiskReport? = null,
        /// Text blocks the camera recognises in Live Text mode, refreshed
        /// every analysed frame and offered as tap targets — the
        /// Android stand-in for VisionKit's tappable highlights.
        val recognizedBlocks: List<RecognizedTextBlock> = emptyList(),
        /// The block the user tapped, awaiting copy / share / translate.
        /// Sticky — frames keep arriving underneath, and only the user's
        /// dismiss clears it. Mirrors iOS `recognizedText`.
        val selectedText: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /// Bulk mode's accumulator. Lives here (rather than in the
    /// Composable) so the collected list survives the result sheet,
    /// mode and tab switches, the background and recompositions for
    /// the lifetime of the screen — the role iOS gives its
    /// `@StateObject var bulk`. Memory only. Written only from the
    /// main-thread analyzer callback.
    val bulk = BulkScanSession()

    /// "Scan a code" launches ([ScanEntryDispatcher.scannerRequests]).
    /// The screen closes the bulk sheet on each; this ViewModel clears
    /// the rest of what covers the camera ([revealCamera]).
    val scannerRequests: StateFlow<Int> = quickEntry.scannerRequests

    /// Surfaced to the Composable so ScannerScreen can flip-flop the
    /// haptic / sound feedback and route to either the result sheet
    /// or the auto-save path without re-reading DataStore on every
    /// frame.
    val settings: StateFlow<SettingsState> =
        settingsRepo.state.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    /// The clock every dedupe window here is measured on. Swappable so a
    /// test can step time instead of sleeping through it.
    internal var now: () -> Long = System::currentTimeMillis

    private val dedupeWindowMs = 1500L
    private var lastValue: String? = null
    private var lastValueAtMs: Long = 0L
    /// Value we last took action on (saved to history in
    /// continuous-scan mode, or surfaced via lastScan in normal
    /// mode). Cleared by [onBatch] when the camera frame is empty
    /// — i.e. when the user has visibly moved the camera off the
    /// code. This is what stops the same code being saved over and
    /// over while it sits in view; the older 1.5 s time window kept
    /// resetting and re-firing every dedupeWindow seconds.
    private var lastHandledValue: String? = null

    /// Live Text opens a code once per stay in the frame, like iOS's
    /// `VisionTextScanner.openedItemIDs`: dismissing its sheet hands the
    /// text back instead of re-opening the code a frame later. Value →
    /// when a frame last showed it, for every value opened (or already
    /// on screen) during its current stay. A value unseen for longer than
    /// [TEXT_STAY_GRACE_MS] has left the frame and is forgotten, so it
    /// opens again next time. Codes and Bulk never read it.
    private val textStayLastSeen = HashMap<String, Long>()

    /// Whether the chooser on screen was raised by a Live Text frame, so
    /// a pick from it counts as that code's opening for the stay.
    private var chooserFromLiveText = false

    init {
        // `drop(1)`: the count as it stood when this screen was created
        // is not a request to it — a fresh screen has nothing on top.
        viewModelScope.launch {
            quickEntry.scannerRequests.drop(1).collect { revealCamera() }
        }
    }

    /// Multi-code analyzer hook. The MlKitAnalyzer in ScannerScreen
    /// passes *every* recognised code in the current frame here.
    /// Single-code path: route straight to [onScan]. Multi-code path:
    /// stash the list and let the UI render a chooser.
    fun onBatch(codes: List<ScannedCode>) = routeFrame(codes, liveText = false)

    /// [onBatch] for Codes / Bulk frames and [onTextFrame]'s codes alike;
    /// [liveText] adds Live Text's once-per-stay rule on the single-code
    /// path (see [textStayLastSeen]).
    private fun routeFrame(codes: List<ScannedCode>, liveText: Boolean) {
        if (codes.isEmpty()) {
            // Frame is empty — clear the multi-code chooser if it
            // was up so the overlay doesn't linger after the user
            // moves the camera off.
            //
            // NOTE: deliberately does *not* clear `lastHandledValue`.
            // Same-value dedupe is now strict — once a value has
            // been handled, the only way to re-handle it is to
            // dismiss the result sheet (sheet mode) or the
            // continuous-scan banner (continuous mode). Pointing the
            // camera elsewhere and back is no longer enough; the
            // user has to explicitly say "I'm done with this scan"
            // first. This is what the user asked for: literal
            // same-value avoidance.
            if (_state.value.multiCodeChoices.isNotEmpty()) {
                _state.update { it.copy(multiCodeChoices = emptyList()) }
            }
            return
        }

        // Distinct codes in this frame. Recorded before anything else
        // so the tamper finding ("more than one code in this frame")
        // sees the real count rather than the 1 it used to assume.
        val frameCount = maxOf(codes.size, 1)
        if (frameCount != _state.value.codesInFrame) {
            val report = if (frameCount > 1) assessFrame(codes.first(), frameCount) else null
            _state.update { it.copy(codesInFrame = frameCount, frameRisk = report) }
        }

        if (settings.value.scanMode == ScanModePreference.BULK) {
            // Bulk capture collects every distinct code in the frame
            // instead of stopping at the first; no chooser, no sheet.
            for (code in codes) bulk.ingest(code)
            if (_state.value.multiCodeChoices.isNotEmpty()) {
                _state.update { it.copy(multiCodeChoices = emptyList()) }
            }
            return
        }

        if (codes.size == 1) {
            // Drop the chooser if it was up and route normally.
            if (_state.value.multiCodeChoices.isNotEmpty()) {
                _state.update { it.copy(multiCodeChoices = emptyList()) }
            }
            val code = codes.first()
            // Live Text: already opened during this stay — the frame's
            // count and risk are updated above, and that is all.
            if (liveText && code.value in textStayLastSeen) return
            onScan(code, dedupe = true, codesInFrame = frameCount)
            // Opened now, or its sheet / banner is the one on screen: either
            // way the user has it, so its stay starts (sheet or continuous
            // banner alike).
            if (liveText && code.value == lastHandledValue) textStayLastSeen[code.value] = now()
            return
        }
        // ≥ 2 codes: hold for the user to pick. We deliberately do
        // *not* fire haptic / sound here; that happens once the user
        // commits to one of the choices.
        chooserFromLiveText = liveText
        _state.update { it.copy(multiCodeChoices = codes) }
    }

    /// User tapped a chip in the multi-code chooser. Bypass the
    /// dedupe window — the user explicitly picked this — and clear
    /// both dedupe states so the chosen code goes through. In Live
    /// Text that includes a code already opened this stay, and the
    /// pick counts as its opening.
    fun pickFromChoices(code: ScannedCode) {
        lastValue = null
        lastValueAtMs = 0L
        lastHandledValue = null
        // The chooser only exists because the frame held several codes,
        // and that is exactly what the tamper finding must hear about.
        val frameCount = maxOf(_state.value.multiCodeChoices.size, _state.value.codesInFrame)
        _state.update { it.copy(multiCodeChoices = emptyList()) }
        onScan(code, dedupe = false, codesInFrame = frameCount)
        if (chooserFromLiveText) textStayLastSeen[code.value] = now()
    }

    /**
     * A tag the user deliberately tapped against the phone. Lands in the
     * same result sheet as a camera scan — smart actions, the safety
     * check, save-to-history — exactly like iOS, which sets its
     * `sheetCode` directly: an explicit gesture skips continuous-scan
     * auto-save, and a tag is one code, never a tampered frame.
     */
    fun onNfcRead(raw: String) {
        if (raw.isBlank()) return
        val t = now()
        lastValue = raw
        lastValueAtMs = t
        lastHandledValue = raw
        _state.update {
            it.copy(
                lastScan = ScannedCode(value = raw, symbology = Symbology.UNKNOWN, timestampMillis = t),
                sheetCodesInFrame = 1,
                importError = null,
                isDecodingImage = false,
            )
        }
    }

    fun dismissMultiCodeChooser() {
        _state.update { it.copy(multiCodeChoices = emptyList()) }
    }

    /**
     * The Scan tab's mode switch. Persists the choice (so the app
     * reopens in the mode the user last worked in) and resumes or
     * pauses the bulk capture straight away rather than after the
     * DataStore round trip — pausing keeps the list, as iOS's
     * `syncBulk()` does. ScannerScreen's `LifecycleStartEffect(mode)`
     * keeps it in step from then on.
     */
    fun applyScanMode(mode: ScanModePreference) {
        viewModelScope.launch { settingsRepo.setScanMode(mode) }
        when (mode) {
            ScanModePreference.BULK -> bulk.start()
            else -> bulk.stop()
        }
        // A new mode is a new session: Live Text starts over, as iOS
        // rebuilds its scanner coordinator.
        textStayLastSeen.clear()
        _state.update {
            it.copy(
                multiCodeChoices = emptyList(),
                recognizedBlocks = emptyList(),
                selectedText = null,
                codesInFrame = 1,
                frameRisk = null,
            )
        }
    }

    /// Live Text frames land here: the recognised blocks refresh the
    /// tap targets, and any codes in the same frame go through the
    /// normal code path — a menu's loyalty QR is a real code, iOS opens
    /// it from Live Text too, and two of them in view raise the chooser
    /// rather than being opened on a guess. A selected block is left
    /// alone: the card belongs to the text the user tapped, not to
    /// whatever the next frame happens to read.
    ///
    /// Unlike Codes mode, a code opens once per stay in the frame (iOS
    /// `VisionTextScanner.openedItemIDs`): after its sheet is dismissed,
    /// the same code still in view leaves the text to the user — the
    /// café-menu-with-a-loyalty-QR case this mode exists for. It opens
    /// again only once it has been out of view for more than
    /// [TEXT_STAY_GRACE_MS], which also absorbs the odd frame where the
    /// recogniser misses a code that never moved.
    fun onTextFrame(frame: TextFrame) {
        if (frame.blocks != _state.value.recognizedBlocks) {
            _state.update { it.copy(recognizedBlocks = frame.blocks) }
        }
        val t = now()
        for (code in frame.codes) {
            if (code.value in textStayLastSeen) textStayLastSeen[code.value] = t
        }
        textStayLastSeen.values.removeAll { t - it > TEXT_STAY_GRACE_MS }
        routeFrame(frame.codes, liveText = true)
    }

    /// The camera stopped — the screen left composition or went to the
    /// background. The next Live Text session starts with no code
    /// "already opened", like iOS's coordinator rebuilt with the view.
    fun onCameraStopped() {
        textStayLastSeen.clear()
    }

    /// Tap target for a Live Text block — the block the user tapped is
    /// the one the card shows, until the user dismisses it (iOS
    /// `recognizedText = block.text`).
    fun selectRecognizedText(block: RecognizedTextBlock) {
        _state.update { it.copy(selectedText = block.text) }
    }

    fun dismissRecognizedText() {
        _state.update { it.copy(selectedText = null) }
    }

    /**
     * "Scan a code" from the Quick Settings tile, the widget or a launcher
     * shortcut — iOS's Start Scanning — means a camera the user can see:
     * close the result sheet (releasing the same-value dedupe, as its own
     * dismissal does), the Live Text card and the multi-code chooser. The
     * bulk list and History stay as they are. Mirrors iOS
     * `ScannerScreen.revealCamera()`.
     *
     * That holds because every one of those launches reaches the running
     * `MainActivity` through `onNewIntent` (`ScanIntents.LAUNCH_FLAGS`) —
     * the static shortcuts by way of `ShortcutTrampolineActivity`, since
     * the system starts a static shortcut with `CLEAR_TASK`, which would
     * otherwise finish this screen's activity and the bulk list with it.
     */
    fun revealCamera() {
        if (_state.value.lastScan != null) lastHandledValue = null
        _state.update {
            it.copy(lastScan = null, selectedText = null, multiCodeChoices = emptyList())
        }
    }

    /// Bulk sheet's "Save all to History". Mirrors iOS
    /// `BulkCaptureSheet.saveAll`, including the "Bulk capture" note.
    /// Saves only the entries not saved yet — they are marked before
    /// the inserts start, so a repeat tap can't write them twice — and
    /// reports how many that was ("N saved to History").
    ///
    /// If an insert fails, the entries it never wrote go back to unsaved
    /// (a retry writes each of them once) and [onFailed] gets the reason;
    /// whatever was already written is still reported through [onSaved].
    /// iOS takes its whole save back instead — either way nothing is
    /// written twice and nothing is silently dropped, and the failure no
    /// longer escapes viewModelScope as an uncaught exception.
    fun saveBulkToHistory(onFailed: (String) -> Unit = {}, onSaved: (Int) -> Unit) {
        val entries = bulk.takeUnsaved()
        if (entries.isEmpty()) return
        viewModelScope.launch {
            var written = 0
            try {
                for (entry in entries) {
                    dao.insert(
                        ScanRecord(
                            value = entry.value,
                            symbology = entry.symbology,
                            timestamp = entry.firstSeenMillis,
                            notes = "Bulk capture",
                            isFavorite = false,
                        )
                    )
                    written += 1
                }
                onSaved(written)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bulk.markUnsaved(entries.drop(written).mapTo(HashSet()) { it.id })
                if (written > 0) onSaved(written)
                onFailed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /// The "two codes in one frame is a sticker" check, run against
    /// whatever the frame actually contained.
    private fun assessFrame(probe: ScannedCode, codesInFrame: Int): QrRiskReport? =
        runCatching {
            QrRiskAssessor.assess(
                raw = probe.value,
                payload = runCatching {
                    ScanPayloadParser.parse(probe.value, probe.symbology)
                }.getOrNull(),
                codesInFrame = codesInFrame,
            )
        }.getOrNull()

    /**
     * Route one recognised code to the sheet (or, in continuous mode, the
     * auto-save banner). [codesInFrame] is how many distinct codes the
     * frame that produced it held — 1 for imports, NFC and deep links.
     */
    fun onScan(code: ScannedCode, dedupe: Boolean, codesInFrame: Int = 1) {
        if (dedupe) {
            // Primary dedupe: don't re-handle the *same value* until
            // the camera has visibly moved off the code (onBatch
            // clears `lastHandledValue` on an empty frame). Stops a
            // single code held in view from being saved every 1.5 s
            // in continuous mode or re-popping the sheet each time
            // the time window expired.
            if (code.value == lastHandledValue) return
            // Secondary safety net: a transient flip "code A → code
            // B → code A" (oscillating recogniser) would otherwise
            // keep re-saving once per pair of frames. Keep the
            // original 1.5 s time-window dedupe as a guard for
            // that pathology.
            val t = now()
            if (code.value == lastValue && t - lastValueAtMs < dedupeWindowMs) return
            lastValue = code.value
            lastValueAtMs = t
        } else {
            lastValue = code.value
            lastValueAtMs = now()
        }
        lastHandledValue = code.value
        if (settings.value.continuousScan) {
            // Auto-save and surface a banner instead of presenting
            // the result sheet. ScanRecord is constructed inline
            // so the persistence path doesn't depend on `lastScan`
            // — the user might dismiss the banner before Room's
            // insert finishes.
            viewModelScope.launch {
                dao.insert(
                    ScanRecord(
                        value = code.value,
                        symbology = code.symbology.displayName,
                        timestamp = code.timestampMillis,
                        notes = null,
                        isFavorite = false,
                    )
                )
            }
            _state.update {
                it.copy(
                    lastScan = null,
                    lastContinuous = code,
                    continuousCodesInFrame = codesInFrame,
                    importError = null,
                    isDecodingImage = false,
                )
            }
        } else {
            _state.update {
                it.copy(
                    lastScan = code,
                    sheetCodesInFrame = codesInFrame,
                    importError = null,
                    isDecodingImage = false,
                )
            }
        }
    }

    /// Tap target for the continuous-scan banner — open the result
    /// sheet for the most recently auto-saved code without re-running
    /// the recogniser.
    fun openLastContinuous() {
        val last = _state.value.lastContinuous ?: return
        _state.update { it.copy(lastScan = last, sheetCodesInFrame = it.continuousCodesInFrame) }
    }

    fun decodingImage() {
        _state.update { it.copy(isDecodingImage = true, importError = null) }
    }

    fun onImportError(message: String) {
        _state.update { it.copy(isDecodingImage = false, importError = message) }
    }

    fun dismissResult() {
        // Releasing the dedupe lock alongside the sheet dismissal
        // is the user-facing equivalent of "I'm done with this
        // scan" — the next sight of any code, including the one
        // just shown, registers as a fresh scan.
        lastHandledValue = null
        _state.update { it.copy(lastScan = null) }
    }

    /// Continuous-scan banner dismiss — same release-the-lock
    /// semantics as [dismissResult] but for the in-camera banner
    /// rather than the result sheet.
    fun dismissContinuousBanner() {
        lastHandledValue = null
        _state.update { it.copy(lastContinuous = null) }
    }

    fun saveScan(notes: String?) {
        val code = _state.value.lastScan ?: return
        viewModelScope.launch {
            dao.insert(
                ScanRecord(
                    value = code.value,
                    symbology = code.symbology.displayName,
                    timestamp = code.timestampMillis,
                    notes = notes
                )
            )
        }
    }

    /**
     * Persist a scan that arrived via App-Links rather than the camera.
     * Direct DAO insert — bypasses the dedupe / continuous-scan / banner
     * machinery in [onScan] entirely so a deep-link save doesn't
     * accidentally show up in the continuous-scan banner or compete
     * with a live capture for the result sheet slot.
     */
    fun saveDeepLinkScan(code: ScannedCode, notes: String? = null) {
        viewModelScope.launch {
            dao.insert(
                ScanRecord(
                    value = code.value,
                    symbology = code.symbology.displayName,
                    timestamp = code.timestampMillis,
                    notes = notes,
                )
            )
        }
    }

    /**
     * Save the current scan as a favourited loyalty-card row. Tagged
     * via `notes = "Loyalty: <merchant>"` so the History search field
     * can find it by merchant name; favourited so it pins to the top
     * of the list. Mirrors iOS `saveAsLoyaltyCard` in
     * `PayloadActionsView.swift`.
     */
    fun saveAsLoyaltyCard(merchant: String) {
        val code = _state.value.lastScan ?: return
        val trimmed = merchant.trim()
        viewModelScope.launch {
            dao.insert(
                ScanRecord(
                    value = code.value,
                    // Override the symbology label when the user
                    // explicitly tags it as a loyalty card — this
                    // makes the History row read "Loyalty" instead
                    // of "EAN-13" and keeps the tagging legible.
                    symbology = "Loyalty",
                    timestamp = code.timestampMillis,
                    notes = if (trimmed.isEmpty()) "Loyalty" else "Loyalty: $trimmed",
                    isFavorite = true,
                )
            )
        }
    }

    companion object {
        /// How long a Live Text code may go unseen and still be in the
        /// same stay: text and barcode recognition both drop a code that
        /// never moved for a frame or two.
        internal const val TEXT_STAY_GRACE_MS = 1000L
    }
}
