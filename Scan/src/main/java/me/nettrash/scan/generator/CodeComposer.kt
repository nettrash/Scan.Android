package me.nettrash.scan.generator

/**
 * Builds well-formed payload strings from structured user input — vCard
 * 3.0 contacts, WIFI: blobs, etc. The output of these composers is what
 * feeds [CodeGenerator.bitmap].
 */
object CodeComposer {

    // ---- vCard 3.0 ----------------------------------------------------

    /**
     * Build a minimal vCard 3.0 string. Only non-empty fields are emitted.
     * The line separator is CRLF, per RFC 2425/2426.
     */
    fun vCard(
        fullName: String,
        phone: String? = null,
        email: String? = null,
        organization: String? = null,
        url: String? = null
    ): String {
        val lines = mutableListOf("BEGIN:VCARD", "VERSION:3.0")

        val fn = fullName.trim()
        if (fn.isNotEmpty()) {
            lines += "FN:${escapeVCard(fn)}"
            val space = fn.indexOf(' ')
            if (space > 0) {
                val given = fn.substring(0, space)
                val family = fn.substring(space + 1)
                lines += "N:${escapeVCard(family)};${escapeVCard(given)};;;"
            } else {
                lines += "N:;${escapeVCard(fn)};;;"
            }
        }

        phone?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += "TEL;TYPE=CELL:$it" }
        email?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += "EMAIL:$it" }
        organization?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += "ORG:${escapeVCard(it)}" }
        url?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += "URL:$it" }

        lines += "END:VCARD"
        return lines.joinToString("\r\n")
    }

    private fun escapeVCard(s: String): String {
        val out = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '\\' -> out.append("\\\\")
                ',' -> out.append("\\,")
                ';' -> out.append("\\;")
                '\n' -> out.append("\\n")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    // ---- Wi-Fi --------------------------------------------------------

    enum class WifiSecurity(val rawValue: String, val displayName: String) {
        WPA("WPA", "WPA"),
        WEP("WEP", "WEP"),
        OPEN("nopass", "None")
    }

    /**
     * Build a WIFI: payload per the de facto standard documented at
     * https://en.wikipedia.org/wiki/QR_code#Wi-Fi_network_login
     * Special characters in SSID/password are backslash-escaped.
     */
    fun wifi(
        ssid: String,
        password: String? = null,
        security: WifiSecurity = WifiSecurity.WPA,
        hidden: Boolean = false
    ): String {
        val fields = mutableListOf<String>()
        fields += "T:${security.rawValue}"
        fields += "S:${escapeWifi(ssid)}"
        if (security != WifiSecurity.OPEN && !password.isNullOrEmpty()) {
            fields += "P:${escapeWifi(password)}"
        }
        if (hidden) fields += "H:true"
        return "WIFI:" + fields.joinToString(";") + ";;"
    }

    private fun escapeWifi(s: String): String {
        val out = StringBuilder(s.length)
        for (ch in s) {
            if (ch in "\\;,:\"") out.append('\\')
            out.append(ch)
        }
        return out.toString()
    }
}
