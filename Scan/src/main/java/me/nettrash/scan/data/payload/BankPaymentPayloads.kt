package me.nettrash.scan.data.payload

import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ---- EPC SEPA Payment (GiroCode) ----------------------------------------

data class EPCPaymentPayload(
    val version: String,
    val bic: String?,
    val beneficiaryName: String?,
    val iban: String?,
    val currency: String?,
    val amount: String?,
    val purposeCode: String?,
    val structuredReference: String?,
    val unstructuredRemittance: String?,
    val beneficiaryInfo: String?
) {
    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf<LabelledField>()
        beneficiaryName?.let { rows += LabelledField("Beneficiary", it) }
        iban?.let { rows += LabelledField("IBAN", it) }
        bic?.let { rows += LabelledField("BIC", it) }
        if (amount != null && currency != null) rows += LabelledField("Amount", "$amount $currency")
        purposeCode?.let { rows += LabelledField("Purpose code", it) }
        structuredReference?.let { rows += LabelledField("Reference", it) }
        unstructuredRemittance?.let { rows += LabelledField("Remittance info", it) }
        beneficiaryInfo?.let { rows += LabelledField("Beneficiary info", it) }
        return rows
    }
}

// ---- Russian Unified Payment (ST00012) ----------------------------------

data class RussianPaymentField(val key: String, val value: String)

data class RussianPaymentPayload(
    val version: String,
    val fields: List<RussianPaymentField>
) {
    private companion object {
        val LABELS = mapOf(
            "Name" to "Recipient",
            "PersonalAcc" to "Account",
            "BankName" to "Bank",
            "BIC" to "BIC",
            "CorrespAcc" to "Correspondent account",
            "PayeeINN" to "Recipient INN",
            "KPP" to "KPP",
            "KBK" to "Budget code (KBK)",
            "OKTMO" to "Territorial code (OKTMO)",
            "Sum" to "Amount",
            "Purpose" to "Purpose",
            "LastName" to "Payer last name",
            "FirstName" to "Payer first name",
            "MiddleName" to "Payer middle name",
            "PayerINN" to "Payer INN",
            "PayerAddress" to "Payer address",
            "PaytReason" to "Tax basis",
            "TaxPeriod" to "Tax period",
            "DocNo" to "Document number",
            "DocDate" to "Document date",
            "TaxPaytKind" to "Payment kind",
            "BirthDate" to "Birth date",
            "Phone" to "Phone",
            "ChildFio" to "Child"
        )
    }

    fun labelledFields(): List<LabelledField> = fields.map { f ->
        val label = LABELS[f.key] ?: f.key
        val value = if (f.key == "Sum") {
            f.value.toIntOrNull()?.let { kopecks ->
                String.format(Locale.US, "%.2f ₽", kopecks / 100.0)
            } ?: f.value
        } else f.value
        LabelledField(label, value)
    }
}

// ---- Russian FNS Receipt -----------------------------------------------

data class FNSReceiptPayload(
    val rawTimestamp: String,
    val sum: String?,
    val fiscalNumber: String?,
    val receiptNumber: String?,
    val fiscalSign: String?,
    val receiptType: String?
) {
    val date: Date?
        get() {
            for (pattern in listOf("yyyyMMdd'T'HHmmss", "yyyyMMdd'T'HHmm")) {
                val f = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("Europe/Moscow")
                }
                runCatching { return f.parse(rawTimestamp) }
            }
            return null
        }

    val receiptTypeLabel: String?
        get() = when (receiptType) {
            "1" -> "Sale"
            "2" -> "Sale refund"
            "3" -> "Expense"
            "4" -> "Expense refund"
            else -> receiptType
        }

    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf<LabelledField>()
        date?.let {
            val df = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM,
                java.text.DateFormat.SHORT,
                Locale.getDefault()
            )
            rows += LabelledField("Date", df.format(it))
        } ?: run { rows += LabelledField("Date", rawTimestamp) }
        sum?.let { rows += LabelledField("Amount", it) }
        receiptTypeLabel?.let { rows += LabelledField("Type", it) }
        fiscalNumber?.let { rows += LabelledField("FN (fiscal accumulator)", it) }
        receiptNumber?.let { rows += LabelledField("FD (receipt number)", it) }
        fiscalSign?.let { rows += LabelledField("FPD (fiscal sign)", it) }
        return rows
    }
}

// ---- Swiss QR-bill (SPC) ----------------------------------------------

data class SwissQRBillAddress(
    val addressType: String?,
    val name: String?,
    val streetOrLine1: String?,
    val houseNoOrLine2: String?,
    val postCode: String?,
    val city: String?,
    val country: String?
) {
    val isEmpty: Boolean
        get() = name == null && streetOrLine1 == null && houseNoOrLine2 == null
            && postCode == null && city == null && country == null

    val formatted: String?
        get() {
            val pieces = listOfNotNull(
                name,
                joined(streetOrLine1, houseNoOrLine2, " "),
                joined(postCode, city, " "),
                country
            ).filter { it.isNotEmpty() }
            return if (pieces.isEmpty()) null else pieces.joinToString(", ")
        }

    private fun joined(a: String?, b: String?, sep: String): String? {
        val ax = a?.takeIf { it.isNotEmpty() }
        val bx = b?.takeIf { it.isNotEmpty() }
        return when {
            ax != null && bx != null -> "$ax$sep$bx"
            ax != null -> ax
            bx != null -> bx
            else -> null
        }
    }
}

data class SwissQRBillPayload(
    val version: String,
    val iban: String?,
    val creditor: SwissQRBillAddress?,
    val ultimateCreditor: SwissQRBillAddress?,
    val amount: String?,
    val currency: String?,
    val ultimateDebtor: SwissQRBillAddress?,
    val referenceType: String?,
    val reference: String?,
    val unstructuredMessage: String?,
    val billInformation: String?
) {
    private companion object {
        val REF_TYPE_LABEL = mapOf(
            "QRR" to "QR-Reference",
            "SCOR" to "Creditor Reference",
            "NON" to "No reference"
        )
    }

    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf<LabelledField>()
        creditor?.formatted?.let { rows += LabelledField("Creditor", it) }
        iban?.let { rows += LabelledField("IBAN", it) }
        when {
            amount != null && currency != null -> rows += LabelledField("Amount", "$amount $currency")
            amount != null -> rows += LabelledField("Amount", amount)
        }
        ultimateDebtor?.formatted?.let { rows += LabelledField("Debtor", it) }
        ultimateCreditor?.formatted?.takeIf { it.isNotEmpty() }
            ?.let { rows += LabelledField("Ultimate creditor", it) }
        referenceType?.let {
            rows += LabelledField("Reference type", REF_TYPE_LABEL[it] ?: it)
        }
        reference?.takeIf { it.isNotEmpty() }?.let { rows += LabelledField("Reference", it) }
        unstructuredMessage?.takeIf { it.isNotEmpty() }?.let { rows += LabelledField("Message", it) }
        billInformation?.takeIf { it.isNotEmpty() }?.let { rows += LabelledField("Bill info", it) }
        return rows
    }
}

// ---- Serbian fiscal receipt (SUF) -------------------------------------

data class SerbianFiscalReceiptPayload(val url: String) {
    fun labelledFields(): List<LabelledField> = listOf(
        LabelledField("Verification URL", url),
        LabelledField("Issuer", "Tax Administration of Serbia (PURS)")
    )
}

// ---- Serbian NBS IPS QR (Prenesi) -------------------------------------

data class SerbianIPSField(val key: String, val value: String)

data class SerbianIPSPayload(val fields: List<SerbianIPSField>) {

    private companion object {
        val LABELS = mapOf(
            "K" to "Code",
            "V" to "Version",
            "C" to "Charset",
            "R" to "Account",
            "N" to "Recipient",
            "I" to "Amount",
            "SF" to "Payment code",
            "S" to "Purpose",
            "RO" to "Reference",
            "P" to "Payer"
        )
        val KIND_LABEL = mapOf(
            "PR" to "Bill payment (PR)",
            "PT" to "POS — merchant QR (PT)",
            "PK" to "POS — customer QR (PK)"
        )
    }

    fun valueFor(key: String): String? = fields.firstOrNull { it.key == key }?.value
    val kind: String? get() = valueFor("K")

    fun labelledFields(): List<LabelledField> = fields.map { f ->
        val label = LABELS[f.key] ?: f.key
        val decoded = runCatching {
            Uri.decode(f.value)
        }.getOrDefault(f.value) ?: f.value
        val display = if (f.key == "K") KIND_LABEL[decoded] ?: decoded else decoded
        LabelledField(label, display)
    }
}

// ---- EMVCo Merchant QR -------------------------------------------------

data class EMVField(val tag: String, val value: String)

data class EMVPaymentPayload(val fields: List<EMVField>) {

    private companion object {
        val TOP_LEVEL_LABELS = mapOf(
            "00" to "Payload format",
            "01" to "Initiation method",
            "52" to "Merchant category",
            "53" to "Currency",
            "54" to "Amount",
            "55" to "Tip indicator",
            "56" to "Tip value",
            "57" to "Convenience fee",
            "58" to "Country",
            "59" to "Merchant name",
            "60" to "Merchant city",
            "61" to "Postal code",
            "62" to "Additional data",
            "63" to "CRC",
            "64" to "Merchant info language"
        )
        val INITIATION_METHOD = mapOf(
            "11" to "Static QR (multiple uses)",
            "12" to "Dynamic QR (single use)"
        )
        val CURRENCY_CODES = mapOf(
            "036" to "AUD", "124" to "CAD", "156" to "CNY", "344" to "HKD",
            "356" to "INR", "392" to "JPY", "410" to "KRW", "458" to "MYR",
            "554" to "NZD", "643" to "RUB", "702" to "SGD", "752" to "SEK",
            "756" to "CHF", "764" to "THB", "784" to "AED", "826" to "GBP",
            "840" to "USD", "858" to "UYU", "894" to "ZMW", "971" to "AFN",
            "972" to "TJS", "974" to "BYN", "975" to "BGN", "978" to "EUR",
            "980" to "UAH", "981" to "GEL", "985" to "PLN", "986" to "BRL"
        )
        val ADDITIONAL_DATA_LABELS = mapOf(
            "01" to "Bill number",
            "02" to "Mobile number",
            "03" to "Store label",
            "04" to "Loyalty number",
            "05" to "Reference label",
            "06" to "Customer label",
            "07" to "Terminal label",
            "08" to "Purpose of transaction",
            "09" to "Additional consumer data request"
        )
        val KNOWN_GUIDS = mapOf(
            "BR.GOV.BCB.PIX" to "Pix",
            "BR.GOV.BCB.SPI" to "Pix (SPI)",
            "SG.PAYNOW" to "PayNow",
            "SG.COM.NETS" to "NETS",
            "TH.COM.SAMSUNG.SPAY" to "Samsung Pay (TH)",
            "MX.COM.BANXICO.CODI" to "CoDi",
            "INT.COM.UPI" to "UPI",
            "UPI" to "UPI",
            "HK.COM.HKICL" to "FPS (Hong Kong)",
            "MY.COM.PAYNET" to "DuitNow",
            "PH.COM.BANCNETPAY" to "BancNet Pay",
            "ID.QRIS" to "QRIS",
            "ID.CO.QRIS.WWW" to "QRIS",
            "VN.NAPAS" to "NAPAS"
        )
    }

    fun valueFor(tag: String): String? = fields.firstOrNull { it.tag == tag }?.value
    val merchantName: String? get() = valueFor("59")
    val merchantCity: String? get() = valueFor("60")
    val amount: String? get() = valueFor("54")
    val country: String? get() = valueFor("58")
    val currency: String? get() = valueFor("53")

    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf<LabelledField>()
        for (f in fields) {
            val label: String = when {
                TOP_LEVEL_LABELS.containsKey(f.tag) -> TOP_LEVEL_LABELS[f.tag]!!
                f.tag.toIntOrNull()?.let { it in 2..51 } == true -> {
                    val nested = parseNestedTLV(f.value)
                    val guid = nested.firstOrNull { it.tag == "00" }?.value
                    val scheme = guid?.let { v ->
                        KNOWN_GUIDS.entries.firstOrNull { v.uppercase().contains(it.key) }?.value
                    }
                    if (scheme != null) "$scheme account (${f.tag})"
                    else "Merchant account (${f.tag})"
                }
                else -> "Tag ${f.tag}"
            }

            val display = when (f.tag) {
                "53" -> CURRENCY_CODES[f.value]?.let { "$it (${f.value})" } ?: f.value
                "01" -> INITIATION_METHOD[f.value] ?: f.value
                else -> f.value
            }
            rows += LabelledField(label, display)

            // Drill into known nested-template containers.
            val tagInt = f.tag.toIntOrNull()
            if ((tagInt != null && tagInt in 2..51) || f.tag == "62") {
                val nested = parseNestedTLV(f.value)
                val isAdditionalData = f.tag == "62"
                for (sub in nested) {
                    val subLabel = if (isAdditionalData) {
                        "  ↳ ${ADDITIONAL_DATA_LABELS[sub.tag] ?: "Sub-tag ${sub.tag}"}"
                    } else when (sub.tag) {
                        "00" -> "  ↳ Scheme GUID"
                        "01" -> "  ↳ Identifier"
                        "02" -> "  ↳ Account info"
                        else -> "  ↳ Sub-tag ${sub.tag}"
                    }
                    rows += LabelledField(subLabel, sub.value)
                }
            }
        }
        return rows
    }

    /** Walk a value as a series of TLVs. Returns empty if malformed. */
    private fun parseNestedTLV(value: String): List<EMVField> {
        val out = mutableListOf<EMVField>()
        var i = 0
        while (i < value.length) {
            if (value.length - i < 4) return emptyList()
            val tag = value.substring(i, i + 2)
            val lenStr = value.substring(i + 2, i + 4)
            if (!tag.all { it.isDigit() }) return emptyList()
            val len = lenStr.toIntOrNull() ?: return emptyList()
            if (len < 0) return emptyList()
            val end = i + 4 + len
            if (end > value.length) return emptyList()
            out += EMVField(tag, value.substring(i + 4, end))
            i = end
        }
        return out
    }
}

// ---- Parsers -----------------------------------------------------------

object BankPaymentParser {

    fun parseEPC(raw: String): EPCPaymentPayload? {
        val lines = raw.replace("\r\n", "\n").split('\n')
        if (lines.size < 6 || lines[0] != "BCD") return null
        fun line(i: Int): String? =
            lines.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
        val version = line(1) ?: "001"
        val bic = line(4)
        val name = line(5)
        val iban = line(6)
        val amountRaw = line(7)
        val purpose = line(8)
        val structRef = line(9)
        val unstruct = line(10)
        val beneInfo = line(11)
        var currency: String? = null
        var amount: String? = null
        if (amountRaw != null && amountRaw.length >= 4) {
            currency = amountRaw.substring(0, 3)
            amount = amountRaw.substring(3)
        }
        if (name == null && iban == null) return null
        return EPCPaymentPayload(
            version, bic, name, iban, currency, amount,
            purpose, structRef, unstruct, beneInfo
        )
    }

    fun parseSwissQRBill(raw: String): SwissQRBillPayload? {
        val lines = raw.replace("\r\n", "\n").split('\n')
        if (lines.size < 28 || lines[0] != "SPC") return null
        fun line(i: Int): String? =
            lines.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }

        fun address(base: Int): SwissQRBillAddress? {
            val a = SwissQRBillAddress(
                addressType = line(base),
                name = line(base + 1),
                streetOrLine1 = line(base + 2),
                houseNoOrLine2 = line(base + 3),
                postCode = line(base + 4),
                city = line(base + 5),
                country = line(base + 6)
            )
            return if (a.isEmpty) null else a
        }

        val version = line(1) ?: "0200"
        val iban = line(3)
        val creditor = address(4)
        val ultCred = address(11)
        val amount = line(18)
        val currency = line(19)
        val debtor = address(20)
        val refType = line(27)
        val reference = line(28)
        val message = line(29)
        val billInfo = line(31)

        if (iban == null && creditor?.name == null) return null

        return SwissQRBillPayload(
            version, iban, creditor, ultCred, amount, currency, debtor,
            refType, reference, message, billInfo
        )
    }

    fun parseSerbianSUFReceipt(raw: String): SerbianFiscalReceiptPayload? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val isExact = host == "suf.purs.gov.rs"
        val isSubdomain = host.endsWith(".suf.purs.gov.rs")
        if (!isExact && !isSubdomain) return null
        return SerbianFiscalReceiptPayload(raw)
    }

    fun parseSerbianIPS(raw: String): SerbianIPSPayload? {
        val pieces = raw.split('|')
        val fields = mutableListOf<SerbianIPSField>()
        for (piece in pieces) {
            val colon = piece.indexOf(':')
            if (colon < 0) continue
            val key = piece.substring(0, colon)
            val value = piece.substring(colon + 1)
            if (key.isEmpty()) continue
            fields += SerbianIPSField(key, value)
        }
        val keys = fields.map { it.key }.toSet()
        if ("K" !in keys || "R" !in keys || "V" !in keys) return null
        return SerbianIPSPayload(fields)
    }

    fun looksLikeSerbianIPS(raw: String): Boolean {
        if (!raw.startsWith("K:")) return false
        val head = raw.takeWhile { it != '|' }
        val colon = head.indexOf(':')
        if (colon < 0) return false
        val value = head.substring(colon + 1)
        return value in setOf("PR", "PT", "PK")
    }

    fun parseRussianPayment(raw: String): RussianPaymentPayload? {
        val firstPipe = raw.indexOf('|').takeIf { it >= 0 } ?: return null
        val header = raw.substring(0, firstPipe)
        if (!header.startsWith("ST0001")) return null
        val body = raw.substring(firstPipe + 1)
        val pairs = body.split('|').mapNotNull { field ->
            val eq = field.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
            RussianPaymentField(field.substring(0, eq), field.substring(eq + 1))
        }
        if (pairs.isEmpty()) return null
        return RussianPaymentPayload(header, pairs)
    }

    fun parseFNSReceipt(raw: String): FNSReceiptPayload? {
        val pairs = raw.split('&').mapNotNull { f ->
            val eq = f.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
            f.substring(0, eq) to f.substring(eq + 1)
        }
        val dict = pairs.toMap()
        val t = dict["t"] ?: return null
        if (dict["fn"] == null || dict["fp"] == null) return null
        return FNSReceiptPayload(
            rawTimestamp = t,
            sum = dict["s"],
            fiscalNumber = dict["fn"],
            receiptNumber = dict["i"],
            fiscalSign = dict["fp"],
            receiptType = dict["n"]
        )
    }

    fun looksLikeFNSReceipt(raw: String): Boolean =
        raw.startsWith("t=") && raw.contains("&fn=") && raw.contains("&fp=")

    /** Top-level TLV decoder. */
    fun parseEMV(raw: String): EMVPaymentPayload? {
        if (!raw.startsWith("000201")) return null
        val out = mutableListOf<EMVField>()
        var i = 0
        while (i < raw.length) {
            if (raw.length - i < 4) return null
            val tag = raw.substring(i, i + 2)
            val lenStr = raw.substring(i + 2, i + 4)
            val len = lenStr.toIntOrNull() ?: return null
            if (len < 0) return null
            val end = i + 4 + len
            if (end > raw.length) return null
            out += EMVField(tag, raw.substring(i + 4, end))
            i = end
        }
        if (out.firstOrNull()?.let { it.tag == "00" && it.value == "01" } != true) return null
        return EMVPaymentPayload(out)
    }
}
