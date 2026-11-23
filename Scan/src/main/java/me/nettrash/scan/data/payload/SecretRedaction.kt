package me.nettrash.scan.data.payload

//
//  Anything that behaves like a credential gets blanked before a payload is
//  handed to the explanation engine. The engine is local, so this is not a
//  confidentiality boundary against a server — it is a boundary against the
//  transcript, any on-screen suggestion UI, and anyone holding the phone while
//  an explanation is showing. It is also the one assistant rule we can test
//  exhaustively, so it lives on its own rather than buried in an engine call.
//  Port of the iOS app's `Scan/SecretRedaction.swift`.
//
//  Every rule keeps the field *name* — or, for a bare token, the prefix that
//  says what kind it is (`ghp_`, `xoxb-`) — and blanks the value, so an
//  engine can still say "this code carries a Wi-Fi password" without ever
//  seeing it.
//
//  The patterns are the iOS ones character for character. They avoid `\b`,
//  `\s`, `\w` and `\d` on purpose: Android's regex engine (ICU, like iOS) and
//  the JVM that runs the unit tests disagree on what those match, so every
//  class is spelled out — `[\t\n\f\r\p{Z}]` is ICU's `\s`.
//

object SecretRedaction {

    /** What a blanked value reads as. */
    const val PLACEHOLDER: String = "<redacted>"

    /** What a long opaque blob reads as. */
    const val BLOB_PLACEHOLDER: String = "<encoded-data omitted>"

    /** What a payload that is nothing but a seed phrase reads as. */
    const val SEED_PHRASE_PLACEHOLDER: String = "<seed phrase redacted>"

    /**
     * Blanks out anything that behaves like a credential before it is put in
     * front of the explanation engine, and caps the result at 2000 code points
     * (Unicode scalars, so both ports cut in the same place).
     */
    fun redactForModel(raw: String): String {
        // The safety check's own detector (QrRisk.kt), so a phrase the banner
        // calls a seed phrase is always blanked, and the other way round.
        if (SeedPhrase.isMnemonic(raw)) return SEED_PHRASE_PLACEHOLDER
        var out = raw
        for ((regex, template) in rules) {
            out = regex.replace(out, template)
        }
        // Long opaque blobs (BOLT-11 invoices, JWTs, base64 CBOR) are noise to
        // an explainer and carry value to an attacker.
        out = longOpaqueBlob.replace(out, BLOB_PLACEHOLDER)
        val count = out.codePointCount(0, out.length)
        return if (count <= 2000) out else out.substring(0, out.offsetByCodePoints(0, 2000))
    }

    // MARK: Rules

    /**
     * Words that follow "password" / "PIN" in ordinary prose ("Enter your
     * password below", "pin the poster", "PIN code required"). A value that is
     * one of these is the sentence carrying on, not a credential.
     */
    private val proseWords = listOf(
        "the", "and", "for", "you", "your", "our", "this", "that", "here", "below",
        "above", "protected", "protection", "manager", "required", "reset",
        "recovery", "reminder", "change", "changed", "field", "hint", "policy",
        "must", "should", "will", "can", "cannot", "not", "with", "are", "was",
        "has", "have", "please", "again", "expired", "expires", "entry", "pad",
        "reader", "incorrect", "invalid", "wrong", "strength", "length", "prompt",
        "screen", "page", "form", "box", "settings", "sent", "needed", "needs",
        "only", "from", "when", "then", "into", "also", "code", "number", "isn",
        "wasn", "doesn", "don",
    ).joinToString("|")

    /**
     * The iOS patterns, applied in order. `$1` (and `$2`) keep the field name.
     * A value runs to whitespace, or to an `&` that starts the next query
     * parameter — a password containing `&` is still blanked whole.
     */
    private val rules: List<Pair<Regex, String>> = listOf(
        // PEM / PGP private key: everything from the header on.
        rule("""(-----BEGIN [A-Z ]*PRIVATE KEY[A-Z ]*-----)[\s\S]*""", "$1$PLACEHOLDER"),
        // A password inside a link's login part (`https://user:pass@host`).
        rule("""(?i)([a-z][a-z0-9+.-]*://[^/?#@\t\n\f\r\p{Z}:]*:)[^/?#@\t\n\f\r\p{Z}]+(?=@)""", "$1$PLACEHOLDER"),
        // Wi-Fi password field (`WIFI:…;P:…;`). Only at a field boundary, so
        // the "p:" of "http:" is left alone, and an escaped `\;` or `\\` is
        // part of the password rather than the end of it.
        rule("""(?i)([;:]P:)(?:\\.|[^;\\])+""", "$1$PLACEHOLDER"),
        // otpauth secret, whatever its alphabet or percent-encoding.
        rule(
            """(?i)(?<![A-Za-z0-9_])(secret[\t\n\f\r\p{Z}]*[=:][\t\n\f\r\p{Z}]*)(?:[^&\t\n\f\r\p{Z}]|&(?![A-Za-z0-9_.-]+=))+""",
            "$1$PLACEHOLDER",
        ),
        // A Google Authenticator export carries every account's secret.
        rule("""(?i)(otpauth-migration:[^\t\n\f\r\p{Z}]*?[?&]data=)[^&\t\n\f\r\p{Z}]+""", "$1$PLACEHOLDER"),
        // Tokens, API keys and client secrets, in a query string or as
        // `key: value` — prefixed names too (`auth_token`, `x-api-key`), and
        // written as words (`API key: …`).
        rule(
            """(?i)(?<![A-Za-z0-9])((?:[A-Za-z0-9]+[_-])*(?:token|api[ _-]?key|access[ _-]?key|secret[ _-]?key|client[_-]?secret|private[_-]?key))([\t\n\f\r\p{Z}]*[=:][\t\n\f\r\p{Z}]*)(?:[^&\t\n\f\r\p{Z}]|&(?![A-Za-z0-9_.-]+=)){4,}""",
            "$1$2$PLACEHOLDER",
        ),
        // An HTTP bearer credential.
        rule("""(?i)(?<![A-Za-z0-9])(bearer[\t\n\f\r\p{Z}]+)[A-Za-z0-9._~+/=-]{8,}""", "$1$PLACEHOLDER"),
        // Password / PIN in a query string or free text: "password hunter2",
        // "the password is hunter2", "PIN code: 1234", `user_password=…`. A
        // quoted value is blanked whole; an unquoted one unless it is prose.
        rule(
            """(?i)(?<![A-Za-z0-9])((?:[A-Za-z0-9]+[_-])*(?:password|passwd|passcode|passphrase|pwd|pin)(?:[ _-]?(?:code|number|no\.?))?)([\t\n\f\r\p{Z}]*[=:][\t\n\f\r\p{Z}]*|[\t\n\f\r\p{Z}]+(?:(?:is|was)(?=[\t\n\f\r\p{Z}=:])[\t\n\f\r\p{Z}]*[=:]?[\t\n\f\r\p{Z}]*)?)("[^"\n]*"|'[^'\n]*'|“[^”\n]*”|‘[^’\n]*’|(?!(?:""" +
                proseWords + """)(?![A-Za-z0-9]))(?:[^&\t\n\f\r\p{Z}]|&(?![A-Za-z0-9_.-]+=)){3,})""",
            "$1$2$PLACEHOLDER",
        ),
        // Nostr secret key. Bech32 may be all upper-case in a QR.
        rule("""(?i)(?<![A-Za-z0-9_])(nsec1)[A-Za-z0-9]+""", "$1$PLACEHOLDER"),
        // A bare Bitcoin private key in wallet-import format — what a paper
        // wallet prints. Base58 is case-sensitive, so no `(?i)` here.
        rule("""(?<![A-Za-z0-9])[5KL][1-9A-HJ-NP-Za-km-z]{50,51}(?![A-Za-z0-9])""", PLACEHOLDER),
        // OpenID4VCI bearer secrets: a pre-authorised code, an issuer state or
        // a transaction code redeems the credential offer for whoever holds
        // it. As JSON — raw, or percent-encoded inside a `credential_offer=`
        // link, where a quote is `%22` and the colon may be `%3A` — and as a
        // form or query parameter. A `tx_code` that is an object only
        // describes the code the wallet will ask for, and is left alone.
        rule(
            """(?i)((?:"|%22)(?:pre-authorized_code|issuer_state|tx_code)(?:"|%22)(?:[\t\n\f\r\p{Z}]|%20)*(?::|%3A)(?:[\t\n\f\r\p{Z}]|%20)*(?:"|%22))(?:\\.|%(?!22)|[^"\\%])+""",
            "$1$PLACEHOLDER",
        ),
        rule(
            """(?i)(?<![A-Za-z0-9_-])((?:pre-authorized_code|issuer_state|tx_code)=)[^&#\t\n\f\r\p{Z}]+""",
            "$1$PLACEHOLDER",
        ),
        // Bare service tokens, recognised by their published prefixes, which
        // stay so an engine can say what kind of token it was: GitHub,
        // OpenAI-style `sk-`, Stripe secret keys, Slack.
        rule("""(?<![A-Za-z0-9_])(gh[pousr]_)[A-Za-z0-9]{36,}""", "$1$PLACEHOLDER"),
        rule("""(?<![A-Za-z0-9_])(github_pat_)[A-Za-z0-9_]{22,}""", "$1$PLACEHOLDER"),
        rule("""(?<![A-Za-z0-9_-])(sk-(?:proj-)?)[A-Za-z0-9_-]{20,}""", "$1$PLACEHOLDER"),
        rule("""(?<![A-Za-z0-9_-])((?:sk|rk)_(?:live|test)_)[A-Za-z0-9]{16,}""", "$1$PLACEHOLDER"),
        rule("""(?<![A-Za-z0-9_-])(xox[abprs]-)[A-Za-z0-9-]{10,}""", "$1$PLACEHOLDER"),
        // An AWS access key and the 40-character secret written after it,
        // then a lone access key.
        rule(
            """(?<![A-Za-z0-9])(AKIA|ASIA)[0-9A-Z]{16}([\t\n\f\r\p{Z},:;|]{1,3})[A-Za-z0-9/+=]{40}(?![A-Za-z0-9/+=])""",
            "$1$PLACEHOLDER$2$PLACEHOLDER",
        ),
        rule("""(?<![A-Za-z0-9])(AKIA|ASIA)[0-9A-Z]{16}(?![A-Za-z0-9])""", "$1$PLACEHOLDER"),
        // A webhook's secret is its path: whoever has it can post as the app.
        rule(
            """(?i)(hooks\.slack\.com/services/|discord(?:app)?\.com/api/webhooks/)[^\t\n\f\r\p{Z}?#"'<>]+""",
            "$1$PLACEHOLDER",
        ),
        // "pass" alone, only with an explicit `=` or `:` ("Wi-Fi pass: …"),
        // quoted or not prose, as for "password" above.
        rule(
            """(?i)(?<![A-Za-z0-9])((?:[A-Za-z0-9]+[_-])*pass)([\t\n\f\r\p{Z}]*[=:][\t\n\f\r\p{Z}]*)("[^"\n]*"|'[^'\n]*'|“[^”\n]*”|‘[^’\n]*’|(?!(?:""" +
                proseWords + """)(?![A-Za-z0-9]))(?:[^&\t\n\f\r\p{Z}]|&(?![A-Za-z0-9_.-]+=)){3,})""",
            "$1$2$PLACEHOLDER",
        ),
        // A Microsoft Authenticator activation link: its code and URL together
        // enrol an authenticator.
        rule("""(?i)(phonefactor://[^\t\n\f\r\p{Z}]*?[?&]code=)[^&\t\n\f\r\p{Z}]+""", "$1$PLACEHOLDER"),
        rule("""(?i)(phonefactor://[^\t\n\f\r\p{Z}]*?[?&]url=)[^&\t\n\f\r\p{Z}]+""", "$1$PLACEHOLDER"),
    )

    private val longOpaqueBlob = Regex("""[A-Za-z0-9+/_-]{64,}""")

    /**
     * The pattern text is the iOS one, `(?i)` included. Kotlin's
     * [RegexOption.IGNORE_CASE] is added on top so case folding is
     * Unicode-aware on the JVM as well, as it is in ICU.
     */
    private fun rule(pattern: String, template: String): Pair<Regex, String> {
        val options = if (pattern.startsWith("(?i)")) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return Regex(pattern, options) to template
    }
}
