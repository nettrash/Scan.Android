package me.nettrash.scan.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.google.zxing.common.BitMatrix
import java.io.File
import java.io.FileOutputStream

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

    fun svg(
        matrix: BitMatrix,
        fgArgb: Int = Color.BLACK,
        bgArgb: Int = Color.WHITE,
        moduleSize: Int = 10,
        margin: Int = 4,
    ): String {
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
    ): android.net.Uri {
        val totalW = (matrix.width + margin * 2) * moduleSize
        val totalH = (matrix.height + margin * 2) * moduleSize

        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(totalW, totalH, 1).create()
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas

        val bgPaint = Paint().apply { color = bgArgb; style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, totalW.toFloat(), totalH.toFloat(), bgPaint)

        val fgPaint = Paint().apply {
            color = fgArgb
            style = Paint.Style.FILL
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

    /** ARGB-int → `#RRGGBB`. Alpha dropped (older renderers choke on `#RRGGBBAA`). */
    private fun hex(argb: Int): String {
        val r = Color.red(argb)
        val g = Color.green(argb)
        val b = Color.blue(argb)
        return String.format("#%02X%02X%02X", r, g, b)
    }
}
