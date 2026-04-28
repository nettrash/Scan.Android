package me.nettrash.scan.data.payload

import android.net.Uri
import java.util.Locale

/**
 * Parsed GS1 Application Identifier element string. Mirrors the iOS
 * [GS1Payload] struct — supports the parens form `(01)…(17)…(10)…`,
 * the GS1 Digital Link URL form, and the FNC1 / unbracketed form.
 */
data class GS1Payload(
    val form: Form,
    val elements: List<Element>,
    val raw: String
) {
    enum class Form(val displayName: String) {
        PARENS("Element string (parens)"),
        DIGITAL_LINK("GS1 Digital Link"),
        UNBRACKETED("Element string (FNC1)")
    }

    data class Element(val ai: String, val value: String)

    fun valueFor(ai: String): String? = elements.firstOrNull { it.ai == ai }?.value
    val gtin: String? get() = valueFor("01")
    val batchLot: String? get() = valueFor("10")
    val serial: String? get() = valueFor("21")
    val expiry: String? get() = valueFor("17")
    val bestBefore: String? get() = valueFor("15")
    val production: String? get() = valueFor("11")

    fun labelledFields(): List<LabelledField> = elements.map { e ->
        val name = GS1Registry.nameFor(e.ai) ?: "AI ${e.ai}"
        val display = GS1Registry.formatValue(e.ai, e.value)
        LabelledField("$name (${e.ai})", display)
    }
}

object GS1Registry {

    private data class AIInfo(val name: String, val length: Int?, val isDate: Boolean)

    private val registry: Map<String, AIInfo> = mapOf(
        "00" to AIInfo("SSCC", 18, false),
        "01" to AIInfo("GTIN", 14, false),
        "02" to AIInfo("GTIN of contained items", 14, false),
        "10" to AIInfo("Batch / lot", null, false),
        "20" to AIInfo("Variant", 2, false),
        "21" to AIInfo("Serial number", null, false),
        "22" to AIInfo("Secondary data", null, false),
        "240" to AIInfo("Additional product ID", null, false),
        "241" to AIInfo("Customer part number", null, false),
        "242" to AIInfo("Made-to-order variation", null, false),
        "243" to AIInfo("Component / part", null, false),
        "250" to AIInfo("Secondary serial number", null, false),
        "251" to AIInfo("Reference to source entity", null, false),
        "253" to AIInfo("GDTI", null, false),
        "254" to AIInfo("GLN extension component", null, false),
        "11" to AIInfo("Production date", 6, true),
        "12" to AIInfo("Due date", 6, true),
        "13" to AIInfo("Packaging date", 6, true),
        "15" to AIInfo("Best before", 6, true),
        "16" to AIInfo("Sell by", 6, true),
        "17" to AIInfo("Expiry", 6, true),
        "30" to AIInfo("Variable count", null, false),
        "37" to AIInfo("Item count", null, false),
        "390" to AIInfo("Amount payable", null, false),
        "391" to AIInfo("Amount payable + currency", null, false),
        "392" to AIInfo("Amount payable single item", null, false),
        "393" to AIInfo("Amount + currency single", null, false),
        "400" to AIInfo("Customer order number", null, false),
        "401" to AIInfo("Consignment number", null, false),
        "402" to AIInfo("Shipment ID number", 17, false),
        "403" to AIInfo("Routing code", null, false),
        "410" to AIInfo("Ship to / deliver to GLN", 13, false),
        "411" to AIInfo("Bill to GLN", 13, false),
        "412" to AIInfo("Purchased from GLN", 13, false),
        "413" to AIInfo("Ship for / deliver for GLN", 13, false),
        "414" to AIInfo("Identification of physical location (GLN)", 13, false),
        "420" to AIInfo("Ship to / deliver to postal code", null, false),
        "421" to AIInfo("Ship to + ISO country", null, false),
        "422" to AIInfo("Country of origin", 3, false),
        "8200" to AIInfo("Extended packaging URL", null, false),
        "8003" to AIInfo("GRAI", null, false),
        "8004" to AIInfo("GIAI", null, false),
        "8017" to AIInfo("GSRN provider", 18, false),
        "8018" to AIInfo("GSRN recipient", 18, false),
        "8020" to AIInfo("Payment slip ref", null, false),
    )

    private val knownPrefixes: List<String> =
        registry.keys.sortedByDescending { it.length }

    fun nameFor(ai: String): String? = registry[ai]?.name
    fun lengthFor(ai: String): Int? = registry[ai]?.length
    fun isDate(ai: String): Boolean = registry[ai]?.isDate ?: false

    fun formatValue(ai: String, value: String): String {
        if (!isDate(ai) || value.length != 6) return value
        val yy = value.substring(0, 2)
        val mm = value.substring(2, 4)
        val dd = value.substring(4, 6)
        val year = if ((yy.toIntOrNull() ?: 0) >= 70) "19$yy" else "20$yy"
        val dayPart = if (dd == "00") "" else "-$dd"
        return "$year-$mm$dayPart"
    }

    fun resolveVariablePrefix(s: String): Int? {
        for (prefix in knownPrefixes) if (s.startsWith(prefix)) return prefix.length
        return if (s.length >= 2) 2 else null
    }
}

object GS1Parser {

    private const val FNC1 = ''

    fun looksLikeGS1(raw: String): Boolean {
        if (raw.startsWith("(")) {
            return raw.drop(1).take(4).all { it.isDigit() || it == ')' }
        }
        // Digital Link: `/<2-digit ai>/<8..14 digits>` somewhere in the path.
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        if (uri != null && uri.host?.isNotEmpty() == true) {
            val path = uri.path.orEmpty()
            if (Regex("""/0\d/\d{8,14}""").containsMatchIn(path)) return true
        }
        if (raw.firstOrNull() == FNC1) return true
        if (raw.length >= 4 && raw.take(2).all { it.isDigit() }) {
            val firstTwo = raw.substring(0, 2)
            val firstThree = if (raw.length >= 3) raw.substring(0, 3) else null
            val firstFour = if (raw.length >= 4) raw.substring(0, 4) else null
            if (GS1Registry.lengthFor(firstTwo) != null) return true
            if (firstThree != null && GS1Registry.lengthFor(firstThree) != null) return true
            if (firstFour != null && GS1Registry.lengthFor(firstFour) != null) return true
        }
        return false
    }

    fun parse(raw: String): GS1Payload? {
        if (raw.startsWith("(")) return parseParens(raw)
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            return parseDigitalLink(raw)
        }
        return parseFNC1(raw)
    }

    // ---- Parens form ----

    private fun parseParens(raw: String): GS1Payload? {
        val elements = mutableListOf<GS1Payload.Element>()
        var i = 0
        while (i < raw.length) {
            if (raw[i] != '(') return null
            val close = raw.indexOf(')', i)
            if (close < 0) return null
            val ai = raw.substring(i + 1, close)
            val valStart = close + 1
            val nextOpen = raw.indexOf('(', valStart).let { if (it < 0) raw.length else it }
            val value = raw.substring(valStart, nextOpen)
            elements += GS1Payload.Element(ai, value)
            i = nextOpen
        }
        return if (elements.isEmpty()) null else
            GS1Payload(GS1Payload.Form.PARENS, elements, raw)
    }

    // ---- Digital Link ----

    private fun parseDigitalLink(raw: String): GS1Payload? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val segs = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val elements = mutableListOf<GS1Payload.Element>()
        var idx = 0
        while (idx + 1 < segs.size) {
            val ai = segs[idx]
            if (!ai.all { it.isDigit() } || GS1Registry.nameFor(ai) == null) {
                idx += 1; continue
            }
            elements += GS1Payload.Element(ai, segs[idx + 1])
            idx += 2
        }
        for (key in uri.queryParameterNames) {
            if (key.all { it.isDigit() } && GS1Registry.nameFor(key) != null) {
                uri.getQueryParameter(key)?.let {
                    elements += GS1Payload.Element(key, it)
                }
            }
        }
        return if (elements.isEmpty()) null else
            GS1Payload(GS1Payload.Form.DIGITAL_LINK, elements, raw)
    }

    // ---- FNC1 / unbracketed ----

    private fun parseFNC1(raw: String): GS1Payload? {
        var s = if (raw.firstOrNull() == FNC1) raw.substring(1) else raw
        val elements = mutableListOf<GS1Payload.Element>()
        while (s.isNotEmpty()) {
            val prefixLen = GS1Registry.resolveVariablePrefix(s) ?: return null
            val ai = s.substring(0, prefixLen)
            s = s.substring(prefixLen)
            val fixed = GS1Registry.lengthFor(ai)
            if (fixed != null) {
                if (s.length < fixed) return null
                elements += GS1Payload.Element(ai, s.substring(0, fixed))
                s = s.substring(fixed)
            } else {
                val fnc = s.indexOf(FNC1)
                if (fnc >= 0) {
                    elements += GS1Payload.Element(ai, s.substring(0, fnc))
                    s = s.substring(fnc + 1)
                } else {
                    elements += GS1Payload.Element(ai, s)
                    s = ""
                }
            }
        }
        return if (elements.isEmpty()) null else
            GS1Payload(GS1Payload.Form.UNBRACKETED, elements, raw)
    }
}
