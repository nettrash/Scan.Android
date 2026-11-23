package me.nettrash.scan.data.payload

import androidx.core.net.toUri

/**
 * The EU Digital Product Passport is arriving on a data carrier most people
 * already own a reader for: a GS1 DataMatrix or QR carrying GS1 Digital
 * Link AIs. Textiles, batteries, steel and electronics are scheduled to
 * need one first, and the consumer-facing question is always the same short
 * list — what is it, which batch, when does it expire, where is it from.
 *
 * Scan reads the *carrier*: everything encoded in the code itself. The
 * passport record proper lives behind the resolver URL, and fetching it
 * would mean sending the serial number of the thing in your hand to a
 * server, so we show what the code says and where it would resolve, and no
 * further.
 *
 * "Where is it from" is GS1's country family (General Specifications,
 * AIs 422–426): 422 the country of origin, 423 initial processing, 424
 * processing, 425 disassembly, 426 the country covering the full process
 * chain. AI 421 is not one of them — it is a *ship-to* postal code
 * prefixed with the destination's country code.
 *
 * Mirrors the iOS [DigitalProductPassportPayload] struct.
 */
data class DigitalProductPassportPayload(
    val gtin: String?,
    val batchLot: String?,
    val serial: String?,
    val expiry: String?,
    val production: String?,
    /** ISO 3166-1 numeric country of origin — AI 422. */
    val originNumeric: String?,
    /** AI 423 — up to five countries of initial processing, three digits each. */
    val initialProcessingNumeric: String?,
    /** AI 424 — the country of processing. */
    val processingNumeric: String?,
    /** AI 425 — up to five countries of disassembly, three digits each. */
    val disassemblyNumeric: String?,
    /** AI 426 — the one country covering the full process chain. */
    val fullProcessNumeric: String?,
    /**
     * Host the Digital Link resolver would be contacted at — shown, never
     * contacted. [ShownHost.UNUSUAL] when the link hides its host.
     */
    val resolverHost: String?,
    /** The raw carrier, for copy / share. */
    val raw: String,
    /**
     * Every element the carrier holds, in source order — the passport rows
     * show some of them, and the rest must not vanish.
     */
    val elements: List<GS1Payload.Element>,
) {
    val originDescription: String?
        get() = originNumeric?.let { ISOCountry.name(it) }

    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf(LabelledField("Format", "GS1 Digital Product Passport carrier"))
        gtin?.let { rows += LabelledField("Product (GTIN)", it) }
        batchLot?.let { rows += LabelledField("Batch / lot", it) }
        serial?.let { rows += LabelledField("Serial", it) }
        expiry?.let { rows += LabelledField("Expiry", it) }
        production?.let { rows += LabelledField("Produced", it) }
        originDescription?.let { rows += LabelledField("Country of origin", it) }
        initialProcessingNumeric?.let { ISOCountry.names(it) }
            ?.let { rows += LabelledField("Country of initial processing", it) }
        processingNumeric?.let { ISOCountry.name(it) }?.let { rows += LabelledField("Country of processing", it) }
        disassemblyNumeric?.let { ISOCountry.names(it) }?.let { rows += LabelledField("Country of disassembly", it) }
        fullProcessNumeric?.let { ISOCountry.name(it) }
            ?.let { rows += LabelledField("Country covering full process chain", it) }
        resolverHost?.let { rows += LabelledField("Passport hosted at", it) }
        // Everything else the carrier holds — best before, net weight, an
        // approval number — as the plain GS1 sheet shows it.
        rows += GS1Payload(GS1Payload.Form.PARENS, elements.filter { it.ai !in PASSPORT_AIS }, raw).labelledFields()
        rows += LabelledField("Read from", "the code itself — nothing was fetched")
        return rows
    }

    private companion object {
        /** The AIs the passport rows already show. */
        val PASSPORT_AIS = setOf("01", "10", "21", "17", "11", "422", "423", "424", "425", "426")
    }
}

object DigitalProductPassportParser {

    /**
     * DPP markers on a GS1 payload: a Digital Link with a `dpp` path
     * segment or query parameter (ASCII, any case), or a passport-shaped
     * bundle of AIs (origin + date + batch/serial alongside a GTIN). The
     * origin is AI 422, or AI 426 — which GS1 uses *instead of* 422 when
     * one country covers the whole process chain.
     */
    fun looksLikePassport(payload: GS1Payload): Boolean {
        if (hasDPPMarker(payload)) return true
        if (payload.gtin == null) return false
        val hasOrigin = payload.valueFor("422") != null || payload.valueFor("426") != null
        val hasDate = payload.expiry != null || payload.production != null
        val hasUnit = payload.batchLot != null || payload.serial != null
        return hasOrigin && hasDate && hasUnit
    }

    fun parse(payload: GS1Payload): DigitalProductPassportPayload {
        val resolver = digitalLinkHost(payload.raw)
        return DigitalProductPassportPayload(
            gtin = payload.gtin,
            batchLot = payload.batchLot,
            serial = payload.serial,
            expiry = payload.expiry?.let { GS1Registry.formatValue("17", it) },
            production = payload.production?.let { GS1Registry.formatValue("11", it) },
            originNumeric = countryCodes(payload.valueFor("422"), max = 1),
            initialProcessingNumeric = countryCodes(payload.valueFor("423"), max = 5),
            processingNumeric = countryCodes(payload.valueFor("424"), max = 1),
            disassemblyNumeric = countryCodes(payload.valueFor("425"), max = 5),
            fullProcessNumeric = countryCodes(payload.valueFor("426"), max = 1),
            resolverHost = resolver,
            raw = payload.raw,
            elements = payload.elements,
        )
    }

    /**
     * A `dpp` path segment that is not an AI's value, or a `dpp` query
     * parameter, in a Digital Link — read off the same split
     * [GS1Parser]'s Digital Link walk uses. Never a substring of the raw
     * text: a lot `ADPPX`, a serial or a host name `dppnews.example` would
     * turn an ordinary product code into a passport. Mirrors iOS
     * `hasDPPMarker`.
     */
    private fun hasDPPMarker(payload: GS1Payload): Boolean {
        if (payload.form != GS1Payload.Form.DIGITAL_LINK) return false
        val uri = runCatching { payload.raw.toUri() }.getOrNull() ?: return false
        val segments = runCatching { uri.path }.getOrNull().orEmpty().split('/').filter { it.isNotEmpty() }
        var idx = 0
        while (idx < segments.size) {
            val segment = segments[idx]
            if (idx + 1 < segments.size && segment.all { it.isDigit() } && GS1Registry.nameFor(segment) != null) {
                idx += 2                        // an AI and its value
                continue
            }
            if (isDPP(segment)) return true
            idx += 1
        }
        val names = runCatching { uri.queryParameterNames }.getOrNull().orEmpty()
        return names.any { isDPP(it) }
    }

    private fun isDPP(s: String): Boolean =
        s.length == 3 && s.map { if (it in 'A'..'Z') it + 32 else it }.joinToString("") == "dpp"

    /**
     * An AI 422–426 value: one (N3) or up to five (N3+N..12) ISO 3166-1
     * numeric codes, back to back. Anything else is not a country code.
     */
    private fun countryCodes(value: String?, max: Int): String? {
        if (value.isNullOrEmpty() || value.length % 3 != 0 || value.length > 3 * max) return null
        return if (value.all { it in '0'..'9' }) value else null
    }

    /**
     * The resolver's host, by the rule the credential sheet uses
     * ([ShownHost]), for an `http(s)` link in any letter case.
     */
    private fun digitalLinkHost(raw: String): String? {
        if (!raw.startsWith("http://", ignoreCase = true) && !raw.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return ShownHost.of(raw)
    }
}

/**
 * ISO 3166-1 numeric → display name. GS1 carries the numeric form, and a
 * product can come from anywhere, so this is the whole current list: all
 * 249 codes, one row each, in numeric order.
 *
 * Every row is the ISO assignment for its code: a wrong row is worse than
 * a missing one, because it prints a confident, false origin. The rows were
 * generated, not typed: codes and alpha-3 from ICU's region data
 * (`uloc_getISOCountries`, `uregion_getNumericCode`, `uloc_getISO3Country`),
 * checked against the UN M49 list (which leaves out 158, Taiwan) and the
 * JDK's; names are CLDR's English ones, where Apple's ICU swaps in its own
 * for five territories. The iOS table is identical.
 */
object ISOCountry {

    fun name(numeric: String): String? {
        val entry = table[numeric] ?: return null
        return "${entry.second} (${entry.first})"
    }

    /**
     * Back-to-back three-digit codes (AIs 423 and 425), every one named,
     * or null: a list with a country left out would be a false answer.
     */
    fun names(numericList: String): String? {
        if (numericList.isEmpty() || numericList.length % 3 != 0) return null
        return numericList.chunked(3).map { name(it) ?: return null }.joinToString(", ")
    }

    private val table: Map<String, Pair<String, String>> = mapOf(
        "004" to ("AFG" to "Afghanistan"),
        "008" to ("ALB" to "Albania"),
        "010" to ("ATA" to "Antarctica"),
        "012" to ("DZA" to "Algeria"),
        "016" to ("ASM" to "American Samoa"),
        "020" to ("AND" to "Andorra"),
        "024" to ("AGO" to "Angola"),
        "028" to ("ATG" to "Antigua & Barbuda"),
        "031" to ("AZE" to "Azerbaijan"),
        "032" to ("ARG" to "Argentina"),
        "036" to ("AUS" to "Australia"),
        "040" to ("AUT" to "Austria"),
        "044" to ("BHS" to "Bahamas"),
        "048" to ("BHR" to "Bahrain"),
        "050" to ("BGD" to "Bangladesh"),
        "051" to ("ARM" to "Armenia"),
        "052" to ("BRB" to "Barbados"),
        "056" to ("BEL" to "Belgium"),
        "060" to ("BMU" to "Bermuda"),
        "064" to ("BTN" to "Bhutan"),
        "068" to ("BOL" to "Bolivia"),
        "070" to ("BIH" to "Bosnia & Herzegovina"),
        "072" to ("BWA" to "Botswana"),
        "074" to ("BVT" to "Bouvet Island"),
        "076" to ("BRA" to "Brazil"),
        "084" to ("BLZ" to "Belize"),
        "086" to ("IOT" to "British Indian Ocean Territory"),
        "090" to ("SLB" to "Solomon Islands"),
        "092" to ("VGB" to "British Virgin Islands"),
        "096" to ("BRN" to "Brunei"),
        "100" to ("BGR" to "Bulgaria"),
        "104" to ("MMR" to "Myanmar (Burma)"),
        "108" to ("BDI" to "Burundi"),
        "112" to ("BLR" to "Belarus"),
        "116" to ("KHM" to "Cambodia"),
        "120" to ("CMR" to "Cameroon"),
        "124" to ("CAN" to "Canada"),
        "132" to ("CPV" to "Cape Verde"),
        "136" to ("CYM" to "Cayman Islands"),
        "140" to ("CAF" to "Central African Republic"),
        "144" to ("LKA" to "Sri Lanka"),
        "148" to ("TCD" to "Chad"),
        "152" to ("CHL" to "Chile"),
        "156" to ("CHN" to "China"),
        "158" to ("TWN" to "Taiwan"),
        "162" to ("CXR" to "Christmas Island"),
        "166" to ("CCK" to "Cocos (Keeling) Islands"),
        "170" to ("COL" to "Colombia"),
        "174" to ("COM" to "Comoros"),
        "175" to ("MYT" to "Mayotte"),
        "178" to ("COG" to "Congo - Brazzaville"),
        "180" to ("COD" to "Congo - Kinshasa"),
        "184" to ("COK" to "Cook Islands"),
        "188" to ("CRI" to "Costa Rica"),
        "191" to ("HRV" to "Croatia"),
        "192" to ("CUB" to "Cuba"),
        "196" to ("CYP" to "Cyprus"),
        "203" to ("CZE" to "Czechia"),
        "204" to ("BEN" to "Benin"),
        "208" to ("DNK" to "Denmark"),
        "212" to ("DMA" to "Dominica"),
        "214" to ("DOM" to "Dominican Republic"),
        "218" to ("ECU" to "Ecuador"),
        "222" to ("SLV" to "El Salvador"),
        "226" to ("GNQ" to "Equatorial Guinea"),
        "231" to ("ETH" to "Ethiopia"),
        "232" to ("ERI" to "Eritrea"),
        "233" to ("EST" to "Estonia"),
        "234" to ("FRO" to "Faroe Islands"),
        "238" to ("FLK" to "Falkland Islands"),
        "239" to ("SGS" to "South Georgia & South Sandwich Islands"),
        "242" to ("FJI" to "Fiji"),
        "246" to ("FIN" to "Finland"),
        "248" to ("ALA" to "Åland Islands"),
        "250" to ("FRA" to "France"),
        "254" to ("GUF" to "French Guiana"),
        "258" to ("PYF" to "French Polynesia"),
        "260" to ("ATF" to "French Southern Territories"),
        "262" to ("DJI" to "Djibouti"),
        "266" to ("GAB" to "Gabon"),
        "268" to ("GEO" to "Georgia"),
        "270" to ("GMB" to "Gambia"),
        "275" to ("PSE" to "Palestinian Territories"),
        "276" to ("DEU" to "Germany"),
        "288" to ("GHA" to "Ghana"),
        "292" to ("GIB" to "Gibraltar"),
        "296" to ("KIR" to "Kiribati"),
        "300" to ("GRC" to "Greece"),
        "304" to ("GRL" to "Greenland"),
        "308" to ("GRD" to "Grenada"),
        "312" to ("GLP" to "Guadeloupe"),
        "316" to ("GUM" to "Guam"),
        "320" to ("GTM" to "Guatemala"),
        "324" to ("GIN" to "Guinea"),
        "328" to ("GUY" to "Guyana"),
        "332" to ("HTI" to "Haiti"),
        "334" to ("HMD" to "Heard & McDonald Islands"),
        "336" to ("VAT" to "Vatican City"),
        "340" to ("HND" to "Honduras"),
        "344" to ("HKG" to "Hong Kong SAR China"),
        "348" to ("HUN" to "Hungary"),
        "352" to ("ISL" to "Iceland"),
        "356" to ("IND" to "India"),
        "360" to ("IDN" to "Indonesia"),
        "364" to ("IRN" to "Iran"),
        "368" to ("IRQ" to "Iraq"),
        "372" to ("IRL" to "Ireland"),
        "376" to ("ISR" to "Israel"),
        "380" to ("ITA" to "Italy"),
        "384" to ("CIV" to "Côte d’Ivoire"),
        "388" to ("JAM" to "Jamaica"),
        "392" to ("JPN" to "Japan"),
        "398" to ("KAZ" to "Kazakhstan"),
        "400" to ("JOR" to "Jordan"),
        "404" to ("KEN" to "Kenya"),
        "408" to ("PRK" to "North Korea"),
        "410" to ("KOR" to "South Korea"),
        "414" to ("KWT" to "Kuwait"),
        "417" to ("KGZ" to "Kyrgyzstan"),
        "418" to ("LAO" to "Laos"),
        "422" to ("LBN" to "Lebanon"),
        "426" to ("LSO" to "Lesotho"),
        "428" to ("LVA" to "Latvia"),
        "430" to ("LBR" to "Liberia"),
        "434" to ("LBY" to "Libya"),
        "438" to ("LIE" to "Liechtenstein"),
        "440" to ("LTU" to "Lithuania"),
        "442" to ("LUX" to "Luxembourg"),
        "446" to ("MAC" to "Macao SAR China"),
        "450" to ("MDG" to "Madagascar"),
        "454" to ("MWI" to "Malawi"),
        "458" to ("MYS" to "Malaysia"),
        "462" to ("MDV" to "Maldives"),
        "466" to ("MLI" to "Mali"),
        "470" to ("MLT" to "Malta"),
        "474" to ("MTQ" to "Martinique"),
        "478" to ("MRT" to "Mauritania"),
        "480" to ("MUS" to "Mauritius"),
        "484" to ("MEX" to "Mexico"),
        "492" to ("MCO" to "Monaco"),
        "496" to ("MNG" to "Mongolia"),
        "498" to ("MDA" to "Moldova"),
        "499" to ("MNE" to "Montenegro"),
        "500" to ("MSR" to "Montserrat"),
        "504" to ("MAR" to "Morocco"),
        "508" to ("MOZ" to "Mozambique"),
        "512" to ("OMN" to "Oman"),
        "516" to ("NAM" to "Namibia"),
        "520" to ("NRU" to "Nauru"),
        "524" to ("NPL" to "Nepal"),
        "528" to ("NLD" to "Netherlands"),
        "531" to ("CUW" to "Curaçao"),
        "533" to ("ABW" to "Aruba"),
        "534" to ("SXM" to "Sint Maarten"),
        "535" to ("BES" to "Caribbean Netherlands"),
        "540" to ("NCL" to "New Caledonia"),
        "548" to ("VUT" to "Vanuatu"),
        "554" to ("NZL" to "New Zealand"),
        "558" to ("NIC" to "Nicaragua"),
        "562" to ("NER" to "Niger"),
        "566" to ("NGA" to "Nigeria"),
        "570" to ("NIU" to "Niue"),
        "574" to ("NFK" to "Norfolk Island"),
        "578" to ("NOR" to "Norway"),
        "580" to ("MNP" to "Northern Mariana Islands"),
        "581" to ("UMI" to "U.S. Outlying Islands"),
        "583" to ("FSM" to "Micronesia"),
        "584" to ("MHL" to "Marshall Islands"),
        "585" to ("PLW" to "Palau"),
        "586" to ("PAK" to "Pakistan"),
        "591" to ("PAN" to "Panama"),
        "598" to ("PNG" to "Papua New Guinea"),
        "600" to ("PRY" to "Paraguay"),
        "604" to ("PER" to "Peru"),
        "608" to ("PHL" to "Philippines"),
        "612" to ("PCN" to "Pitcairn Islands"),
        "616" to ("POL" to "Poland"),
        "620" to ("PRT" to "Portugal"),
        "624" to ("GNB" to "Guinea-Bissau"),
        "626" to ("TLS" to "Timor-Leste"),
        "630" to ("PRI" to "Puerto Rico"),
        "634" to ("QAT" to "Qatar"),
        "638" to ("REU" to "Réunion"),
        "642" to ("ROU" to "Romania"),
        "643" to ("RUS" to "Russia"),
        "646" to ("RWA" to "Rwanda"),
        "652" to ("BLM" to "St. Barthélemy"),
        "654" to ("SHN" to "St. Helena"),
        "659" to ("KNA" to "St. Kitts & Nevis"),
        "660" to ("AIA" to "Anguilla"),
        "662" to ("LCA" to "St. Lucia"),
        "663" to ("MAF" to "St. Martin"),
        "666" to ("SPM" to "St. Pierre & Miquelon"),
        "670" to ("VCT" to "St. Vincent & Grenadines"),
        "674" to ("SMR" to "San Marino"),
        "678" to ("STP" to "São Tomé & Príncipe"),
        "682" to ("SAU" to "Saudi Arabia"),
        "686" to ("SEN" to "Senegal"),
        "688" to ("SRB" to "Serbia"),
        "690" to ("SYC" to "Seychelles"),
        "694" to ("SLE" to "Sierra Leone"),
        "702" to ("SGP" to "Singapore"),
        "703" to ("SVK" to "Slovakia"),
        "704" to ("VNM" to "Vietnam"),
        "705" to ("SVN" to "Slovenia"),
        "706" to ("SOM" to "Somalia"),
        "710" to ("ZAF" to "South Africa"),
        "716" to ("ZWE" to "Zimbabwe"),
        "724" to ("ESP" to "Spain"),
        "728" to ("SSD" to "South Sudan"),
        "729" to ("SDN" to "Sudan"),
        "732" to ("ESH" to "Western Sahara"),
        "740" to ("SUR" to "Suriname"),
        "744" to ("SJM" to "Svalbard & Jan Mayen"),
        "748" to ("SWZ" to "Eswatini"),
        "752" to ("SWE" to "Sweden"),
        "756" to ("CHE" to "Switzerland"),
        "760" to ("SYR" to "Syria"),
        "762" to ("TJK" to "Tajikistan"),
        "764" to ("THA" to "Thailand"),
        "768" to ("TGO" to "Togo"),
        "772" to ("TKL" to "Tokelau"),
        "776" to ("TON" to "Tonga"),
        "780" to ("TTO" to "Trinidad & Tobago"),
        "784" to ("ARE" to "United Arab Emirates"),
        "788" to ("TUN" to "Tunisia"),
        "792" to ("TUR" to "Türkiye"),
        "795" to ("TKM" to "Turkmenistan"),
        "796" to ("TCA" to "Turks & Caicos Islands"),
        "798" to ("TUV" to "Tuvalu"),
        "800" to ("UGA" to "Uganda"),
        "804" to ("UKR" to "Ukraine"),
        "807" to ("MKD" to "North Macedonia"),
        "818" to ("EGY" to "Egypt"),
        "826" to ("GBR" to "United Kingdom"),
        "831" to ("GGY" to "Guernsey"),
        "832" to ("JEY" to "Jersey"),
        "833" to ("IMN" to "Isle of Man"),
        "834" to ("TZA" to "Tanzania"),
        "840" to ("USA" to "United States"),
        "850" to ("VIR" to "U.S. Virgin Islands"),
        "854" to ("BFA" to "Burkina Faso"),
        "858" to ("URY" to "Uruguay"),
        "860" to ("UZB" to "Uzbekistan"),
        "862" to ("VEN" to "Venezuela"),
        "876" to ("WLF" to "Wallis & Futuna"),
        "882" to ("WSM" to "Samoa"),
        "887" to ("YEM" to "Yemen"),
        "894" to ("ZMB" to "Zambia"),
    )
}
