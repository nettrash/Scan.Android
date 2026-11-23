package me.nettrash.scan.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import com.google.zxing.common.BitMatrix
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max
import me.nettrash.scan.generator.QRGeometry
import me.nettrash.scan.generator.QRInkShape
import me.nettrash.scan.generator.QRRect
import me.nettrash.scan.generator.QRStyle
import me.nettrash.scan.generator.QRStyledRenderer

/**
 * Vector exports for QR codes — SVG and PDF — built off the BitMatrix
 * returned by `CodeGenerator.matrix`. Mirrors iOS's `QRSvg.swift`:
 * SVG is run-length-encoded per row so dense codes don't bloat,
 * PDF is drawn with `android.graphics.pdf.PdfDocument` for true vector
 * output that prints cleanly at any size. Both honour the user-picked
 * FG/BG colours.
 *
 * Only QR is handled — Aztec/PDF417/Code128 don't have a stable
 * "module size in absolute units" notion that vector export benefits
 * from, and falling back to PNG for those is fine.
 */
object CodeSvg {

    /** Id of the `<linearGradient>` a styled export emits; every inked
     *  `<path>` references it through `url(#...)`. */
    private const val GRADIENT_ID = "scan-ink"

    /** Long side, in pixels, of the logo inlined into a styled SVG
     *  (iOS `QRSvg.svgLogoPixels`). */
    private const val SVG_LOGO_PIXELS = 512

    /**
     * @param style when non-null and not [QRStyle.isPlain], the SVG carries
     *   the studio's module shapes, eye shapes and gradient ink instead of
     *   plain squares. A null or plain style emits exactly the run-length
     *   encoded `<rect>` form it always has.
     */
    fun svg(
        matrix: BitMatrix,
        fgArgb: Int = Color.BLACK,
        bgArgb: Int = Color.WHITE,
        moduleSize: Int = 10,
        margin: Int = 4,
        style: QRStyle? = null,
    ): String {
        style?.takeIf { !it.isPlain }
            ?.let { styledSvg(matrix, it, moduleSize) }
            ?.let { return it }

        val totalW = (matrix.width + margin * 2) * moduleSize
        val totalH = (matrix.height + margin * 2) * moduleSize
        val fgHex = hex(fgArgb)
        val bgHex = hex(bgArgb)

        val out = StringBuilder(matrix.width * matrix.height * 30)
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
        out.append("viewBox=\"0 0 ").append(totalW).append(' ').append(totalH).append("\" ")
        out.append("width=\"").append(totalW).append("\" height=\"").append(totalH).append("\" ")
        out.append("shape-rendering=\"crispEdges\">\n")
        out.append("<rect width=\"").append(totalW).append("\" height=\"").append(totalH)
            .append("\" fill=\"").append(bgHex).append("\"/>\n")

        // Row-RLE: contiguous "on" modules become one wide <rect>.
        // Trims the file size by ~5x for typical QR codes vs. one
        // <rect> per module.
        for (y in 0 until matrix.height) {
            var x = 0
            while (x < matrix.width) {
                if (!matrix[x, y]) { x++; continue }
                val runStart = x
                while (x < matrix.width && matrix[x, y]) { x++ }
                val runLen = x - runStart
                val px = (margin + runStart) * moduleSize
                val py = (margin + y) * moduleSize
                val pw = runLen * moduleSize
                out.append("<rect x=\"").append(px).append("\" y=\"").append(py)
                    .append("\" width=\"").append(pw).append("\" height=\"").append(moduleSize)
                    .append("\" fill=\"").append(fgHex).append("\"/>\n")
            }
        }

        out.append("</svg>\n")
        return out.toString()
    }

    fun writeSvgUri(
        context: Context,
        svg: String,
        fileName: String = "scan-qr.svg",
    ): android.net.Uri {
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val target = File(sharedDir, fileName)
        target.writeText(svg, Charsets.UTF_8)
        return FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            target,
        )
    }

    /**
     * Render the same matrix as a single-page PDF. Uses
     * [PdfDocument]'s native draw so the output is true vector when
     * opened in any reader that supports PDF 1.4+.
     */
    fun writePdfUri(
        context: Context,
        matrix: BitMatrix,
        fgArgb: Int = Color.BLACK,
        bgArgb: Int = Color.WHITE,
        moduleSize: Int = 10,
        margin: Int = 4,
        fileName: String = "scan-qr.pdf",
        style: QRStyle? = null,
    ): android.net.Uri {
        val studio = style?.takeIf { !it.isPlain }
        val area = studio?.let { QRGeometry.symbolArea(matrix) }
        if (studio != null && area != null) {
            return writeStyledPdfUri(context, matrix, studio, area, moduleSize, fileName)
        }

        val totalW = (matrix.width + margin * 2) * moduleSize
        val totalH = (matrix.height + margin * 2) * moduleSize

        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(totalW, totalH, 1).create()
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas

        val bgPaint = Paint().apply { color = bgArgb; setStyle(Paint.Style.FILL) }
        canvas.drawRect(0f, 0f, totalW.toFloat(), totalH.toFloat(), bgPaint)

        val fgPaint = Paint().apply {
            color = fgArgb
            setStyle(Paint.Style.FILL)
            // Alpha = 255 keeps the modules truly opaque; PDF
            // readers vary on how they composite partially-transparent
            // fills and we don't want surprises.
            isAntiAlias = false
        }
        for (y in 0 until matrix.height) {
            var x = 0
            while (x < matrix.width) {
                if (!matrix[x, y]) { x++; continue }
                val runStart = x
                while (x < matrix.width && matrix[x, y]) { x++ }
                val runLen = x - runStart
                val rect = RectF(
                    ((margin + runStart) * moduleSize).toFloat(),
                    ((margin + y) * moduleSize).toFloat(),
                    ((margin + runStart + runLen) * moduleSize).toFloat(),
                    ((margin + y + 1) * moduleSize).toFloat(),
                )
                canvas.drawRect(rect, fgPaint)
            }
        }

        doc.finishPage(page)

        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val target = File(sharedDir, fileName)
        FileOutputStream(target).use { doc.writeTo(it) }
        doc.close()

        return FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            target,
        )
    }

    /**
     * Vector form of a styled code. Same [QRGeometry] the raster renderer
     * consumes, so the exported SVG and the on-screen preview are the same
     * three ink passes: data modules plus the eye outer rings, the background
     * gap, then the eye centres — and then the logo, where
     * [QRGeometry.logoLayout] puts it. The gradient is anchored to the symbol
     * area in module units - one wash across the code, never painted behind it.
     *
     * Returns null when there is nothing to draw, letting the caller fall
     * back to the plain `<rect>` form.
     */
    private fun styledSvg(matrix: BitMatrix, style: QRStyle, moduleSize: Int): String? {
        val area = QRGeometry.symbolArea(matrix) ?: return null
        val margin = max(style.quietZone, 4)
        val modulesX = area.width.toInt()
        val modulesY = area.height.toInt()
        if (modulesX <= 0 || modulesY <= 0) return null

        val totalW = (modulesX + margin * 2) * moduleSize
        val totalH = (modulesY + margin * 2) * moduleSize
        val passes = QRGeometry.inkPasses(matrix, style, area)

        val bgHex = hex(style.backgroundArgb)
        val usesGradient = style.usesGradient
        val inkFill = if (usesGradient) "url(#$GRADIENT_ID)" else hex(style.primaryInk)
        val logoPng = style.logo?.let(::svgLogoPng)

        val out = StringBuilder(modulesX * modulesY * 40 + (logoPng?.length ?: 0))
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
        if (logoPng != null) {
            out.append("xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
        }
        out.append("viewBox=\"0 0 ").append(totalW).append(' ').append(totalH).append("\" ")
        out.append("width=\"").append(totalW).append("\" height=\"").append(totalH).append("\" ")
        // crispEdges would facet the rounded and circular shapes.
        out.append("shape-rendering=\"geometricPrecision\">\n")

        if (usesGradient) {
            out.append("<defs>\n<linearGradient id=\"").append(GRADIENT_ID)
                .append("\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"0\" x2=\"")
                .append(modulesX)
                .append("\" y2=\"")
                .append(modulesY)
                .append("\">\n")
            val last = style.inks.size - 1
            style.inks.forEachIndexed { index, argb ->
                val offset = if (last <= 0) 0.0 else index.toDouble() / last
                out.append("<stop offset=\"").append(decimal(offset))
                    .append("\" stop-color=\"").append(hex(argb)).append("\"/>\n")
            }
            out.append("</linearGradient>\n</defs>\n")
        }

        out.append("<rect width=\"").append(totalW).append("\" height=\"").append(totalH)
            .append("\" fill=\"").append(bgHex).append("\"/>\n")

        // Module units inside the group; the transform is what maps them onto
        // the canvas past the (trimmed, then re-applied) quiet zone.
        out.append("<g transform=\"translate(")
            .append(margin * moduleSize).append(',').append(margin * moduleSize)
            .append(") scale(").append(moduleSize).append(")\">\n")
        appendSvgPass(out, passes.ink, inkFill)
        appendSvgPass(out, passes.gap, bgHex)
        appendSvgPass(out, passes.centre, inkFill)

        // The passes already left the modules under the logo out, so the
        // artwork needs no backing patch.
        if (logoPng != null) {
            val a = QRGeometry.logoLayout(modulesX).artwork
            // `xlink:href` alone, as on iOS: every SVG 1.1 and SVG 2 reader
            // resolves it (SVG 2 keeps it for backwards compatibility; older
            // Inkscape and librsvg read nothing else), and repeating the
            // base64 under `href` would double the file for nothing.
            out.append("<image x=\"").append(QRGeometry.number(a.x))
                .append("\" y=\"").append(QRGeometry.number(a.y))
                .append("\" width=\"").append(QRGeometry.number(a.width))
                .append("\" height=\"").append(QRGeometry.number(a.height))
                .append("\" preserveAspectRatio=\"xMidYMid meet\"")
                .append(" xlink:href=\"data:image/png;base64,").append(logoPng).append("\"/>\n")
        }

        out.append("</g>\n")
        out.append("</svg>\n")
        return out.toString()
    }

    private fun appendSvgPass(out: StringBuilder, shapes: List<QRInkShape>, fill: String) {
        val d = QRGeometry.svgPathD(shapes)
        if (d.isEmpty()) return
        out.append("<path fill=\"").append(fill)
            .append("\" fill-rule=\"nonzero\" d=\"").append(d).append("\"/>\n")
    }

    /**
     * Centre artwork as base64 PNG, inlined so the vector file stays
     * standalone; null when it cannot be encoded. At most [SVG_LOGO_PIXELS]
     * on its long side, as on iOS: ample for print, since the artwork covers
     * a few dozen modules, while a full-size photo would bloat the file.
     */
    private fun svgLogoPng(logo: Bitmap): String? {
        if (logo.width <= 0 || logo.height <= 0) return null
        return runCatching {
            val (width, height) = QRStyledRenderer.preparedLogoSize(logo.width, logo.height, SVG_LOGO_PIXELS)
            val fitted = if (width == logo.width && height == logo.height) logo else logo.scale(width, height)
            val bytes = ByteArrayOutputStream()
            fitted.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }

    /**
     * Styled PDF: drawn onto the [PdfDocument] canvas by
     * [QRStyledRenderer.draw], the very routine behind the preview and the
     * PNG, so the page carries the same shapes, inks and logo instead of
     * silently dropping back to squares (iOS draws its PDF the same way).
     */
    private fun writeStyledPdfUri(
        context: Context,
        matrix: BitMatrix,
        style: QRStyle,
        area: QRRect,
        moduleSize: Int,
        fileName: String,
    ): android.net.Uri {
        val margin = max(style.quietZone, 4)
        val totalW = (area.width.toInt() + margin * 2) * moduleSize
        val totalH = (area.height.toInt() + margin * 2) * moduleSize

        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(totalW, totalH, 1).create()
        val page = doc.startPage(pageInfo)
        QRStyledRenderer.draw(page.canvas, matrix, style, area, moduleSize)
        doc.finishPage(page)

        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val target = File(sharedDir, fileName)
        FileOutputStream(target).use { doc.writeTo(it) }
        doc.close()

        return FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            target,
        )
    }

    private fun decimal(value: Double): String =
        String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

    /** ARGB int to `#RRGGBB`. Alpha dropped (older renderers choke on `#RRGGBBAA`). */
    private fun hex(argb: Int): String {
        val r = Color.red(argb)
        val g = Color.green(argb)
        val b = Color.blue(argb)
        return String.format("#%02X%02X%02X", r, g, b)
    }
}
