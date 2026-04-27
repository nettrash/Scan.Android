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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
    var torchOn by remember { mutableStateOf(false) }

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
            val first = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }
                ?: return@MlKitAnalyzer
            // Update the reticle position even on debounced repeats so it
            // keeps tracking the code if it moves.
            first.boundingBox?.let { box ->
                detectedRect = Rect(
                    box.left.toFloat(),
                    box.top.toFloat(),
                    box.right.toFloat(),
                    box.bottom.toFloat(),
                )
            }
            viewModel.onScan(
                ScannedCode(
                    value = first.rawValue!!,
                    symbology = Symbology.fromMlKit(first.format),
                    timestampMillis = System.currentTimeMillis(),
                    previewRect = detectedRect,
                ),
                dedupe = true,
            )
        }
        cameraController.setImageAnalysisAnalyzer(mainExecutor, analyzer)
        cameraController.bindToLifecycle(lifecycleOwner)
    }

    LaunchedEffect(torchOn) {
        // enableTorch queues until the camera is ready, so this is safe to
        // call before bindToLifecycle has finished.
        runCatching { cameraController.enableTorch(torchOn) }
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
    onDismiss: () -> Unit
) {
    val payload = remember(code) { ScanPayloadParser.parse(code.value, code.symbology) }
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

        PayloadActions(payload = payload, raw = code.value)

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
