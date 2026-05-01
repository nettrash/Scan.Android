package me.nettrash.scan.ui.generator

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.nettrash.scan.data.CodeSvg
import me.nettrash.scan.generator.CodeComposer
import me.nettrash.scan.generator.CodeGenerator
import me.nettrash.scan.generator.GeneratableSymbology
import me.nettrash.scan.generator.QRErrorCorrection
import me.nettrash.scan.ui.components.ColorPickerRow
import me.nettrash.scan.ui.components.copyToClipboard
import me.nettrash.scan.ui.components.saveBitmapToPictures
import me.nettrash.scan.ui.components.shareImage

private enum class InputKind(val label: String) {
    TEXT("Text"), URL("URL"), CONTACT("Contact"), WIFI("Wi-Fi")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorScreen() {
    val context = LocalContext.current

    var inputKind by remember { mutableStateOf(InputKind.TEXT) }
    var symbology by remember { mutableStateOf(GeneratableSymbology.QR) }

    var textInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("https://") }

    var contactName by remember { mutableStateOf("") }
    var contactPhone by remember { mutableStateOf("") }
    var contactEmail by remember { mutableStateOf("") }
    var contactOrg by remember { mutableStateOf("") }
    var contactURL by remember { mutableStateOf("") }

    var wifiSSID by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var wifiSecurity by remember { mutableStateOf(CodeComposer.WifiSecurity.WPA) }
    var wifiHidden by remember { mutableStateOf(false) }

    // 1.3: style + logo state.
    var foregroundColor by remember { mutableStateOf(Color.Black) }
    var backgroundColor by remember { mutableStateOf(Color.White) }
    var qrErrorCorrection by remember { mutableStateOf(QRErrorCorrection.MEDIUM) }
    var logoUri by remember { mutableStateOf<Uri?>(null) }
    var logoBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var symbologyMenuExpanded by remember { mutableStateOf(false) }
    var securityMenuExpanded by remember { mutableStateOf(false) }
    var ecMenuExpanded by remember { mutableStateOf(false) }

    val pickLogo = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        logoUri = uri
    }

    // Load the logo bitmap whenever the URI changes. Kept off the
    // main thread implicitly via LaunchedEffect — for picker-sized
    // images this is fast enough that the user doesn't notice.
    LaunchedEffect(logoUri) {
        val uri = logoUri
        logoBitmap = if (uri == null) null else {
            runCatching {
                context.contentResolver.openInputStream(uri).use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()
        }
    }

    val encoded: String = when (inputKind) {
        InputKind.TEXT -> textInput
        InputKind.URL -> urlInput.trim()
        InputKind.CONTACT -> {
            val pieces = listOf(contactName, contactPhone, contactEmail, contactOrg, contactURL)
                .map { it.trim() }
            if (pieces.all { it.isEmpty() }) ""
            else CodeComposer.vCard(
                fullName = contactName,
                phone = contactPhone,
                email = contactEmail,
                organization = contactOrg,
                url = contactURL
            )
        }
        InputKind.WIFI -> {
            val ssid = wifiSSID.trim()
            if (ssid.isEmpty()) ""
            else CodeComposer.wifi(
                ssid = ssid,
                password = wifiPassword,
                security = wifiSecurity,
                hidden = wifiHidden
            )
        }
    }

    val effectiveLogo = if (symbology.supportsLogo) logoBitmap else null
    val bitmap: Bitmap? = remember(encoded, symbology, foregroundColor, backgroundColor, qrErrorCorrection, effectiveLogo) {
        if (encoded.isEmpty()) null
        else CodeGenerator.bitmap(
            content = encoded,
            symbology = symbology,
            scale = 12,
            foregroundArgb = foregroundColor.toArgb(),
            backgroundArgb = backgroundColor.toArgb(),
            errorCorrection = qrErrorCorrection,
            logo = effectiveLogo,
        )
    }

    val contrast = remember(foregroundColor, backgroundColor) {
        CodeGenerator.contrastRatio(foregroundColor.toArgb(), backgroundColor.toArgb())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Generate", style = MaterialTheme.typography.headlineSmall)

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            InputKind.values().forEachIndexed { idx, k ->
                SegmentedButton(
                    selected = inputKind == k,
                    onClick = { inputKind = k },
                    shape = SegmentedButtonDefaults.itemShape(idx, InputKind.values().size)
                ) { Text(k.label) }
            }
        }

        when (inputKind) {
            InputKind.TEXT -> OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                label = { Text("Anything you want to encode") },
                modifier = Modifier.fillMaxWidth()
            )
            InputKind.URL -> OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            InputKind.CONTACT -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(contactName, { contactName = it }, label = { Text("Full name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    contactPhone, { contactPhone = it }, label = { Text("Phone") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                OutlinedTextField(
                    contactEmail, { contactEmail = it }, label = { Text("Email") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email)
                )
                OutlinedTextField(contactOrg, { contactOrg = it }, label = { Text("Organization (optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    contactURL, { contactURL = it }, label = { Text("Website (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
            }
            InputKind.WIFI -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(wifiSSID, { wifiSSID = it }, label = { Text("Network name (SSID)") }, modifier = Modifier.fillMaxWidth())
                if (wifiSecurity != CodeComposer.WifiSecurity.OPEN) {
                    OutlinedTextField(
                        wifiPassword, { wifiPassword = it }, label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                }
                ExposedDropdownMenuBox(
                    expanded = securityMenuExpanded,
                    onExpandedChange = { securityMenuExpanded = !securityMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = wifiSecurity.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Security") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = securityMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = securityMenuExpanded,
                        onDismissRequest = { securityMenuExpanded = false }
                    ) {
                        CodeComposer.WifiSecurity.values().forEach { sec ->
                            DropdownMenuItem(
                                text = { Text(sec.displayName) },
                                onClick = { wifiSecurity = sec; securityMenuExpanded = false }
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = wifiHidden, onCheckedChange = { wifiHidden = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Hidden network")
                }
            }
        }

        ExposedDropdownMenuBox(
            expanded = symbologyMenuExpanded,
            onExpandedChange = { symbologyMenuExpanded = !symbologyMenuExpanded }
        ) {
            OutlinedTextField(
                value = symbology.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Symbology") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = symbologyMenuExpanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = symbologyMenuExpanded,
                onDismissRequest = { symbologyMenuExpanded = false }
            ) {
                GeneratableSymbology.values().forEach { sym ->
                    DropdownMenuItem(
                        text = { Text(sym.displayName) },
                        onClick = { symbology = sym; symbologyMenuExpanded = false }
                    )
                }
            }
        }

        if (symbology == GeneratableSymbology.CODE128 && encoded.contains('\n')) {
            Text(
                "Code 128 is a 1D format and can't encode multi-line content reliably.",
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        // Style block
        Text("Style", style = MaterialTheme.typography.titleSmall)
        ColorPickerRow("Foreground", foregroundColor, onColorChange = { foregroundColor = it })
        ColorPickerRow("Background", backgroundColor, onColorChange = { backgroundColor = it })

        if (symbology == GeneratableSymbology.QR) {
            ExposedDropdownMenuBox(
                expanded = ecMenuExpanded,
                onExpandedChange = { ecMenuExpanded = !ecMenuExpanded }
            ) {
                OutlinedTextField(
                    value = qrErrorCorrection.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Error correction") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ecMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = ecMenuExpanded,
                    onDismissRequest = { ecMenuExpanded = false }
                ) {
                    QRErrorCorrection.values().forEach { lvl ->
                        DropdownMenuItem(
                            text = { Text(lvl.displayName) },
                            onClick = { qrErrorCorrection = lvl; ecMenuExpanded = false }
                        )
                    }
                }
            }
        }

        // Logo block (QR only)
        if (symbology.supportsLogo) {
            Text("Logo", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (logoBitmap != null) {
                    Image(
                        bitmap = logoBitmap!!.asImageBitmap(),
                        contentDescription = "Selected logo",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (logoBitmap == null) "No logo" else "Logo set")
                    Text(
                        if (logoBitmap == null)
                            "Add an image to embed at the centre of the QR."
                        else
                            "QR will use High error correction to keep it scannable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    pickLogo.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) { Text(if (logoBitmap == null) "Choose logo…" else "Change logo…") }
                if (logoBitmap != null) {
                    TextButton(onClick = { logoUri = null }) { Text("Remove") }
                }
            }
        }

        // Contrast warning — same threshold as iOS.
        if (contrast < 3.0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Contrast ${"%.1f".format(contrast)}:1 is below the safe threshold (3:1). Some scanners will struggle.",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Preview
        Text("Preview", style = MaterialTheme.typography.titleMedium)
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Generated ${symbology.displayName} code",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(Color.White)
                    .padding(8.dp)
            )
            // Action row: PNG share / save / copy / vector exports.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val uri = saveBitmapToPictures(context, bitmap, "scan_${System.currentTimeMillis()}.png")
                        if (uri != null) shareImage(context, uri)
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Spacer(Modifier.width(6.dp)); Text("Share")
                    }
                    Button(onClick = {
                        saveBitmapToPictures(context, bitmap, "scan_${System.currentTimeMillis()}.png")
                    }) {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp)); Text("Save")
                    }
                    Button(onClick = { copyToClipboard(context, encoded) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(6.dp)); Text("Copy")
                    }
                }
                if (symbology == GeneratableSymbology.QR) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            shareSvg(
                                context = context,
                                content = encoded,
                                fg = foregroundColor.toArgb(),
                                bg = backgroundColor.toArgb(),
                                errorCorrection = qrErrorCorrection,
                                hasLogo = effectiveLogo != null,
                            )
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = null)
                            Spacer(Modifier.width(6.dp)); Text("Share SVG")
                        }
                        OutlinedButton(onClick = {
                            sharePdf(
                                context = context,
                                content = encoded,
                                fg = foregroundColor.toArgb(),
                                bg = backgroundColor.toArgb(),
                                errorCorrection = qrErrorCorrection,
                                hasLogo = effectiveLogo != null,
                            )
                        }) {
                            Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                            Spacer(Modifier.width(6.dp)); Text("Share PDF")
                        }
                    }
                }
            }
        } else {
            Text("Fill in the fields above to see a preview.")
        }
    }
}

// MARK: - Vector export helpers

/// Build the QR module matrix at the requested error-correction
/// level, render as SVG, write to a FileProvider URI, and fire an
/// ACTION_SEND chooser. Logo presence forces HIGH error correction
/// to mirror the bitmap path.
private fun shareSvg(
    context: android.content.Context,
    content: String,
    fg: Int,
    bg: Int,
    errorCorrection: QRErrorCorrection,
    hasLogo: Boolean,
) {
    val ec = if (hasLogo) QRErrorCorrection.HIGH else errorCorrection
    val matrix = CodeGenerator.matrix(
        content = content,
        symbology = GeneratableSymbology.QR,
        errorCorrection = ec,
    ) ?: return
    val svg = CodeSvg.svg(matrix, fgArgb = fg, bgArgb = bg)
    val uri = CodeSvg.writeSvgUri(context, svg)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/svg+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Share QR (SVG)").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    )
}

private fun sharePdf(
    context: android.content.Context,
    content: String,
    fg: Int,
    bg: Int,
    errorCorrection: QRErrorCorrection,
    hasLogo: Boolean,
) {
    val ec = if (hasLogo) QRErrorCorrection.HIGH else errorCorrection
    val matrix = CodeGenerator.matrix(
        content = content,
        symbology = GeneratableSymbology.QR,
        errorCorrection = ec,
    ) ?: return
    val uri = CodeSvg.writePdfUri(context, matrix, fgArgb = fg, bgArgb = bg)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Share QR (PDF)").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    )
}
