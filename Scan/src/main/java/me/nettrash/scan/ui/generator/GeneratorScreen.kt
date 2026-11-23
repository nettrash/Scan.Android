package me.nettrash.scan.ui.generator

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.nettrash.scan.data.payload.ComposeResult
import me.nettrash.scan.data.payload.ScanAssistant
import me.nettrash.scan.data.CodeSvg
import me.nettrash.scan.generator.CodeComposer
import me.nettrash.scan.generator.CodeGenerator
import me.nettrash.scan.generator.GeneratableSymbology
import me.nettrash.scan.generator.PreviewFailure
import me.nettrash.scan.generator.QRErrorCorrection
import me.nettrash.scan.generator.QRGeometry
import me.nettrash.scan.generator.QREyeShape
import me.nettrash.scan.generator.QRModuleShape
import me.nettrash.scan.generator.QRStyledRenderer
import me.nettrash.scan.generator.QRStyle
import me.nettrash.scan.nfc.NfcWriteAction
import me.nettrash.scan.ui.components.LocalScanSettings
import me.nettrash.scan.ui.components.ColorPickerRow
import me.nettrash.scan.ui.components.copyToClipboard
import me.nettrash.scan.ui.components.saveBitmapToPictures
import me.nettrash.scan.ui.components.shareImage
import me.nettrash.scan.util.Haptics

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

    // QR art studio. Shapes and the optional second ink; colours and error
    // correction keep their existing state above.
    var moduleShape by remember { mutableStateOf(QRModuleShape.SQUARE) }
    var eyeShape by remember { mutableStateOf(QREyeShape.SQUARE) }
    var usesSecondInk by remember { mutableStateOf(false) }
    var secondInk by remember { mutableStateOf(Color.Blue) }
    var presetID by remember { mutableStateOf("classic") }

    var symbologyMenuExpanded by remember { mutableStateOf(false) }
    var securityMenuExpanded by remember { mutableStateOf(false) }
    var ecMenuExpanded by remember { mutableStateOf(false) }

    val pickLogo = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        logoUri = uri
    }

    // Load the logo bitmap whenever the URI changes, as iOS's `preparedLogo`
    // does: off the main thread, upright (ImageDecoder applies the EXIF
    // orientation) and at most QRStyledRenderer.LOGO_MAX_PIXELS on its long
    // side — a 12 MP photo would otherwise be redrawn on every preview refresh
    // and embedded whole in the PDF. Software pixels, because the studio draws
    // the logo onto bitmap and PDF canvases, which reject hardware bitmaps.
    LaunchedEffect(logoUri) {
        val uri = logoUri
        logoBitmap = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val (width, height) = QRStyledRenderer.preparedLogoSize(info.size.width, info.size.height)
                    if (width != info.size.width || height != info.size.height) {
                        decoder.setTargetSize(width, height)
                    }
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

    // MARK: - Style

    val isQR = symbology == GeneratableSymbology.QR

    /** The style the preview and every export use: shapes from the studio
     *  rows, colours and the logo from the existing pickers. Mirrors iOS's
     *  `activeStyle`. */
    val activeStyle = remember(
        isQR,
        moduleShape,
        eyeShape,
        foregroundColor,
        backgroundColor,
        usesSecondInk,
        secondInk,
        effectiveLogo,
    ) {
        QRStyle(
            moduleShape = if (isQR) moduleShape else QRModuleShape.SQUARE,
            eyeShape = if (isQR) eyeShape else QREyeShape.SQUARE,
            inks = buildList {
                add(foregroundColor.toArgb())
                if (usesSecondInk && isQR) add(secondInk.toArgb())
            },
            backgroundArgb = backgroundColor.toArgb(),
            quietZone = 4,
            logo = effectiveLogo,
        )
    }

    /** Error correction the style demands. Rounded modules, ring eyes and
     *  inset artwork cost module contrast, so they buy back redundancy
     *  automatically; the user's pick is raised, never lowered. */
    val effectiveErrorCorrection =
        CodeGenerator.effectiveErrorCorrection(qrErrorCorrection, activeStyle)

    /** Only a non-default style routes through the studio renderer, so the
     *  default generator output and exports stay exactly what they are
     *  today. */
    val isStyled = isQR && !activeStyle.isPlain

    val bitmap: Bitmap? = remember(
        encoded,
        symbology,
        foregroundColor,
        backgroundColor,
        qrErrorCorrection,
        effectiveLogo,
        isStyled,
        activeStyle,
    ) {
        if (encoded.isEmpty()) null
        else if (isStyled) CodeGenerator.styledBitmap(
            content = encoded,
            style = activeStyle,
            moduleSize = 12,
            errorCorrection = qrErrorCorrection,
        )
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

    /** Modules per side of the styled QR, for the density warning; null for
     *  the plain path (iOS `PreviewResult.code`'s `styledSide`). */
    val styledSide: Int? = remember(encoded, isStyled, activeStyle, qrErrorCorrection) {
        if (!isStyled || encoded.isEmpty()) null
        else CodeGenerator.styledMatrix(encoded, activeStyle, qrErrorCorrection)
            ?.let { QRGeometry.symbolArea(it)?.width?.toInt() }
    }

    /** WCAG contrast of the palest ink against the background. With a
     *  gradient every ink is measured and the worst is reported — a wash is
     *  only as legible as its palest end (iOS `contrastRatio`). */
    val contrast = remember(activeStyle) {
        activeStyle.inks.minOfOrNull { CodeGenerator.contrastRatio(it, activeStyle.backgroundArgb) } ?: 21.0
    }

    /** The "describe the code" box's result, copied onto the form. */
    fun applyComposed(fill: ComposeFormFill) {
        when (fill) {
            is ComposeFormFill.Text -> {
                inputKind = InputKind.TEXT
                textInput = fill.text
            }
            is ComposeFormFill.Url -> {
                inputKind = InputKind.URL
                urlInput = fill.url
            }
            is ComposeFormFill.Wifi -> {
                inputKind = InputKind.WIFI
                wifiSSID = fill.ssid
                wifiPassword = fill.password
                fill.security?.let { wifiSecurity = it }
                wifiHidden = fill.hidden
            }
            is ComposeFormFill.Contact -> {
                inputKind = InputKind.CONTACT
                contactName = fill.name
                contactPhone = fill.phone
                contactEmail = fill.email
                contactOrg = fill.organization
                contactURL = ""
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Generate", style = MaterialTheme.typography.headlineSmall)

        // "Describe the code" — a sentence in, a filled-in form out (iOS
        // `AssistantComposeBox`). Hidden when the assistant is switched off
        // in Settings → Insights.
        if (LocalScanSettings.current.assistantEnabled) {
            DescribeTheCodeBox(onResult = ::applyComposed)
        }

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

            // Say so when the studio overrode the user's choice.
            if (effectiveErrorCorrection != qrErrorCorrection) {
                Text(
                    "Shapes and artwork raise error correction to " +
                        "${effectiveErrorCorrection.displayName} automatically \u2014 that " +
                        "redundancy is what pays for them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            // QR art studio: presets, shapes, and the second ink.
            StyleDropdown(
                label = "Preset",
                options = QRStyle.presets.map { it.name },
                selected = QRStyle.presets.indexOfFirst { it.id == presetID }.coerceAtLeast(0),
                onSelect = { index -> presetID = QRStyle.presets[index].id },
            )
            StyleDropdown(
                label = "Modules",
                options = QRModuleShape.values().map { it.label },
                selected = moduleShape.ordinal,
                onSelect = { index -> moduleShape = QRModuleShape.values()[index] },
            )
            StyleDropdown(
                label = "Eyes",
                options = QREyeShape.values().map { it.label },
                selected = eyeShape.ordinal,
                onSelect = { index -> eyeShape = QREyeShape.values()[index] },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = usesSecondInk, onCheckedChange = { usesSecondInk = it })
                Spacer(Modifier.width(8.dp))
                Text("Gradient ink")
            }
            if (usesSecondInk) {
                ColorPickerRow("Second ink", secondInk, onColorChange = { secondInk = it })
            }

            QRStyle.preset(presetID)?.let { preset ->
                OutlinedButton(onClick = {
                    moduleShape = preset.style.moduleShape
                    eyeShape = preset.style.eyeShape
                    usesSecondInk = preset.style.inks.size > 1
                    preset.style.inks.firstOrNull()?.let { foregroundColor = Color(it) }
                    if (preset.style.inks.size > 1) {
                        preset.style.inks.lastOrNull()?.let { secondInk = Color(it) }
                    }
                    backgroundColor = Color(preset.style.backgroundArgb)
                    Haptics.success(context)
                }) { Text("Apply \u201c${preset.name}\u201d") }
            }
        }

        // Reset + the studio's scannability footnote (iOS's Style footer).
        TextButton(onClick = {
            foregroundColor = Color.Black
            backgroundColor = Color.White
            moduleShape = QRModuleShape.SQUARE
            eyeShape = QREyeShape.SQUARE
            usesSecondInk = false
            presetID = "classic"
        }) { Text("Reset to black on white") }
        Text(
            if (symbology == GeneratableSymbology.QR) {
                "Shapes and artwork raise error correction to High automatically \u2014 " +
                    "that redundancy is what pays for them. Aim for a contrast ratio of at " +
                    "least 3:1 for every ink."
            } else {
                "Colour customisation works on every supported symbology. Aim for a " +
                    "contrast ratio of at least 3:1."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )

        // NFC: write what the form encodes onto a writable tag (iOS
        // `nfcSection`). Renders nothing on a phone without NFC.
        NfcWriteAction(value = encoded)

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
            Text(
                "Logos sit at about 22% of the code's width on a patch of the background " +
                    "colour \u2014 well within what High error correction can recover.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
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
                // Locale.US: a decimal point in every locale, as iOS's
                // unlocalised String(format:) prints it.
                Text(
                    "Contrast ${String.format(java.util.Locale.US, "%.1f", contrast)}:1 is below the safe threshold (3:1). Some scanners will struggle.",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Shaped modules run out of margin on dense codes — dots soonest, all of
        // them sooner with a logo (`QRStyle.isHardToScan`): say so rather than
        // let the user print a code their own camera may not read. Same wording
        // as iOS.
        val densityWarning = styledSide?.let { activeStyle.scannabilityWarning(it) }
        if (densityWarning != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    densityWarning,
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
                                style = activeStyle.takeIf { isStyled },
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
                                style = activeStyle.takeIf { isStyled },
                            )
                        }) {
                            Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                            Spacer(Modifier.width(6.dp)); Text("Share PDF")
                        }
                    }
                }
            }
        } else {
            // Say why instead of asking for fields that are already filled in
            // (iOS `PreviewFailure`); only a failed preview pays to find out.
            val failure = remember(encoded, symbology, activeStyle, qrErrorCorrection) {
                PreviewFailure.reason(encoded, symbology, activeStyle, qrErrorCorrection)
            }
            Text(failure.message)
        }
    }
}

// MARK: - Describe the code

/**
 * The Generate tab's "describe the code" box: a sentence in, a filled-in form
 * out — the port of iOS `AssistantComposeBox`. [ScanAssistant.compose] runs on
 * the device (the rule-based engine today; an on-device model if one is ever
 * registered), so nothing typed here is sent anywhere. Applying the result is
 * the caller's job ([ComposeFormFill]); the user still sees and checks every
 * field before sharing the code.
 */
@Composable
private fun DescribeTheCodeBox(onResult: (ComposeFormFill) -> Unit) {
    var request by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val trimmed = request.trim()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Describe the code", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = request,
            onValueChange = { request = it },
            placeholder = { Text("Wi-Fi for network BlueHouse, password hunter2") },
            maxLines = 3,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                working = true
                scope.launch {
                    val result: ComposeResult? = ScanAssistant.compose(trimmed)
                    val fill = result?.let(ComposeFormFill::from)
                    note = when {
                        result == null || fill == null ->
                            "Scan couldn't tell what to build from that. Start with the kind of " +
                                "code: \u201Cwifi \u2026\u201D, \u201Ccontact for \u2026\u201D, a link, or \u201Ctext \u2026\u201D."
                        result.source == ComposeResult.ComposeSource.ON_DEVICE_MODEL ->
                            "Filled in by the on-device model."
                        else -> "Filled in by the built-in assistant."
                    }
                    if (fill != null) onResult(fill)
                    working = false
                }
            },
            enabled = !working && trimmed.isNotEmpty(),
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (working) "Filling in the form\u2026" else "Fill in the form")
        }
        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        Text(
            "Understood on this device \u2014 nothing you type is sent anywhere. Check the " +
                "filled-in fields before you share the code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }
}

// MARK: - Studio dropdown

/** Menu-anchor dropdown for the studio rows. Same affordance as the
 *  error-correction control above, factored out so Preset / Modules / Eyes
 *  don't each repeat the boilerplate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StyleDropdown(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = options.getOrElse(selected) { "" },
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelect(index); expanded = false }
                )
            }
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
    style: QRStyle?,
) {
    // Same encoding as the preview: a style raises the level it demands, a
    // logo forces HIGH, and neither is the caller's problem.
    val raised = style?.let { CodeGenerator.effectiveErrorCorrection(errorCorrection, it) }
        ?: errorCorrection
    val ec = if (hasLogo) QRErrorCorrection.HIGH else raised
    val matrix = CodeGenerator.matrix(
        content = content,
        symbology = GeneratableSymbology.QR,
        errorCorrection = ec,
    ) ?: return
    val svg = CodeSvg.svg(matrix, fgArgb = fg, bgArgb = bg, style = style)
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
    style: QRStyle?,
) {
    val raised = style?.let { CodeGenerator.effectiveErrorCorrection(errorCorrection, it) }
        ?: errorCorrection
    val ec = if (hasLogo) QRErrorCorrection.HIGH else raised
    val matrix = CodeGenerator.matrix(
        content = content,
        symbology = GeneratableSymbology.QR,
        errorCorrection = ec,
    ) ?: return
    val uri = CodeSvg.writePdfUri(context, matrix, fgArgb = fg, bgArgb = bg, style = style)
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
