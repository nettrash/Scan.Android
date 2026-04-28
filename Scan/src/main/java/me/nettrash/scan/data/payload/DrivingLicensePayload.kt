package me.nettrash.scan.data.payload

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Parsed AAMVA driver's-licence PDF417 payload. Mirrors the iOS
 * [DrivingLicensePayload] struct.
 */
data class DrivingLicensePayload(
    val issuerIIN: String?,
    val issuerName: String?,
    val licenseNumber: String?,
    val firstName: String?,
    val middleName: String?,
    val lastName: String?,
    val dateOfBirth: Date?,
    val expiry: Date?,
    val issueDate: Date?,
    val sex: String?,
    val address: String?,
    val city: String?,
    val state: String?,
    val postalCode: String?,
    val raw: String
) {
    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf<LabelledField>()
        when {
            !issuerName.isNullOrEmpty() -> rows += LabelledField("Issuer", issuerName)
            !issuerIIN.isNullOrEmpty()  -> rows += LabelledField("Issuer IIN", issuerIIN)
        }
        val fullName = listOfNotNull(firstName, middleName, lastName)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (fullName.isNotEmpty()) rows += LabelledField("Name", fullName)
        licenseNumber?.let { rows += LabelledField("License #", it) }
        dateOfBirth?.let { rows += LabelledField("Date of birth", formatDate(it)) }
        expiry?.let       { rows += LabelledField("Expires", formatDate(it)) }
        issueDate?.let    { rows += LabelledField("Issued", formatDate(it)) }
        sex?.let          { rows += LabelledField("Sex", it) }
        val addressLine = listOfNotNull(address, city, state, postalCode)
            .filter { it.isNotEmpty() }
            .joinToString(", ")
        if (addressLine.isNotEmpty()) rows += LabelledField("Address", addressLine)
        return rows
    }

    private fun formatDate(d: Date): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(d)
}

object DrivingLicenseParser {

    fun looksLikeAAMVA(raw: String): Boolean {
        if (raw.firstOrNull() != '@') return false
        val lower = raw.lowercase(Locale.ROOT)
        return lower.contains("\nansi ") || lower.contains("\naamva ")
            || lower.contains("ansi ") || lower.contains("aamva ")
    }

    fun parse(raw: String): DrivingLicensePayload? {
        if (!looksLikeAAMVA(raw)) return null

        val iin = extractIIN(raw)
        val elements = parseElements(raw)

        val isCanada: Boolean = run {
            val dba = elements["DBA"] ?: return@run false
            dba.length == 8 && (dba.take(4).toIntOrNull() ?: 0) > 1900
        }

        fun parseDate(s: String?): Date? {
            if (s == null || s.length != 8) return null
            val fmt = SimpleDateFormat(
                if (isCanada) "yyyyMMdd" else "MMddyyyy",
                Locale.US
            ).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return runCatching { fmt.parse(s) }.getOrNull()
        }

        val sex: String? = when (elements["DBC"]) {
            "1" -> "Male"
            "2" -> "Female"
            "9" -> "Not specified"
            null -> null
            else -> elements["DBC"]
        }

        return DrivingLicensePayload(
            issuerIIN = iin,
            issuerName = iin?.let { jurisdictionName(it) },
            licenseNumber = elements["DAQ"],
            firstName = elements["DAC"] ?: elements["DCT"],
            middleName = elements["DAD"],
            lastName = elements["DCS"] ?: elements["DAB"],
            dateOfBirth = parseDate(elements["DBB"]),
            expiry = parseDate(elements["DBA"]),
            issueDate = parseDate(elements["DBD"]),
            sex = sex,
            address = elements["DAG"],
            city = elements["DAI"],
            state = elements["DAJ"],
            postalCode = elements["DAK"],
            raw = raw
        )
    }

    private fun extractIIN(raw: String): String? {
        val lower = raw.lowercase(Locale.ROOT)
        for (marker in listOf("ansi ", "aamva ")) {
            val idx = lower.indexOf(marker)
            if (idx >= 0) {
                val after = idx + marker.length
                if (after + 6 <= raw.length) {
                    val candidate = raw.substring(after, after + 6)
                    if (candidate.all { it.isDigit() }) return candidate
                }
            }
        }
        return null
    }

    private fun parseElements(raw: String): Map<String, String> {
        val terminators = setOf('\n', '\r', '', '')
        val result = LinkedHashMap<String, String>()
        val chars = raw
        var i = 0
        while (i < chars.length) {
            if (i + 3 < chars.length &&
                chars[i] == 'D' &&
                chars[i + 1].isLetter() && chars[i + 1].isUpperCase() &&
                chars[i + 2].isLetter() && chars[i + 2].isUpperCase()
            ) {
                val atStart = i == 0
                val afterTerminator = i > 0 && terminators.contains(chars[i - 1])
                if (!(atStart || afterTerminator)) { i++; continue }
                val id = chars.substring(i, i + 3)
                var j = i + 3
                while (j < chars.length && !terminators.contains(chars[j])) j++
                val value = chars.substring(i + 3, j).trim()
                if (value.isNotEmpty() && !result.containsKey(id)) {
                    result[id] = value
                }
                i = j + 1
            } else {
                i++
            }
        }
        return result
    }

    private fun jurisdictionName(iin: String): String? = when (iin) {
        "636014" -> "California"
        "636015" -> "Texas"
        "636001" -> "Alabama"
        "636025" -> "Indiana"
        "636026" -> "South Carolina"
        "636017" -> "Wisconsin"
        "636018" -> "Wyoming"
        "636020" -> "Iowa"
        "636021" -> "Massachusetts"
        "636030" -> "Tennessee"
        "636032" -> "New Mexico"
        "636034" -> "Oregon"
        "636035" -> "South Dakota"
        "636036" -> "Pennsylvania"
        "636038" -> "Mississippi"
        "636039" -> "Vermont"
        "636042" -> "Rhode Island"
        "636043" -> "Hawaii"
        "636045" -> "New Hampshire"
        "636046" -> "Maine"
        "636049" -> "Idaho"
        "636050" -> "Montana"
        "636051" -> "Nebraska"
        "636052" -> "Nevada"
        "636053" -> "Arizona"
        "636054" -> "Connecticut"
        "636055" -> "Florida"
        "636056" -> "Illinois"
        "636057" -> "Washington"
        "636058" -> "Oklahoma"
        "636059" -> "Maryland"
        "636060" -> "Kentucky"
        "636062" -> "Virginia"
        "636067" -> "Kansas"
        "636068" -> "Minnesota"
        "636069" -> "Michigan"
        "636070" -> "New Jersey"
        "636071" -> "New York"
        "636072" -> "North Carolina"
        "636074" -> "North Dakota"
        "636075" -> "Ohio"
        "636016" -> "Ontario"
        "636028" -> "British Columbia"
        "636023" -> "Saskatchewan"
        "636040" -> "Alberta"
        "636044" -> "Quebec"
        "636047" -> "Manitoba"
        "636048" -> "New Brunswick"
        "636066" -> "Nova Scotia"
        "636019" -> "Prince Edward Island"
        "636037" -> "Newfoundland and Labrador"
        else -> null
    }
}
