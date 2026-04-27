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
        "lightning" to CryptoPayload.Chain.LIGHTNING
    )

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
