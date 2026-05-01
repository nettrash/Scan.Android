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
    val raw: String,
    /** New in 1.4. Token context for ERC-20 / TRC-20 / SPL transfers
     *  — null for native-asset payments. Mirrors iOS's `CryptoPayload.Token`. */
    val token: Token? = null,
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
        /** New in 1.4. TRON's mainnet — host of TRC-20 stablecoins. */
        TRON("TRON"),
        OTHER("Crypto")
    }

    /**
     * Token context for non-native transfers — ERC-20 (Ethereum),
     * TRC-20 (Tron), SPL (Solana). When recognised against the
     * built-in registry [CryptoURIParser.knownTokens] the symbol
     * resolves to "USDC" / "USDT" / "DAI"; otherwise we fall back
     * to a generic "ERC-20" / "TRC-20" / "SPL" tag with the contract
     * address baked into [contract].
     */
    data class Token(
        val symbol: String,
        val contract: String,
        val chain: Chain,
    )

    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf<LabelledField>()
        // Token-aware header. "USDC on Ethereum" reads better than a
        // bare contract address; mirrors iOS exactly.
        if (token != null) {
            rows += LabelledField("Token", "${token.symbol} on ${token.chain.displayName}")
            rows += LabelledField("Contract", token.contract)
        } else {
            rows += LabelledField("Chain", chain.displayName)
        }
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
        "cosmos" to CryptoPayload.Chain.COSMOS,
        "tron" to CryptoPayload.Chain.TRON,
        "tronlink" to CryptoPayload.Chain.TRON,
    )

    /**
     * Well-known stablecoin contracts. Keys are lowercased so lookups
     * are case-insensitive (Ethereum addresses are checksum-cased and
     * Solana mints are base58-cased). Mirrors `knownTokens` on iOS.
     */
    internal val knownTokens: Map<String, CryptoPayload.Token> = mapOf(
        // ERC-20 — Ethereum mainnet
        "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" to
            CryptoPayload.Token("USDC", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", CryptoPayload.Chain.ETHEREUM),
        "0xdac17f958d2ee523a2206206994597c13d831ec7" to
            CryptoPayload.Token("USDT", "0xdAC17F958D2ee523a2206206994597C13D831ec7", CryptoPayload.Chain.ETHEREUM),
        "0x6b175474e89094c44da98b954eedeac495271d0f" to
            CryptoPayload.Token("DAI",  "0x6B175474E89094C44Da98b954EedeAC495271d0F", CryptoPayload.Chain.ETHEREUM),

        // TRC-20 — Tron mainnet
        "tr7nhqjekqxgtci8q8zy4pl8otszgjlj6t" to
            CryptoPayload.Token("USDT", "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", CryptoPayload.Chain.TRON),
        "thb4cqifcwoyalsl6bwuthba5krshxtrjq" to
            CryptoPayload.Token("USDC", "THb4CqiFcwoyaL5L6bWuThbA5krsHXtrJq", CryptoPayload.Chain.TRON),

        // SPL — Solana mainnet
        "epjfwdd5aufqssqem2qn1xzybapc8g4weggkzwytdt1v" to
            CryptoPayload.Token("USDC", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", CryptoPayload.Chain.SOLANA),
        "es9vmfrzacermjfrf4h2fyd4kconky11mcce8benwnyb" to
            CryptoPayload.Token("USDT", "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", CryptoPayload.Chain.SOLANA),
    )

    // ---- Bare-address & Lightning-Address recognition ----

    private val bitcoinAddressRegex =
        Regex("""^([13][a-km-zA-HJ-NP-Z1-9]{25,34}|bc1[02-9ac-hj-np-z]{6,87})$""")
    private val ethereumAddressRegex = Regex("""^0x[a-fA-F0-9]{40}$""")
    private val xrpAddressRegex = Regex("""^r[1-9A-HJ-NP-Za-km-z]{24,34}$""")
    private val stellarAddressRegex = Regex("""^G[A-Z2-7]{55}$""")
    private val cosmosAddressRegex = Regex("""^cosmos1[02-9ac-hj-np-z]{30,50}$""")
    /** Tron base58 address: `T` + exactly 33 base58 chars = 34 total.
     *  Strict on length so it doesn't fight Bitcoin's regex. */
    private val tronAddressRegex = Regex("""^T[1-9A-HJ-NP-Za-km-z]{33}$""")
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
        fun build(chain: CryptoPayload.Chain, token: CryptoPayload.Token? = null) = CryptoPayload(
            chain = chain, scheme = "", address = s,
            amount = null, label = null, message = null, chainId = null,
            raw = s, token = token,
        )
        return when {
            lnurlRegex.matches(s) -> build(CryptoPayload.Chain.LNURL)
            bareBolt11Regex.matches(s) -> build(CryptoPayload.Chain.LIGHTNING)
            // Tron checked before Bitcoin so the `T1...` shape doesn't
            // get swallowed by the legacy-Bitcoin regex.
            tronAddressRegex.matches(s) -> build(
                CryptoPayload.Chain.TRON,
                token = knownTokens[s.lowercase(Locale.ROOT)],
            )
            bitcoinAddressRegex.matches(s) -> build(CryptoPayload.Chain.BITCOIN)
            ethereumAddressRegex.matches(s) -> build(
                CryptoPayload.Chain.ETHEREUM,
                token = knownTokens[s.lowercase(Locale.ROOT)],
            )
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
        // EIP-681 function name on the path — `transfer`, `approve`, etc.
        var function: String? = null
        if (chain == CryptoPayload.Chain.ETHEREUM) {
            val at = path.indexOf('@')
            if (at >= 0) {
                address = path.substring(0, at)
                val afterAt = path.substring(at + 1).split('/', limit = 2)
                chainId = afterAt.firstOrNull()
                function = if (afterAt.size > 1) afterAt[1] else null
            }
        }
        if (address.isEmpty()) return null

        // Parse query params into a single map; spec says case-sensitive
        // but real-world wallets vary, so we lowercase at the lookup site.
        val params = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            val parts = pair.split('=', limit = 2)
            if (parts.size != 2) continue
            params[parts[0].lowercase(Locale.ROOT)] = decode(parts[1])
        }

        var amount: String? = params["amount"] ?: params["value"]
        val label: String? = params["label"]
        val message: String? = params["message"]

        // EIP-681 ERC-20 transfer: path's `address` is the token
        // *contract*, the recipient is in `address=`, amount in
        // `uint256=`. Look up the contract in knownTokens to surface
        // the symbol; otherwise fall back to a generic "ERC-20" tag.
        var resolvedAddress = address
        var token: CryptoPayload.Token? = null
        if (chain == CryptoPayload.Chain.ETHEREUM
            && function?.lowercase(Locale.ROOT) == "transfer") {
            val recipient = params["address"]
            if (!recipient.isNullOrEmpty()) {
                token = knownTokens[address.lowercase(Locale.ROOT)]
                    ?: CryptoPayload.Token("ERC-20", address, CryptoPayload.Chain.ETHEREUM)
                resolvedAddress = recipient
                params["uint256"]?.let { amount = it }
            }
        }

        // Solana Pay SPL token: `solana:RECIPIENT?spl-token=MINT&amount=…`
        // Recipient stays in the path; the mint comes from the query.
        if (chain == CryptoPayload.Chain.SOLANA) {
            val mint = params["spl-token"]
            if (!mint.isNullOrEmpty()) {
                token = knownTokens[mint.lowercase(Locale.ROOT)]
                    ?: CryptoPayload.Token("SPL", mint, CryptoPayload.Chain.SOLANA)
            }
        }

        // TRC-20: `tron:CONTRACT?address=RECIPIENT&amount=…` or
        // `tron:RECIPIENT?contract=…` — handle both shapes.
        if (chain == CryptoPayload.Chain.TRON) {
            val recipient = params["address"]
            val contract = params["contract"]
            if (!recipient.isNullOrEmpty() && address.startsWith("T")) {
                token = knownTokens[address.lowercase(Locale.ROOT)]
                    ?: CryptoPayload.Token("TRC-20", address, CryptoPayload.Chain.TRON)
                resolvedAddress = recipient
            } else if (!contract.isNullOrEmpty()) {
                token = knownTokens[contract.lowercase(Locale.ROOT)]
                    ?: CryptoPayload.Token("TRC-20", contract, CryptoPayload.Chain.TRON)
            }
        }

        return CryptoPayload(
            chain = chain,
            scheme = scheme,
            address = resolvedAddress,
            amount = amount,
            label = label,
            message = message,
            chainId = chainId,
            raw = raw,
            token = token,
        )
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, StandardCharsets.UTF_8.name()) }.getOrDefault(s)
}
