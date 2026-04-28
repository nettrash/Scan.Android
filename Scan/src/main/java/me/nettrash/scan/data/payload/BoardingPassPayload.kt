package me.nettrash.scan.data.payload

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Parsed IATA Bar Coded Boarding Pass (RP 1740c, version `M`).
 * Mirrors the iOS [BoardingPassPayload] struct.
 */
data class BoardingPassPayload(
    val passengerName: String,
    val electronicTicket: Boolean,
    val formatCode: Char,
    val numberOfLegs: Int,
    val legs: List<Leg>,
    val raw: String
) {
    data class Leg(
        val pnr: String,
        val from: String,
        val to: String,
        val carrier: String,
        val flightNumber: String,
        val dateJulian: Int?,
        val compartment: String,
        val seat: String,
        val sequenceNumber: String,
        val passengerStatus: String
    )

    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf<LabelledField>()
        rows += LabelledField("Passenger", passengerName)
        rows += LabelledField("E-ticket", if (electronicTicket) "Yes" else "No")
        rows += LabelledField("Legs", numberOfLegs.toString())
        for ((i, leg) in legs.withIndex()) {
            val prefix = if (legs.size > 1) "Leg ${i + 1} — " else ""
            rows += LabelledField("${prefix}PNR", leg.pnr)
            rows += LabelledField("${prefix}From", leg.from)
            rows += LabelledField("${prefix}To", leg.to)
            rows += LabelledField(
                "${prefix}Flight",
                "${leg.carrier} ${leg.flightNumber.trim()}"
            )
            leg.dateJulian?.let {
                rows += LabelledField("${prefix}Date", formatJulian(it))
            }
            rows += LabelledField("${prefix}Cabin", leg.compartment)
            rows += LabelledField("${prefix}Seat", leg.seat.trim())
            rows += LabelledField("${prefix}Sequence", leg.sequenceNumber.trim())
        }
        return rows
    }

    private fun formatJulian(day: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, get(Calendar.YEAR))
            set(Calendar.DAY_OF_YEAR, day)
        }
        val fmt = SimpleDateFormat("dd MMM", Locale.US)
        return fmt.format(cal.time)
    }
}

object BoardingPassParser {

    fun looksLikeBoardingPass(raw: String): Boolean {
        if (raw.length < 60) return false
        if (raw.firstOrNull() != 'M') return false
        if (raw.getOrNull(1)?.isDigit() != true) return false
        // 'E' (electronic ticket indicator) at index 22 is a strong fingerprint.
        return raw[22] == 'E'
    }

    fun parse(raw: String): BoardingPassPayload? {
        if (raw.length < 60 || raw.firstOrNull() != 'M') return null

        fun slice(start: Int, length: Int): String {
            val end = minOf(start + length, raw.length)
            return raw.substring(start, end)
        }

        val formatCode = raw[0]
        val legCount = raw[1].digitToIntOrNull() ?: return null
        val passengerName = slice(2, 20).trim()
        val etktChar = raw[22]
        val pnr = slice(23, 7).trim()
        val from = slice(30, 3)
        val to = slice(33, 3)
        val carrier = slice(36, 3).trim()
        val flightNumber = slice(39, 5)
        val dateJulian = slice(44, 3).trim().toIntOrNull()
        val compartment = slice(47, 1)
        val seat = slice(48, 4)
        val sequence = slice(52, 5)
        val status = slice(57, 1)

        val isAirport: (String) -> Boolean = { s ->
            s.length == 3 && s.all { it.isLetter() && it.isUpperCase() }
        }
        if (!isAirport(from) || !isAirport(to)) return null

        val firstLeg = BoardingPassPayload.Leg(
            pnr = pnr, from = from, to = to,
            carrier = carrier, flightNumber = flightNumber,
            dateJulian = dateJulian, compartment = compartment,
            seat = seat, sequenceNumber = sequence, passengerStatus = status
        )

        return BoardingPassPayload(
            passengerName = passengerName,
            electronicTicket = etktChar == 'E',
            formatCode = formatCode,
            numberOfLegs = legCount,
            legs = listOf(firstLeg),
            raw = raw
        )
    }
}
