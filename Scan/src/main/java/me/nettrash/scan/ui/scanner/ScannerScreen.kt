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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import me.nettrash.scan.scanner.ImageDecoder
import me.nettrash.scan.scanner.ScannedCode
import me.nettrash.scan.scanner.Symbology
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

    // The detected code's bounding rect, in PreviewView coordinates.
    // `null` while we haven't seen a code yet — the reticle then falls back
    // to a centred default-size square. Held as Compose state so the
    // ReticleOverlay can animate to it whenever it changes.
    var detectedRect by remember { mutableStateOf<Rect?>(null) }

    val scope = rememberCoroutineScope()

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

    LaunchedEffect(cameraPermission.status.isGranted, barcodeScanner) {
        if (!cameraPermission.status.isGranted) return@LaunchedEffect
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analyzer = MlKitAnalyzer(
            listOf(barcodeScanner),
            ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
            mainExecutor,
        ) { result ->
            val barcodes = result.getValue(barcodeScanner) ?: return@MlKitAnalyzer
            // Collect *all* readable codes in the frame — the
            // ViewModel decides what to do with multiplicity. Dedupe
            // by `rawValue` so the same code recognised twice in a
            // single frame doesn't show as two chooser chips.
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

    // ---- UI --------------------------------------------------------------

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraPermission.status.isGranted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        controller = cameraController
                    }
                }
            )

            // Tracking corner-bracket reticle, drawn on top of the preview.
            ReticleOverlay(detectedRect = detectedRect)

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
            ) {
                IconButton(
                    onClick = {
                        pickMedia.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.background(Color(0x80000000), CircleShape)
                ) {
                    Icon(Icons.Filled.Image, contentDescription = "Pick image", tint = Color.White)
                }
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
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(24.dp)
            ) {
                Text(
                    "Scan needs the camera to read 1D and 2D barcodes such as QR, EAN, UPC, Code 128, PDF417, Aztec and Data Matrix.",
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

        // Multi-code chooser. When more than one code is in frame,
        // the camera analyzer pushes the list into state.multiCodeChoices
        // (suppressing the result sheet). We render numbered chips
        // anchored at each code's preview rect, plus a translucent
        // backdrop the user can tap to dismiss.
        if (state.multiCodeChoices.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x40000000))
                    .clickable { viewModel.dismissMultiCodeChooser() }
            )
            // Banner at the top of the frame.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .background(Color(0xCC000000))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    "Multiple codes — tap one",
                    color = Color.White,
                )
            }
            // One numbered chip per code, positioned over its rect.
            // Chips clickable; tapping commits to that code through
            // the ViewModel.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                state.multiCodeChoices.forEachIndexed { idx, code ->
                    val rect = code.previewRect
                    if (rect != null) {
                        val xDp = with(density) { rect.center.x.toDp() }
                        val yDp = with(density) { rect.center.y.toDp() }
                        Box(
                            modifier = Modifier
                                .offset(x = xDp - 22.dp, y = yDp - 22.dp)
                                .size(44.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .clickable { viewModel.pickFromChoices(code) },
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
        }

        // Continuous-scan banner: tap the body to open the result
        // sheet for the most recently auto-saved code; tap the ✕ to
        // dismiss the banner *and* release the same-value dedupe
        // lock so the next sight of any code (including this one)
        // counts as a fresh scan. Only rendered when the toggle is
        // on and there's something to show.
        val continuous = state.lastContinuous
        if (settings.continuousScan && continuous != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .background(Color(0xCC1B5E20))
            ) {
                // Tap-target area — opens the saved scan in the
                // result sheet.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { viewModel.openLastContinuous() }
                        .padding(start = 14.dp, end = 6.dp,
                                 top = 10.dp, bottom = 10.dp),
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

                // Dismiss button — explicit "I'm done, ready for
                // the next scan" gesture. Releases the dedupe.
                IconButton(
                    onClick = { viewModel.dismissContinuousBanner() },
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Dismiss saved-scan banner",
                        tint = Color.White,
                    )
                }
            }
        }

        state.importError?.let { msg ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp)
                    .background(Color(0xCC000000))
                    .padding(12.dp)
            ) { Text(msg, color = Color.White) }
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
}

@Composable
private fun ScanResultSheet(
    code: ScannedCode,
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

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
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
