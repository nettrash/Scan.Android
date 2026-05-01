package me.nettrash.scan.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Compact HSV colour picker dialog. Hue strip + saturation/value
 * square + hex text field + a row of preset swatches. ~95 % of the
 * picker UI most apps need without dragging in another dependency.
 *
 * Mirrors the affordances of iOS's `ColorPicker` SwiftUI control
 * closely enough that the Generator screen feels like the same
 * product on either platform.
 */
@Composable
fun ColorPickerDialog(
    initial: Color,
    title: String,
    onPick: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialHsv = floatArrayOf(0f, 0f, 0f)
    android.graphics.Color.colorToHSV(initial.toArgb(), initialHsv)

    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    var hexInput by remember { mutableStateOf(toHex(initial)) }

    val current: Color = remember(hue, sat, value) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Saturation/value square. X = saturation 0…1,
                // Y = value 1…0 (top is bright).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.White,
                                    Color.hsv(hue, 1f, 1f),
                                ),
                            ),
                        )
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black,
                                ),
                            ),
                        )
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                sat = (offset.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                                hexInput = toHex(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))))
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                hexInput = toHex(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))))
                            }
                        }
                )

                // Hue strip — rainbow.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = (0..6).map {
                                    Color.hsv(it * 60f, 1f, 1f)
                                }
                            ),
                        )
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                hue = ((offset.x / size.width) * 360f).coerceIn(0f, 360f)
                                hexInput = toHex(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))))
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                hue = ((change.position.x / size.width) * 360f).coerceIn(0f, 360f)
                                hexInput = toHex(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))))
                            }
                        }
                )

                // Preview swatch + hex text field side-by-side.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(current),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { newValue ->
                            hexInput = newValue
                            parseHex(newValue)?.let { argb ->
                                val hsv = floatArrayOf(0f, 0f, 0f)
                                android.graphics.Color.colorToHSV(argb, hsv)
                                hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                            }
                        },
                        label = { Text("Hex") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Preset row. Mostly black/white plus the accent /
                // common brand-friendly choices.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presetSwatches.forEach { preset ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(preset)
                                .pointerInput(preset) {
                                    detectTapGestures {
                                        val hsv = floatArrayOf(0f, 0f, 0f)
                                        android.graphics.Color.colorToHSV(preset.toArgb(), hsv)
                                        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                                        hexInput = toHex(preset)
                                    }
                                },
                        )
                    }
                }

                Text(
                    "Aim for ≥3:1 contrast against the other colour for reliable scanning.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Start,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current); onDismiss() }) { Text("Pick") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Coloured row that opens [ColorPickerDialog] on tap. The Generator
 * screen embeds two of these (foreground / background). */
@Composable
fun ColorPickerRow(
    label: String,
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pointerInput(Unit) {
                detectTapGestures { open = true }
            }
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
    if (open) {
        ColorPickerDialog(
            initial = color,
            title = label,
            onPick = onColorChange,
            onDismiss = { open = false },
        )
    }
}

private val presetSwatches: List<Color> = listOf(
    Color.Black,
    Color.White,
    Color(0xFF1976D2), // blue
    Color(0xFF388E3C), // green
    Color(0xFFD32F2F), // red
    Color(0xFFF57C00), // orange
    Color(0xFF7B1FA2), // purple
    Color(0xFF455A64), // blue-grey
)

private fun toHex(c: Color): String {
    val argb = c.toArgb()
    return String.format(
        "#%02X%02X%02X",
        android.graphics.Color.red(argb),
        android.graphics.Color.green(argb),
        android.graphics.Color.blue(argb),
    )
}

private fun parseHex(input: String): Int? {
    val hex = input.trim().removePrefix("#")
    if (hex.length != 6) return null
    return runCatching { hex.toLong(16).toInt() or 0xFF000000.toInt() }.getOrNull()
}

/** Compose has no `Color.hsv` factory; this approximates one. Kept
 *  file-private so the picker can reach for it without polluting the
 *  global `Color.Companion`. */
private fun Color.Companion.hsv(hue: Float, sat: Float, value: Float): Color {
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    return Color(argb)
}
