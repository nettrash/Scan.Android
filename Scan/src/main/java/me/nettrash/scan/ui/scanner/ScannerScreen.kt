package me.nettrash.scan.ui.scanner

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.nettrash.scan.data.payload.ScanPayloadParser
import me.nettrash.scan.data.payload.TranslateLanguage
import me.nettrash.scan.data.payload.TranslationCatalog
import me.nettrash.scan.data.settings.ScanModePreference
import me.nettrash.scan.nfc.NfcReadAction
import me.nettrash.scan.nfc.NfcReadStatusBanner
import me.nettrash.scan.nfc.rememberNfcTagWriter
import me.nettrash.scan.scanner.BulkScanSession
import me.nettrash.scan.scanner.ImageDecoder
import me.nettrash.scan.scanner.RecognizedTextBlock
import me.nettrash.scan.scanner.ScannedCode
import me.nettrash.scan.scanner.Symbology
import me.nettrash.scan.scanner.TextScanner
import me.nettrash.scan.ui.components.PayloadActions
import me.nettrash.scan.ui.settings.ScanSound
import me.nettrash.scan.util.Haptics

@OptIn(
    ExperimentalPermissionsApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)
@Composable
fun ScannerScreen(viewModel: ScannerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(cameraPermission.status.isGranted) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }

    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var torchOn by remember { mutableStateOf(false) }
    var lastFeedbackValue by remember { mutableStateOf<String?>(null) }
    // Latest known PreviewView size — drives the ROI rect we
    // intersect against analyzer results, and feeds the pinch-
    // gesture's clamping logic.
    var previewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    // The detected code's bounding rect, in PreviewView coordinates.
    // `null` while we haven't seen a code yet — the reticle then falls back
    // to a centred default-size square. Held as Compose state so the
    // ReticleOverlay can animate to it whenever it changes.
    var detectedRect by remember { mutableStateOf<Rect?>(null) }

    val scope = rememberCoroutineScope()

    // ---- Scan mode (Codes / Live Text / Bulk) -----------------------------
    //
    // Persisted through `SettingsRepository.setScanMode`, so the app reopens
    // in the mode the user last worked in — the iOS `@AppStorage(scanMode)`
    // behaviour. Live Text is dropped from the picker when the recogniser is
    // unavailable, exactly as iOS filters on `ScanMode.isAvailable`.
    val availableModes = remember {
        ScanModePreference.entries.filter {
            it != ScanModePreference.TEXT || TextScanner.isSupported
        }
    }
    val storedMode = settings.scanMode
    val mode = if (storedMode in availableModes) storedMode else ScanModePreference.CODES
    var showBulkSheet by remember { mutableStateOf(false) }
    val bulk = viewModel.bulk

    // Bulk collects only while it is the mode on a started screen — iOS
    // `syncBulk()`. Leaving it — for Codes or Live Text, another tab (this
    // screen leaves composition) or the background (ON_STOP) — pauses the
    // capture and keeps the list; coming back resumes the same list with
    // the HUD counting it. Only Clear in the bulk sheet empties it. A
    // stopped camera also ends Live Text's "already opened" codes, so the
    // next session starts fresh.
    LifecycleStartEffect(mode) {
        if (mode == ScanModePreference.BULK) bulk.start() else bulk.stop()
        onStopOrDispose {
            bulk.stop()
            viewModel.onCameraStopped()
        }
    }

    // Bulk mode fires its feedback off the *new distinct code* count rather
    // than the result sheet, which it never shows. Mirrors iOS's
    // `if bulk.ingest(code) { emitScanFeedback() }`. Seeded with the kept
    // list, so coming back to a non-empty capture doesn't buzz.
    var lastBulkCount by remember { mutableIntStateOf(bulk.count) }
    LaunchedEffect(bulk.count) {
        if (bulk.count > lastBulkCount) {
            if (settings.hapticOnScan) Haptics.success(context)
            if (settings.soundOnScan) ScanSound.playScanned()
        }
        lastBulkCount = bulk.count
    }

    // ---- ML Kit + CameraX wiring -----------------------------------------

    // Bundled barcode scanner — doesn't need the Play Services on-demand
    // module. Closing it on dispose releases the native models.
    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_AZTEC,
                    Barcode.FORMAT_PDF417,
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_ITF,
                    Barcode.FORMAT_CODABAR,
                )
                .build()
        )
    }
    DisposableEffect(barcodeScanner) {
        onDispose { barcodeScanner.close() }
    }

    // LifecycleCameraController + MlKitAnalyzer with view-referenced
    // coordinates. CameraX maps the analyzer's `boundingBox` from image-
    // sensor space to PreviewView-space for us, which is what makes the
    // reticle's "snap to the code" behaviour straightforward.
    val cameraController = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        }
    }

    // Live Text pass: ML Kit's on-device text recogniser over the same
    // CameraX analysis stream, with the barcode scanner kept alive beside it
    // — a menu photographed at a café usually carries a loyalty QR next to
    // the text, which is why iOS also configures VisionKit's barcode
    // recogniser in `.text` mode.
    //
    // A frame still being read when the user switches away from Live Text
    // finishes after the Codes analyzer has taken over; its blocks and codes
    // belong to the mode just left, so they are dropped rather than opening
    // a sheet in Codes or Bulk.
    val currentMode by rememberUpdatedState(mode)
    val textAnalyzer = remember(barcodeScanner) {
        ImageAnalysis.Analyzer { proxy ->
            scope.launch {
                try {
                    val frame = TextScanner.recognize(proxy, barcodeScanner)
                    if (currentMode == ScanModePreference.TEXT) viewModel.onTextFrame(frame)
                } catch (_: Exception) {
                    // A frame ML Kit refuses (blur, unsupported format) is
                    // routine — the preview just keeps running.
                } finally {
                    proxy.close()
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { TextScanner.close() }
    }

    LaunchedEffect(cameraPermission.status.isGranted, barcodeScanner, mode) {
        if (!cameraPermission.status.isGranted) return@LaunchedEffect
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analyzer = if (mode == ScanModePreference.TEXT && TextScanner.isSupported) {
            textAnalyzer
        } else MlKitAnalyzer(
            listOf(barcodeScanner),
            ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
            mainExecutor,
        ) { result ->
            val barcodes = result.getValue(barcodeScanner) ?: return@MlKitAnalyzer
            // Collect *all* readable codes in the frame — the
            // ViewModel decides what to do with multiplicity. Dedupe
            // by `rawValue` so the same code recognised twice in a
            // single frame doesn't show as two chooser chips.
            //
            // ROI filter: drop codes whose bounding-box centre falls
            // outside the centred 78% × 78% rect of the preview. ML
            // Kit doesn't expose a server-side ROI knob, so we do
            // this in-process — saves the host-app from having to
            // disambiguate a stray code at the edge of the frame
            // and keeps the host UX in lockstep with the iOS
            // `AVCaptureMetadataOutput.rectOfInterest` semantics.
            // When previewSize is zero (first composition before
            // layout) we keep every code, which means worst-case
            // we use the full-frame behaviour for the first ~30 ms.
            val roi = roiRectFor(previewSize)
            val seen = HashSet<String>()
            val ts = System.currentTimeMillis()
            val codes = barcodes.mapNotNull { b ->
                val v = b.rawValue
                if (v.isNullOrEmpty() || !seen.add(v)) return@mapNotNull null
                val box = b.boundingBox
                val rect = if (box != null) {
                    Rect(box.left.toFloat(), box.top.toFloat(),
                         box.right.toFloat(), box.bottom.toFloat())
                } else null
                if (roi != null && rect != null) {
                    val cx = (rect.left + rect.right) / 2f
                    val cy = (rect.top + rect.bottom) / 2f
                    if (cx !in roi.left..roi.right ||
                        cy !in roi.top..roi.bottom) {
                        return@mapNotNull null
                    }
                }
                ScannedCode(
                    value = v,
                    symbology = Symbology.fromMlKit(b.format),
                    timestampMillis = ts,
                    previewRect = rect,
                )
            }
            // Track the largest detected code in the reticle so it
            // still moves with the user's framing even when there are
            // multiple codes on screen.
            codes.maxByOrNull { (it.previewRect?.width ?: 0f) * (it.previewRect?.height ?: 0f) }
                ?.previewRect?.let { detectedRect = it }
            viewModel.onBatch(codes)
        }
        cameraController.setImageAnalysisAnalyzer(mainExecutor, analyzer)
        cameraController.bindToLifecycle(lifecycleOwner)
    }

    LaunchedEffect(torchOn) {
        // enableTorch queues until the camera is ready, so this is safe to
        // call before bindToLifecycle has finished.
        runCatching { cameraController.enableTorch(torchOn) }
    }

    // Fire haptic + sound feedback whenever a *new* scan is presented
    // — either via the result sheet (sheet-mode) or via the auto-save
    // banner (continuous-mode). We track `lastFeedbackValue` so a
    // sheet dismiss + re-display of the same payload doesn't double-buzz.
    LaunchedEffect(state.lastScan, state.lastContinuous) {
        val v = state.lastScan?.value ?: state.lastContinuous?.value
        if (v != null && v != lastFeedbackValue) {
            lastFeedbackValue = v
            if (settings.hapticOnScan) Haptics.success(context)
            if (settings.soundOnScan) ScanSound.playScanned()
        }
    }

    // ---- Photo Picker import ---------------------------------------------

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.decodingImage()
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { ImageDecoder.decode(context, uri) }
            }.fold(
                onSuccess = { codes ->
                    codes.firstOrNull()?.let { viewModel.onScan(it, dedupe = false) }
                },
                onFailure = { e ->
                    viewModel.onImportError(e.message ?: "Couldn't read that image.")
                }
            )
        }
    }

    // ---- Files import (images + PDFs) -------------------------------------
    //
    // Mirrors the iOS `.fileImporter(allowedContentTypes: [.image, .pdf])`
    // path added in v1.8.59. The system Photo Picker (above) is image-only
    // by spec, so PDFs are reached via `OpenDocument` instead — which also
    // gives the user access to third-party providers (Drive, OneDrive,
    // Dropbox, etc.) the way iOS's Files picker does.
    //
    // The decoded pick routes through `ImageDecoder.decodeBatch`, which
    // already does MIME-based dispatch between `decode` (image) and
    // `decodePdf` (PDF) — the same code path the share-intake flow uses
    // for `ACTION_SEND` / `ACTION_SEND_MULTIPLE` arrivals, so behaviour
    // stays in lockstep with what shows up when the user shares a PDF
    // into Scan from another app.
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.decodingImage()
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    ImageDecoder.decodeBatch(context, listOf(uri))
                }
            }.fold(
                onSuccess = { codes ->
                    codes.firstOrNull()?.let { viewModel.onScan(it, dedupe = false) }
                },
                onFailure = { e ->
                    viewModel.onImportError(e.message ?: "Couldn't read that file.")
                }
            )
        }
    }

    // NFC tag reading — the iOS read button beside the import menu. The
    // writer object owns the reader-mode session; `rememberNfcTagWriter`
    // switches reader mode off when this screen pauses or leaves
    // composition, so a tag is only ever read while the user is here
    // and has asked for it.
    val nfcReader = rememberNfcTagWriter()

    // Import-menu expansion state. Anchors the DropdownMenu to the
    // import IconButton; mirrors the iOS `Menu { … }` import affordance
    // in `ScannerScreen.swift`.
    var importMenuExpanded by remember { mutableStateOf(false) }

    // "Scan a code" from the tile, the widget or a shortcut lands on a camera
    // the user can see: the ViewModel closes the result sheet and the Live
    // Text card (`revealCamera`), and this closes the bulk sheet and the
    // import menu. The first run, at composition, finds nothing open.
    val scannerRequests by viewModel.scannerRequests.collectAsState()
    LaunchedEffect(scannerRequests) {
        showBulkSheet = false
        importMenuExpanded = false
    }

    // What the overlay layer asks for — the ViewModel's handlers plus this
    // screen's bulk sheet and share helpers — built once for the screen.
    val overlayCallbacks = remember(viewModel, context) {
        ScannerOverlayCallbacks(
            onSelectMode = viewModel::applyScanMode,
            onOpenBulkList = { showBulkSheet = true },
            onPickCode = viewModel::pickFromChoices,
            onDismissChooser = viewModel::dismissMultiCodeChooser,
            onOpenContinuous = viewModel::openLastContinuous,
            onDismissContinuous = viewModel::dismissContinuousBanner,
            onSelectText = viewModel::selectRecognizedText,
            onDismissText = viewModel::dismissRecognizedText,
            onCopyText = { copyToClipboard(context, it) },
            onShareText = { sharePlainText(context, it) },
        )
    }

    // ---- UI --------------------------------------------------------------

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraPermission.status.isGranted) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { previewSize = it }
                    .pointerInput(cameraController) {
                        // Pinch-to-zoom: every gesture frame multiplies
                        // the camera's *current* zoom ratio by the
                        // gesture's delta and re-applies, clamped to
                        // the device's reported [min, max] range
                        // (read off `cameraInfo.zoomState` so multi-
                        // lens phones with telephoto/macro cameras get
                        // the right limits). `cameraInfo` is null
                        // until `bindToLifecycle` finishes, so the
                        // first few frames of a pinch may no-op.
                        detectTransformGestures(panZoomLock = true) { _, _, zoom, _ ->
                            if (zoom == 1f) return@detectTransformGestures
                            val info = cameraController.cameraInfo ?: return@detectTransformGestures
                            val zoomState = info.zoomState.value ?: return@detectTransformGestures
                            val target = (zoomState.zoomRatio * zoom)
                                .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                            cameraController.setZoomRatio(target)
                        }
                    },
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        controller = cameraController
                    }
                }
            )

            // Tracking corner-bracket reticle, drawn on top of the preview.
            ReticleOverlay(detectedRect = detectedRect)

            // Everything over the preview: the multi-code chooser *under*
            // the top chrome and the bottom stack — iOS's ZStack puts
            // `multiCodeOverlay` before `modeChrome` — so the mode switch,
            // the bulk HUD, the banner, the Live Text blocks and the
            // controls row stay tappable while several codes are in view.
            // The controls need this screen's launchers and NFC session,
            // so they come in as the tail of the bottom stack.
            ScannerOverlays(
                state = state,
                mode = mode,
                availableModes = availableModes,
                bulk = bulk,
                continuousScan = settings.continuousScan,
                translateTarget = TranslationCatalog.resolved(settings.translateLanguage),
                callbacks = overlayCallbacks,
            ) {
                NfcReadStatusBanner(nfcReader)

                state.importError?.let { msg ->
                    Text(
                        msg,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Import menu — anchors a DropdownMenu with two
                    // entries: Photo Library (system Photo Picker, images
                    // only) and Files (OpenDocument, accepts PDFs and
                    // images). Mirrors the iOS import `Menu` introduced in
                    // v1.7 and widened to PDFs in v1.8.59.
                    Box {
                        IconButton(
                            onClick = { importMenuExpanded = true },
                            modifier = Modifier.background(Color(0x80000000), CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.Image,
                                contentDescription = "Import image or PDF",
                                tint = Color.White,
                            )
                        }
                        DropdownMenu(
                            expanded = importMenuExpanded,
                            onDismissRequest = { importMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Photo Library") },
                                leadingIcon = {
                                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                                },
                                onClick = {
                                    importMenuExpanded = false
                                    pickMedia.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Files") },
                                leadingIcon = {
                                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                                },
                                onClick = {
                                    importMenuExpanded = false
                                    // Advertise both MIME types so the
                                    // system picker shows PDFs *and*
                                    // images. ImageDecoder.decodeBatch
                                    // routes by MIME at decode-time, so
                                    // either pick is handled correctly.
                                    pickFile.launch(
                                        arrayOf("application/pdf", "image/*")
                                    )
                                },
                            )
                        }
                    }
                    // Read an NFC tag into the normal result sheet. Renders
                    // nothing on a phone without NFC, like iOS's
                    // `if NFCTagWriter.isSupported`.
                    Spacer(Modifier.width(12.dp))
                    NfcReadAction(
                        onResult = { _, raw -> viewModel.onNfcRead(raw) },
                        writer = nfcReader,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { torchOn = !torchOn },
                        modifier = Modifier.background(Color(0x80000000), CircleShape)
                    ) {
                        Icon(
                            if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = if (torchOn) "Turn flashlight off" else "Turn flashlight on",
                            tint = Color.White
                        )
                    }
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(24.dp)
            ) {
                Text(
                    CAMERA_RATIONALE,
                    color = Color.White
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                    Text("Grant camera access")
                }
            }
        }

        if (state.isDecodingImage) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Reading image…", color = Color.White)
            }
        }

    }

    // ---- Result sheet ---------------------------------------------------

    if (state.lastScan != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.dismissResult()
                detectedRect = null
            },
            sheetState = sheetState
        ) {
            ScanResultSheet(
                code = state.lastScan!!,
                codesInFrame = state.sheetCodesInFrame,
                onSave = { notes -> viewModel.saveScan(notes) },
                onSaveAsLoyaltyCard = { merchant ->
                    viewModel.saveAsLoyaltyCard(merchant)
                },
                onDismiss = {
                    viewModel.dismissResult()
                    detectedRect = null
                }
            )
        }
    }

    // ---- Bulk capture sheet ----------------------------------------------

    if (showBulkSheet) {
        BulkCaptureSheet(
            session = bulk,
            onSaveAll = { onSaved, onFailed -> viewModel.saveBulkToHistory(onFailed = onFailed, onSaved = onSaved) },
            onDismiss = { showBulkSheet = false },
        )
    }
}

/**
 * The camera rationale shown until access is granted — the same sentence as
 * the iOS `NSCameraUsageDescription`. Live Text made the camera read printed
 * text as well as codes, so the old barcodes-only wording no longer said
 * everything it is used for.
 */
internal const val CAMERA_RATIONALE =
    "Scan uses the camera to read barcodes and QR codes and, in Live Text mode, " +
        "printed text. Frames are processed on this device and are not saved."

/**
 * What the overlay layer asks the scanner to do: [ScannerViewModel]'s
 * handlers plus the screen's bulk sheet and share helpers in the app, plain
 * recorders in tests — so [ScannerOverlays] composes without CameraX or Hilt.
 */
internal class ScannerOverlayCallbacks(
    val onSelectMode: (ScanModePreference) -> Unit = {},
    val onOpenBulkList: () -> Unit = {},
    val onPickCode: (ScannedCode) -> Unit = {},
    val onDismissChooser: () -> Unit = {},
    val onOpenContinuous: () -> Unit = {},
    val onDismissContinuous: () -> Unit = {},
    val onSelectText: (RecognizedTextBlock) -> Unit = {},
    val onDismissText: () -> Unit = {},
    val onCopyText: (String) -> Unit = {},
    val onShareText: (String) -> Unit = {},
)

/**
 * Everything the Scan tab draws over the live preview, in drawing order —
 * Compose hit-tests a Box's children the other way round, last first:
 *
 *  1. The multi-code chooser outside Live Text: a numbered chip over each
 *     code and a translucent backdrop that dismisses it. It comes *before*
 *     the chrome, as iOS's ZStack puts `multiCodeOverlay` before
 *     `modeChrome`, so a tap on the mode switch, the bulk HUD, the banner
 *     or the controls row still reaches them while 2+ codes are in view;
 *     only a tap on the bare camera lands on the backdrop.
 *  2. The top chrome, iOS `modeChrome`: the mode switch and bulk HUD
 *     ([ScanModeTopRow]), the "tap one" hint, the continuous-scan banner.
 *  3. The bottom stack. In Live Text the codes to pick come first, as
 *     numbered rows: ML Kit reports Live Text's codes in image space, so
 *     there is nothing to pin a chip to, and — like iOS, which draws no
 *     chooser over VisionKit's highlights — no backdrop covers the text.
 *     Then the recognised text, then [bottomControls]: the NFC prompt, an
 *     import error and the controls row.
 *
 * Neither column has pointer handling of its own, so the gaps between the
 * controls stay with whatever is underneath.
 */
@Composable
internal fun BoxScope.ScannerOverlays(
    state: ScannerViewModel.State,
    mode: ScanModePreference,
    availableModes: List<ScanModePreference>,
    bulk: BulkScanSession,
    continuousScan: Boolean,
    translateTarget: TranslateLanguage,
    callbacks: ScannerOverlayCallbacks,
    bottomControls: @Composable ColumnScope.() -> Unit = {},
) {
    val choices = state.multiCodeChoices
    val liveText = mode == ScanModePreference.TEXT

    // 1. The chooser, beneath everything else.
    if (choices.isNotEmpty() && !liveText) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x40000000))
                .clickable(onClick = callbacks.onDismissChooser)
        )
        // One numbered chip per code, positioned over its rect. Chips
        // clickable; tapping commits to that code through the ViewModel.
        Box(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            choices.forEachIndexed { idx, code ->
                val rect = code.previewRect
                if (rect != null) {
                    val xDp = with(density) { rect.center.x.toDp() }
                    val yDp = with(density) { rect.center.y.toDp() }
                    Box(
                        modifier = Modifier
                            .offset(x = xDp - 22.dp, y = yDp - 22.dp)
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable { callbacks.onPickCode(code) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${idx + 1}",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
        // A code ML Kit gave no box for can't get a chip over it; list it
        // instead, numbered the same way, so the chooser never shows a
        // backdrop with nothing to pick.
        val unanchored = choices.withIndex().filter { it.value.previewRect == null }
        if (unanchored.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                unanchored.forEach { (idx, code) -> ChooserRow(idx, code, callbacks.onPickCode) }
            }
        }
    }

    // 2. The top of the frame, like iOS's `modeChrome` VStack, stacked in
    // one column so none of it can sit on top of another part. The footnote
    // under the picker is the per-mode hint `ScanModePreference` carries,
    // shared with iOS's strings.
    val continuous = state.lastContinuous
    val bulkHud: @Composable () -> Unit = {
        BulkCaptureHud(
            count = bulk.count,
            isFull = bulk.isFull,
            tamperWarning = state.frameRisk != null,
            onClick = callbacks.onOpenBulkList,
        )
    }
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScanModeTopRow(
            mode = mode,
            availableModes = availableModes,
            onSelect = callbacks.onSelectMode,
            hud = if (mode == ScanModePreference.BULK && !bulk.isEmpty) bulkHud else null,
        )

        // "Multiple codes — tap one", over the chooser chips in Codes mode
        // and the listed codes in Live Text (iOS `showsPickOneHint`).
        if (choices.isNotEmpty()) {
            Text(
                "Multiple codes — tap one",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .background(Color(0xCC000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // Continuous-scan banner: tap the body to open the result sheet for
        // the most recently auto-saved code; tap the ✕ to dismiss the banner
        // *and* release the same-value dedupe lock so the next sight of any
        // code (including this one) counts as a fresh scan. Only rendered
        // when the toggle is on and there's something to show.
        if (continuousScan && continuous != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .background(Color(0xCC1B5E20), RoundedCornerShape(12.dp))
            ) {
                // Tap-target area — opens the saved scan in the result sheet.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = callbacks.onOpenContinuous)
                        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Saved",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            continuous.value,
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                    Text("Open ›", color = Color.White)
                }

                // Dismiss button — explicit "I'm done, ready for the next
                // scan" gesture. Releases the dedupe.
                IconButton(onClick = callbacks.onDismissContinuous) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Dismiss saved-scan banner",
                        tint = Color.White,
                    )
                }
            }
        }
    }

    // 3. Bottom stack: Live Text's codes and text, then the NFC prompt, an
    // import error and the controls row — one column, so none of them can
    // land on top of another.
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Live Text's codes, above the text so a tall stack of blocks can
        // never cover them.
        if (liveText) {
            choices.forEachIndexed { idx, code -> ChooserRow(idx, code, callbacks.onPickCode) }
        }

        // Live Text results. iOS gets tap-to-select for free from VisionKit's
        // `ImageAnalysisInteraction`; the Android equivalent is the
        // recognised blocks as tap targets — tapping one opens the card,
        // which offers copy / share / translate and stays until the user
        // dismisses it.
        val selected = state.selectedText
        val recognized = state.recognizedBlocks
        if (liveText && selected != null) {
            RecognizedTextCard(
                text = selected,
                onDismiss = callbacks.onDismissText,
                onCopy = callbacks.onCopyText,
                onShare = callbacks.onShareText,
                initialTarget = translateTarget,
            )
        } else if (liveText && recognized.isNotEmpty()) {
            recognized.take(6).forEach { block ->
                Text(
                    block.text,
                    color = Color.White,
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xCC1C1C1E), RoundedCornerShape(12.dp))
                        .clickable { callbacks.onSelectText(block) }
                        .padding(12.dp),
                )
            }
        }

        bottomControls()
    }
}

/** One code to pick, numbered as the chooser numbers it. */
@Composable
private fun ChooserRow(index: Int, code: ScannedCode, onPick: (ScannedCode) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xE61C1C1E), RoundedCornerShape(12.dp))
            .clickable { onPick(code) }
            .padding(12.dp),
    ) {
        Text(
            "${index + 1}",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(code.value, color = Color.White, maxLines = 2)
    }
}

@Composable
private fun ScanResultSheet(
    code: ScannedCode,
    /// Distinct codes the camera saw in the frame that produced [code],
    /// frozen when the scan was handled. Travels with the scan so the
    /// safety inspector's "more than one code in this frame" tamper
    /// finding can fire — iOS passes `frameCounts[scan.id]` into
    /// `PayloadActionsView`. The assistant / safety-check / translate
    /// preferences reach [PayloadActions] through `LocalScanSettings`.
    codesInFrame: Int = 1,
    onSave: (String?) -> Unit,
    onSaveAsLoyaltyCard: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Belt-and-braces: any uncaught exception inside the parser would
    // otherwise propagate up through the bottom-sheet Composable and crash
    // the Activity — and Play's pre-launch test sweeps a lot of malformed
    // payloads at us. Fall back to plain Text so the sheet always renders.
    val payload = remember(code) {
        runCatching { ScanPayloadParser.parse(code.value, code.symbology) }
            .getOrElse { me.nettrash.scan.data.payload.ScanPayload.Text(code.value) }
    }
    var notes by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    // Scrollable: the safety banner, an explanation and a translation can
    // together outgrow the sheet, and the Save / Done row must stay
    // reachable.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                payload.kindLabel,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = Color.White
            )
            Spacer(Modifier.padding(horizontal = 6.dp))
            Text(code.symbology.displayName, color = MaterialTheme.colorScheme.onSurface)
        }

        Text(
            code.value,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            maxLines = 6
        )

        PayloadActions(
            payload = payload,
            raw = code.value,
            codesInFrame = codesInFrame,
            onSaveAsLoyaltyCard = onSaveAsLoyaltyCard,
        )

        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes (optional)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(notes.takeIf { it.isNotBlank() })
                    saved = true
                },
                enabled = !saved
            ) { Text(if (saved) "Saved" else "Save") }
            Button(onClick = onDismiss) { Text("Done") }
        }
    }
}

/**
 * Centred 78% × 78% rect of [size] in PreviewView pixels — the
 * region-of-interest used to filter ML Kit detections. Returns null
 * when [size] is zero (first frame before layout), which the
 * analyzer treats as "no filtering" so we don't drop everything
 * for the first ~30 ms after composition.
 *
 * The 0.78 fraction matches the iOS `roiFraction` in
 * `CameraScannerView.swift` — both platforms intersect against the
 * same proportion of the preview, so a code framed inside the
 * SwiftUI / Compose reticle gets through on both sides.
 */
private fun roiRectFor(size: androidx.compose.ui.unit.IntSize): androidx.compose.ui.geometry.Rect? {
    if (size.width <= 0 || size.height <= 0) return null
    val side = minOf(size.width, size.height) * 0.78f
    val cx = size.width / 2f
    val cy = size.height / 2f
    return androidx.compose.ui.geometry.Rect(
        left   = cx - side / 2f,
        top    = cy - side / 2f,
        right  = cx + side / 2f,
        bottom = cy + side / 2f,
    )
}
