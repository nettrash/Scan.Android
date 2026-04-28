package me.nettrash.scan.data.payload

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** A parsed cryptocurrency payment URI (BIP-21 / EIP-681 / BOLT-11). */
data class CryptoPayload(
    val chain: Chain,
    val scheme: String,
    val address: String,
    val amount: String?,
    val label: String?,
    val message: String?,
    val chainId: String?,
    val raw: String
) {
    enum class Chain(val displayName: String) {
        BITCOIN("Bitcoin"),
        ETHEREUM("Ethereum"),
        LITECOIN("Litecoin"),
        BITCOIN_CASH("Bitcoin Cash"),
        DOGECOIN("Dogecoin"),
        MONERO("Monero"),
        CARDANO("Cardano"),
        SOLANA("Solana"),
        LIGHTNING("Lightning"),
        LNURL("LNURL"),
        LIGHTNING_ADDRESS("Lightning Address"),
        RIPPLE("XRP"),
        STELLAR("Stellar"),
        COSMOS("Cosmos"),
        OTHER("Crypto")
    }

    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf<LabelledField>()
        rows += LabelledField("Chain", chain.displayName)
        rows += LabelledField(
            if (chain == Chain.LIGHTNING) "Invoice" else "Address",
            address
        )
        amount?.let { rows += LabelledField("Amount", it) }
        label?.let { rows += LabelledField("Label", it) }
        message?.let { rows += LabelledField("Message", it) }
        chainId?.let { rows += LabelledField("Chain ID", it) }
        return rows
    }
}

object CryptoURIParser {

    private val chainByScheme: Map<String, CryptoPayload.Chain> = mapOf(
        "bitcoin" to CryptoPayload.Chain.BITCOIN,
        "ethereum" to CryptoPayload.Chain.ETHEREUM,
        "litecoin" to CryptoPayload.Chain.LITECOIN,
        "bitcoincash" to CryptoPayload.Chain.BITCOIN_CASH,
        "dogecoin" to CryptoPayload.Chain.DOGECOIN,
        "monero" to CryptoPayload.Chain.MONERO,
        "cardano" to CryptoPayload.Chain.CARDANO,
        "solana" to CryptoPayload.Chain.SOLANA,
        "lightning" to CryptoPayload.Chain.LIGHTNING,
        "ripple" to CryptoPayload.Chain.RIPPLE,
        "xrp" to CryptoPayload.Chain.RIPPLE,
        "xrpl" to CryptoPayload.Chain.RIPPLE,
        "stellar" to CryptoPayload.Chain.STELLAR,
        "web+stellar" to CryptoPayload.Chain.STELLAR,
        "cosmos" to CryptoPayload.Chain.COSMOS
    )

    // ---- Bare-address & Lightning-Address recognition ----

    private val bitcoinAddressRegex =
        Regex("""^([13][a-km-zA-HJ-NP-Z1-9]{25,34}|bc1[02-9ac-hj-np-z]{6,87})$""")
    private val ethereumAddressRegex = Regex("""^0x[a-fA-F0-9]{40}$""")
    private val xrpAddressRegex = Regex("""^r[1-9A-HJ-NP-Za-km-z]{24,34}$""")
    private val stellarAddressRegex = Regex("""^G[A-Z2-7]{55}$""")
    private val cosmosAddressRegex = Regex("""^cosmos1[02-9ac-hj-np-z]{30,50}$""")
    private val bareBolt11Regex =
        Regex("""^ln(bc|tb)[a-z0-9]{50,}$""", RegexOption.IGNORE_CASE)
    private val lnurlRegex = Regex("""^LNURL1[a-z0-9]{50,}$""", RegexOption.IGNORE_CASE)
    private val lightningAddressRegex =
        Regex("""^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$""")

    /**
     * Recognise a *bare* (no scheme) cryptocurrency address or LN token.
     * Returns null when nothing matches.
     */
    fun parseBare(raw: String): CryptoPayload? {
        val s = raw.trim()
        fun build(chain: CryptoPayload.Chain) = CryptoPayload(
            chain = chain, scheme = "", address = s,
            amount = null, label = null, message = null, chainId = null, raw = s
        )
        return when {
            lnurlRegex.matches(s) -> build(CryptoPayload.Chain.LNURL)
            bareBolt11Regex.matches(s) -> build(CryptoPayload.Chain.LIGHTNING)
            bitcoinAddressRegex.matches(s) -> build(CryptoPayload.Chain.BITCOIN)
            ethereumAddressRegex.matches(s) -> build(CryptoPayload.Chain.ETHEREUM)
            xrpAddressRegex.matches(s) -> build(CryptoPayload.Chain.RIPPLE)
            stellarAddressRegex.matches(s) -> build(CryptoPayload.Chain.STELLAR)
            cosmosAddressRegex.matches(s) -> build(CryptoPayload.Chain.COSMOS)
            else -> null
        }
    }

    /** Explicit Lightning Address detection (wins over `.email`). */
    fun parseLightningAddress(raw: String): CryptoPayload? {
        val s = raw.trim()
        if (!lightningAddressRegex.matches(s)) return null
        return CryptoPayload(
            chain = CryptoPayload.Chain.LIGHTNING_ADDRESS, scheme = "",
            address = s, amount = null, label = null, message = null,
            chainId = null, raw = s
        )
    }

    val knownSchemes: Set<String>
        get() = chainByScheme.keys

    fun parse(raw: String): CryptoPayload? {
        val colon = raw.indexOf(':').takeIf { it >= 0 } ?: return null
        val scheme = raw.substring(0, colon).lowercase(Locale.ROOT)
        val chain = chainByScheme[scheme] ?: return null
        var body = raw.substring(colon + 1)

        // Lightning: the remainder is the bolt11 invoice. No params.
        if (chain == CryptoPayload.Chain.LIGHTNING) {
            val invoice = body.trim()
            if (invoice.isEmpty()) return null
            return CryptoPayload(
                chain = CryptoPayload.Chain.LIGHTNING,
                scheme = scheme,
                address = invoice,
                amount = null, label = null, message = null, chainId = null,
                raw = raw
            )
        }

        // Some non-conforming wallets emit `scheme://address?…`; tolerate that.
        if (body.startsWith("//")) body = body.substring(2)

        val q = body.indexOf('?')
        val path = if (q >= 0) body.substring(0, q) else body
        val query = if (q >= 0) body.substring(q + 1) else ""

        var address = path
        var chainId: String? = null
        if (chain == CryptoPayload.Chain.ETHEREUM) {
            val at = path.indexOf('@')
            if (at >= 0) {
                address = path.substring(0, at)
                chainId = path.substring(at + 1).split('/').firstOrNull()
            }
        }
        if (address.isEmpty()) return null

        var amount: String? = null
        var label: String? = null
        var message: String? = null
        for (pair in query.split('&')) {
            val parts = pair.split('=', limit = 2)
            if (parts.size != 2) continue
            val k = parts[0].lowercase(Locale.ROOT)
            val v = decode(parts[1])
            when (k) {
                "amount", "value" -> amount = v
                "label" -> label = v
                "message" -> message = v
            }
        }

        return CryptoPayload(
            chain = chain,
            scheme = scheme,
            address = address,
            amount = amount,
            label = label,
            message = message,
            chainId = chainId,
            raw = raw
        )
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, StandardCharsets.UTF_8.name()) }.getOrDefault(s)
}
