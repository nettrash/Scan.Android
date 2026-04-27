package me.nettrash.scan.ui.scanner

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Reticle overlay drawn over the camera preview. Mirrors the iOS
 * `ReticleView`: four corner brackets that snap to the detected code's
 * bounding rect, falling back to a centred default-size square when
 * nothing has been recognised yet. The amber stroke colour and
 * spring-damped rect animation match the iOS look so a user moving
 * between the two apps gets the same affordance.
 *
 * `detectedRect` is expected to be in **PreviewView coordinates**
 * (CameraX's `MlKitAnalyzer` with `COORDINATE_SYSTEM_VIEW_REFERENCED`
 * provides this directly, which is why the scanner switched off the
 * raw `ImageAnalysis.Analyzer` path).
 */
@Composable
fun ReticleOverlay(
    detectedRect: Rect?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val defaultSizePx = with(density) { 260.dp.toPx() }
        val verticalLiftPx = with(density) { 80.dp.toPx() }

        val fallback = Rect(
            left = (widthPx - defaultSizePx) / 2f,
            top = (heightPx - defaultSizePx) / 2f - verticalLiftPx,
            right = (widthPx + defaultSizePx) / 2f,
            bottom = (heightPx + defaultSizePx) / 2f - verticalLiftPx,
        )

        // Clamp tiny detections so the reticle stays a usable target. The
        // floor (80 dp) matches the iOS default — small enough to wrap a
        // EAN/UPC barcode tightly, big enough to read.
        val minVisibleDim = with(density) { 80.dp.toPx() }
        val raw = detectedRect ?: fallback
        val target = if (raw.width < minVisibleDim || raw.height < minVisibleDim) {
            val cx = (raw.left + raw.right) / 2f
            val cy = (raw.top + raw.bottom) / 2f
            val w = max(raw.width, minVisibleDim)
            val h = max(raw.height, minVisibleDim)
            Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        } else raw

        val animated by animateRectAsState(
            targetValue = target,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = 350f,
            ),
            label = "ReticleRect",
        )

        val cornerMinPx = with(density) { 20.dp.toPx() }
        val cornerMaxPx = with(density) { 48.dp.toPx() }
        val strokeMinPx = with(density) { 3.dp.toPx() }
        val strokeMaxPx = with(density) { 6.dp.toPx() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawReticle(
                rect = animated,
                cornerSizePx = (min(animated.width, animated.height) * 0.18f)
                    .coerceIn(cornerMinPx, cornerMaxPx),
                strokeWidthPx = (min(animated.width, animated.height) * 0.025f)
                    .coerceIn(strokeMinPx, strokeMaxPx),
            )
        }
    }
}

private fun DrawScope.drawReticle(
    rect: Rect,
    cornerSizePx: Float,
    strokeWidthPx: Float,
) {
    val accent = Color(0xFFFFC107) // amber, matches the iOS accent
    val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)

    // Each corner is an L: two line segments forming the bracket.
    fun drawCorner(start: Offset, mid: Offset, end: Offset) {
        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(mid.x, mid.y)
            lineTo(end.x, end.y)
        }
        drawPath(path = path, color = accent, style = stroke)
    }

    // Top-left: down-stroke, then right-stroke.
    drawCorner(
        Offset(rect.left, rect.top + cornerSizePx),
        Offset(rect.left, rect.top),
        Offset(rect.left + cornerSizePx, rect.top),
    )
    // Top-right: down-stroke from inside, then meet right edge.
    drawCorner(
        Offset(rect.right - cornerSizePx, rect.top),
        Offset(rect.right, rect.top),
        Offset(rect.right, rect.top + cornerSizePx),
    )
    // Bottom-right.
    drawCorner(
        Offset(rect.right, rect.bottom - cornerSizePx),
        Offset(rect.right, rect.bottom),
        Offset(rect.right - cornerSizePx, rect.bottom),
    )
    // Bottom-left.
    drawCorner(
        Offset(rect.left + cornerSizePx, rect.bottom),
        Offset(rect.left, rect.bottom),
        Offset(rect.left, rect.bottom - cornerSizePx),
    )
}
