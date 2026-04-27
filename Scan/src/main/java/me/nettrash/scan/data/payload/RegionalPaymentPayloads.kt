package me.nettrash.scan.data.payload

import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

// ---- UPI (India) -------------------------------------------------------

data class UPIPayload(
    val payeeAddress: String,
    val payeeName: String?,
    val amount: String?,
    val currency: String?,
    val note: String?,
    val referenceURL: String?,
    val merchantCode: String?,
    val transactionID: String?,
    val raw: String
) {
    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf<LabelledField>()
        rows += LabelledField("Payee VPA", payeeAddress)
        payeeName?.let { rows += LabelledField("Payee", it) }
        if (amount != null) {
            val c = currency ?: "INR"
            rows += LabelledField("Amount", "$amount $c")
        }
        note?.let { rows += LabelledField("Note", it) }
        merchantCode?.let { rows += LabelledField("Merchant code", it) }
        transactionID?.let { rows += LabelledField("Transaction ID", it) }
        referenceURL?.let { rows += LabelledField("Reference URL", it) }
        return rows
    }
}

// ---- Czech SPD (Spayd) -------------------------------------------------

data class CzechSPDPayload(
    val version: String,
    val fields: List<Pair<String, String>>
) {
    private companion object {
        val LABELS = mapOf(
            "ACC" to "Account (IBAN)",
            "ALT-ACC" to "Alternative accounts",
            "AM" to "Amount",
            "CC" to "Currency",
            "MSG" to "Message",
            "RN" to "Recipient name",
            "X-ID" to "Reference ID",
            "DT" to "Due date",
            "X-VS" to "Variable symbol",
            "X-KS" to "Constant symbol",
            "X-SS" to "Specific symbol",
            "PT" to "Payment type",
            "RF" to "Creditor reference",
            "NT" to "Notification type",
            "NTA" to "Notification address"
        )
    }

    fun valueFor(key: String): String? = fields.firstOrNull { it.first == key }?.second
    val iban: String? get() = valueFor("ACC")
    val amount: String? get() = valueFor("AM")
    val currency: String? get() = valueFor("CC")
    val message: String? get() = valueFor("MSG")
    val recipient: String? get() = valueFor("RN")
    val dueDate: String? get() = valueFor("DT")
    val variableSymbol: String? get() = valueFor("X-VS")
    val constantSymbol: String? get() = valueFor("X-KS")
    val specificSymbol: String? get() = valueFor("X-SS")

    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf<LabelledField>()
        recipient?.let { rows += LabelledField("Recipient", it) }
        iban?.let { rows += LabelledField("IBAN", it) }
        amount?.let { rows += LabelledField("Amount", currency?.let { c -> "$it $c" } ?: "$it CZK") }
        message?.let { rows += LabelledField("Message", it) }
        dueDate?.let { rows += LabelledField("Due date", it) }
        variableSymbol?.let { rows += LabelledField("Variable symbol", it) }
        constantSymbol?.let { rows += LabelledField("Constant symbol", it) }
        specificSymbol?.let { rows += LabelledField("Specific symbol", it) }
        val surfaced = setOf("ACC", "AM", "CC", "MSG", "RN", "DT", "X-VS", "X-KS", "X-SS")
        for ((k, v) in fields) {
            if (k in surfaced) continue
            rows += LabelledField(LABELS[k] ?: k, v)
        }
        return rows
    }
}

// ---- Slovak Pay by Square (recognition only) ---------------------------

data class PayBySquarePayload(val raw: String) {
    fun labelledFields(): List<LabelledField> = listOf(
        LabelledField("Format", "Pay by Square (Slovakia)"),
        LabelledField(
            "Note",
            "Decoding requires LZMA. Open this in your bank's app or use Share / Copy."
        ),
        LabelledField("Token", raw)
    )
}

// ---- Regional URI-scheme payments --------------------------------------

data class RegionalPaymentPayload(
    val scheme: Scheme,
    val raw: String,
    val parsed: List<LabelledField>
) {
    enum class Scheme(val displayName: String) {
        BEZAHLCODE("Bezahlcode"),
        SWISH("Swish"),
        VIPPS("Vipps"),
        MOBILEPAY("MobilePay"),
        BIZUM("Bizum"),
        IDEAL("iDEAL")
    }

    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf(LabelledField("Scheme", scheme.displayName))
        rows += parsed
        return rows
    }
}

object RegionalPaymentParser {

    val knownURISchemes: Map<String, RegionalPaymentPayload.Scheme> = mapOf(
        "bank" to RegionalPaymentPayload.Scheme.BEZAHLCODE,
        "bezahlcode" to RegionalPaymentPayload.Scheme.BEZAHLCODE,
        "swish" to RegionalPaymentPayload.Scheme.SWISH,
        "vipps" to RegionalPaymentPayload.Scheme.VIPPS,
        "mobilepay" to RegionalPaymentPayload.Scheme.MOBILEPAY,
        "bizum" to RegionalPaymentPayload.Scheme.BIZUM,
        "ideal" to RegionalPaymentPayload.Scheme.IDEAL
    )

    fun parseUPI(raw: String): UPIPayload? {
        if (!raw.lowercase(Locale.ROOT).startsWith("upi:")) return null
        val body = raw.substring(4)
        val q = body.indexOf('?').takeIf { it > 0 } ?: return null
        val cmd = body.substring(0, q).lowercase(Locale.ROOT).removePrefix("//")
        if (cmd != "pay" && cmd != "mandate") return null
        val query = body.substring(q + 1)

        val pairs = mutableMapOf<String, String>()
        for (kv in query.split('&')) {
            val parts = kv.split('=', limit = 2)
            if (parts.size != 2) continue
            val v = decode(parts[1])
            pairs[parts[0].lowercase(Locale.ROOT)] = v
        }
        val pa = pairs["pa"]?.takeIf { it.isNotEmpty() } ?: return null
        return UPIPayload(
            payeeAddress = pa,
            payeeName = pairs["pn"],
            amount = pairs["am"],
            currency = pairs["cu"] ?: "INR",
            note = pairs["tn"],
            referenceURL = pairs["url"],
            merchantCode = pairs["mc"],
            transactionID = pairs["tr"] ?: pairs["tid"],
            raw = raw
        )
    }

    fun parseCzechSPD(raw: String): CzechSPDPayload? {
        if (!raw.startsWith("SPD*")) return null
        val parts = raw.split('*')
        if (parts.size < 3 || parts[0] != "SPD") return null
        val version = parts[1]
        val fields = mutableListOf<Pair<String, String>>()
        for (part in parts.drop(2)) {
            if (part.isEmpty()) continue
            val colon = part.indexOf(':').takeIf { it > 0 } ?: continue
            val key = part.substring(0, colon)
            val value = decode(part.substring(colon + 1).replace('+', ' '))
            if (key.isEmpty()) continue
            fields += key to value
        }
        if (fields.isEmpty()) return null
        return CzechSPDPayload(version, fields)
    }

    fun looksLikePayBySquare(raw: String): Boolean {
        if (raw.length < 32) return false
        val validHeaders = listOf(
            "0000A", "0000B", "0000C", "0000D", "0000E",
            "0008A", "0008B", "0008C",
            "00010", "00018", "00020"
        )
        if (!validHeaders.any { raw.startsWith(it) }) return false
        val allowed = ('0'..'9').toSet() + ('A'..'V').toSet()
        return raw.all { it in allowed }
    }

    fun parsePayBySquare(raw: String): PayBySquarePayload? =
        if (looksLikePayBySquare(raw)) PayBySquarePayload(raw) else null

    fun parseRegional(raw: String): RegionalPaymentPayload? {
        val colon = raw.indexOf(':').takeIf { it > 0 } ?: return null
        val scheme = raw.substring(0, colon).lowercase(Locale.ROOT)
        val kind = knownURISchemes[scheme] ?: return null
        return when (kind) {
            RegionalPaymentPayload.Scheme.BEZAHLCODE -> parseBezahlcode(raw)
            RegionalPaymentPayload.Scheme.SWISH -> parseSwish(raw)
            RegionalPaymentPayload.Scheme.VIPPS -> parseVipps(raw)
            RegionalPaymentPayload.Scheme.MOBILEPAY -> parseMobilePay(raw)
            RegionalPaymentPayload.Scheme.BIZUM -> parseBizum(raw)
            RegionalPaymentPayload.Scheme.IDEAL -> parseIDEAL(raw)
        }
    }

    private fun parseBezahlcode(raw: String): RegionalPaymentPayload? {
        val pairs = queryPairs(raw)
        val labels = mapOf(
            "name" to "Beneficiary",
            "iban" to "IBAN",
            "bic" to "BIC",
            "amount" to "Amount",
            "currency" to "Currency",
            "reason" to "Purpose",
            "executiondate" to "Execution date",
            "creditorid" to "Creditor ID",
            "mandateid" to "Mandate ID",
            "dateofsignature" to "Mandate date",
            "reference" to "Reference"
        )
        val parsed = pairs.map { (k, v) ->
            LabelledField(labels[k.lowercase(Locale.ROOT)] ?: k, v)
        }
        if (parsed.isEmpty()) return null
        return RegionalPaymentPayload(RegionalPaymentPayload.Scheme.BEZAHLCODE, raw, parsed)
    }

    private fun parseSwish(raw: String): RegionalPaymentPayload? {
        val pairs = queryPairs(raw)
        val parsed = mutableListOf<LabelledField>()
        val data = pairs.firstOrNull { it.first.equals("data", ignoreCase = true) }?.second
        if (data != null) {
            val labels = mapOf(
                "payee" to "Payee",
                "amount" to "Amount",
                "message" to "Message",
                "currency" to "Currency",
                "reference" to "Reference"
            )
            val decoded = runCatching {
                Base64.decode(
                    data.replace('-', '+').replace('_', '/').padBase64(),
                    Base64.DEFAULT
                )
            }.getOrNull()
            val str = decoded?.toString(Charsets.UTF_8)
            val obj = str?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (obj != null) {
                val keys = obj.keys().asSequence().toList().sorted()
                for (k in keys) {
                    val v = obj.get(k).toString()
                    parsed += LabelledField(labels[k.lowercase(Locale.ROOT)] ?: k, v)
                }
            } else {
                parsed += LabelledField("Data", data)
            }
        }
        return RegionalPaymentPayload(RegionalPaymentPayload.Scheme.SWISH, raw, parsed)
    }

    private fun parseVipps(raw: String): RegionalPaymentPayload {
        val pairs = queryPairs(raw)
        val labels = mapOf(
            "phonenumber" to "Phone number",
            "amount" to "Amount",
            "message" to "Message",
            "merchantserialnumber" to "Merchant ID",
            "ordertext" to "Order text"
        )
        val parsed = pairs.map { (k, v) ->
            LabelledField(labels[k.lowercase(Locale.ROOT)] ?: k, v)
        }
        return RegionalPaymentPayload(RegionalPaymentPayload.Scheme.VIPPS, raw, parsed)
    }

    private fun parseMobilePay(raw: String): RegionalPaymentPayload {
        val pairs = queryPairs(raw)
        val labels = mapOf(
            "phone" to "Phone number",
            "amount" to "Amount",
            "comment" to "Comment",
            "lock" to "Locked amount"
        )
        val parsed = pairs.map { (k, v) ->
            LabelledField(labels[k.lowercase(Locale.ROOT)] ?: k, v)
        }
        return RegionalPaymentPayload(RegionalPaymentPayload.Scheme.MOBILEPAY, raw, parsed)
    }

    private fun parseBizum(raw: String): RegionalPaymentPayload {
        val pairs = queryPairs(raw)
        val labels = mapOf(
            "amount" to "Amount",
            "concept" to "Concept",
            "phone" to "Phone number"
        )
        val parsed = pairs.map { (k, v) ->
            LabelledField(labels[k.lowercase(Locale.ROOT)] ?: k, v)
        }
        return RegionalPaymentPayload(RegionalPaymentPayload.Scheme.BIZUM, raw, parsed)
    }

    private fun parseIDEAL(raw: String): RegionalPaymentPayload {
        val pairs = queryPairs(raw)
        val labels = mapOf(
            "amount" to "Amount",
            "description" to "Description",
            "iban" to "IBAN",
            "name" to "Beneficiary",
            "reference" to "Reference"
        )
        val parsed = pairs.map { (k, v) ->
            LabelledField(labels[k.lowercase(Locale.ROOT)] ?: k, v)
        }
        return RegionalPaymentPayload(RegionalPaymentPayload.Scheme.IDEAL, raw, parsed)
    }

    private fun queryPairs(raw: String): List<Pair<String, String>> {
        val q = raw.indexOf('?').takeIf { it > 0 } ?: return emptyList()
        val query = raw.substring(q + 1)
        val out = mutableListOf<Pair<String, String>>()
        for (kv in query.split('&')) {
            val parts = kv.split('=', limit = 2)
            if (parts.size != 2) continue
            out += parts[0] to decode(parts[1])
        }
        return out
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, StandardCharsets.UTF_8.name()) }.getOrDefault(s)

    private fun String.padBase64(): String {
        val pad = (4 - this.length % 4) % 4
        return this + "=".repeat(pad)
    }
}
