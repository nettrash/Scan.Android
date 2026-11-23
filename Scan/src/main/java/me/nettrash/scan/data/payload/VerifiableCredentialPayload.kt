package me.nettrash.scan.data.payload

import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * eIDAS 2.0 / OpenID4VC wallet codes: the QR a verifier site shows so an
 * EU Digital Identity Wallet (or any OpenID4VC wallet) hands it attributes,
 * the QR an issuer shows to put a credential into one, and the credential
 * itself.
 *
 * The shapes that arrive in practice:
 *
 *   openid4vp://?client_id=x509_hash%3A…&request_uri=https%3A%2F%2F…    (VP request, by reference)
 *   openid4vp://?client_id=redirect_uri%3A…&dcql_query=%7B…&nonce=…    (VP request, by value)
 *   openid-credential-offer://?credential_offer=%7B…%7D                (offer, by value)
 *   openid-credential-offer://?credential_offer_uri=https%3A%2F%2F…    (offer, by reference)
 *   eyJhbGciOi…​.eyJ2Y3QiO…​.…~WyJ…~                                    (SD-JWT VC)
 *
 * A request or offer *by reference* carries almost nothing: the wallet
 * fetches the real one — with its nonce, its claims and its signature —
 * from the verifier's or issuer's server. Scan says so rather than calling
 * a nonce "missing" that was never meant to be in the code, and it never
 * fetches the document itself: that would tell the server this phone
 * scanned it.
 *
 * Scan decodes these codes to answer "what is this asking me to release,
 * and to whom" — and stops there. We do **not** verify signatures: no
 * trust anchors, no revocation lists, and above all no network round-trip
 * with the user's credential data. The result sheet says that explicitly,
 * in the same voice as the FIDO passkey rows from 1.10.
 *
 * Specs: OpenID4VP 1.0 (Client Identifier Prefixes §5.9.3,
 * `request_uri_method` §5.1, DCQL §6, claims paths §7), OpenID4VCI 1.0
 * (Credential Offer §4.1), OpenID4VC HAIP 1.0 (`haip-vp`, `haip-vci`),
 * ISO/IEC 18013-7 Annex B (`mdoc-openid4vp`), RFC 9901 (SD-JWT).
 *
 * Mirrors the iOS [VerifiableCredentialPayload] struct.
 */
data class VerifiableCredentialPayload(
    val kind: Kind,
    val raw: String,
    /**
     * Wire format — `dc+sd-jwt`, `mso_mdoc`, `jwt_vc_json` … — or, for a
     * DCQL request, every format its credential queries name.
     */
    val format: String? = null,
    /** `client_id` (verifier) or `credential_issuer` (issuer), verbatim. */
    val counterparty: String? = null,
    /**
     * Host the counterparty names, when it names one — the thing a user
     * can actually compare against the site they are standing on. Every
     * host field holds [ShownHost.UNUSUAL] when the address hides its host.
     */
    val counterpartyHost: String? = null,
    /** Where the wallet is told to send its answer. */
    val responseURI: String? = null,
    val noncePresent: Boolean = false,
    val statePresent: Boolean = false,
    /** Issuer of a credential token. */
    val issuer: String? = null,
    /** Credential type — SD-JWT VC `vct`, or the `scope` of a request. */
    val credentialType: String? = null,
    val issuedAt: Date? = null,
    val expiresAt: Date? = null,
    /** Number of selectively-disclosable claims (`_sd`). */
    val selectivelyDisclosable: Int? = null,
    /** Whether the token binds a key (proof-of-possession) — `cnf`. */
    val keyBinding: Boolean? = null,
    /**
     * What the verifier wants, or the issuer offers, flattened to
     * human-readable names.
     */
    val requested: List<String> = emptyList(),
    /** False when the code was recognised but its contents did not decode. */
    val decoded: Boolean = false,
    /**
     * A verifier identity that is not a host: a DID, an attestation
     * subject, a pre-registered client id.
     */
    val counterpartyName: String? = null,
    /**
     * The OpenID4VP 1.0 Client Identifier Prefix `client_id` carries
     * (`x509_hash`, `redirect_uri`, …), when it carries one.
     */
    val clientIDPrefix: String? = null,
    /**
     * The code only points at the request or offer (`request_uri`,
     * `credential_offer_uri`); the wallet fetches the document itself.
     */
    val byReference: Boolean = false,
    /** Host the wallet fetches a by-reference request or offer from. */
    val referenceHost: String? = null,
    /**
     * OpenID4VP `request_uri_method=post`: the wallet POSTs its
     * capabilities to the verifier to get the request.
     */
    val referenceByPost: Boolean = false,
    /**
     * A Request Object passed by value (`request=`) whose payload did not
     * decode — the nonce may well be in it.
     */
    val signedRequestUnreadable: Boolean = false,
    /** The DCQL claims paths a request asks for, as a person reads them. */
    val claims: List<String> = emptyList(),
    /**
     * The same claims credential by credential — "given name, portrait
     * from Mobile driving licence (ISO 18013-5)" — for the explanation.
     */
    val claimsByCredential: List<String> = emptyList(),
    /**
     * The requested claims that are biometric data (a portrait, a
     * signature image, a biometric template), as a person reads them.
     */
    val biometricClaims: List<String> = emptyList(),
    /**
     * OpenID4VCI grants. The pre-authorised code itself is a bearer
     * secret and is never kept, let alone shown; nor is `issuer_state`.
     */
    val preAuthorized: Boolean = false,
    val transactionCodeRequired: Boolean = false,
    val transactionCodeLength: Int? = null,
    val transactionCodeIsText: Boolean = false,
    val authorizationCodeGrant: Boolean = false,
    val authorizationServerHost: String? = null,
) {
    enum class Kind(val rawValue: String) {
        PRESENTATION_REQUEST("Credential request"),
        CREDENTIAL_OFFER("Credential offer"),
        CREDENTIAL("Verifiable credential"),
        PRESENTATION("Verifiable presentation"),
    }

    /** Result-sheet badge text — the iOS `kind.rawValue`. */
    val kindLabel: String get() = kind.rawValue

    /** The counterparty as it reads in a sentence ("issued by …"). */
    val counterpartyDescription: String
        get() = counterpartyHost?.takeIf { it.isNotEmpty() }?.let { ShownHost.inSentence(it) }
            ?: counterpartyName?.takeIf { it.isNotEmpty() }
            ?: (if (clientIDPrefix == "x509_hash") "a verifier identified only by a certificate hash" else null)
            ?: counterparty
            ?: "Not stated"

    /** "PID + 2 more" style summary used in the result header. */
    val requestedSummary: String
        get() = when {
            requested.isEmpty() -> "Nothing stated"
            requested.size == 1 -> requested[0]
            else -> "${requested[0]} + ${requested.size - 1} more"
        }

    fun labelledFields(): List<LabelledField> {
        val rows = mutableListOf(LabelledField("Type", kind.rawValue))
        format?.let { if (it.isNotEmpty()) rows += LabelledField("Format", it) }
        when (kind) {
            Kind.PRESENTATION_REQUEST -> {
                rows += LabelledField("Asking for", requestedRow)
                rows += LabelledField("Verifier", counterpartyRow)
                responseURI?.let {
                    if (it.isNotEmpty()) {
                        rows += LabelledField("Answer goes to", hostOf(it) ?: it)
                    }
                }
                if (byReference) {
                    rows += LabelledField("Request", fetchedFrom(referenceHostInSentence ?: "the verifier"))
                }
                rows += LabelledField("Anti-replay", replayProtection)
            }

            Kind.CREDENTIAL_OFFER -> {
                rows += LabelledField("Offering", requestedRow)
                rows += LabelledField("Issuer", counterpartyRow)
                if (byReference) {
                    rows += LabelledField("Offer", fetchedFrom(referenceHostInSentence ?: "the issuer"))
                }
                signIn?.let { rows += LabelledField("Sign-in", it) }
                if (preAuthorized) rows += LabelledField("Transaction code", transactionCodeRow)
            }

            Kind.CREDENTIAL, Kind.PRESENTATION -> {
                rows += LabelledField("Credential", VerifiableCredentialParser.typeName(credentialType))
                rows += LabelledField(
                    "Issuer",
                    issuer?.let { hostOf(it) } ?: issuer ?: "Not stated"
                )
                issuedAt?.let { rows += LabelledField("Issued", stamp(it)) }
                expiresAt?.let { rows += LabelledField("Expires", stamp(it)) }
                selectivelyDisclosable?.let {
                    rows += LabelledField(
                        "Hidden claims",
                        if (it == 1) "1 (selective disclosure)" else "$it (selective disclosure)"
                    )
                }
                keyBinding?.let {
                    rows += LabelledField(
                        "Proof of possession",
                        if (it) "Bound to a device key" else "Not bound"
                    )
                }
                credentialType?.let {
                    if (!VerifiableCredentialParser.isKnownType(it)) {
                        rows += LabelledField("Type id", it)
                    }
                }
            }
        }
        for (item in requested.drop(1).take(24)) {
            rows += LabelledField("↳", item)
        }
        if (claims.isNotEmpty()) rows += LabelledField("Claims asked for", claimsSummary)
        if (!decoded) {
            rows += LabelledField("Claims", "Not decodable — possibly a newer format")
        }
        return rows
    }

    /**
     * Rows the result sheet shows under the fields: what Scan did *not* do.
     */
    fun cautions(): List<String> {
        val notes = mutableListOf<String>()
        when (kind) {
            Kind.PRESENTATION_REQUEST -> {
                notes.add(
                    "Only approve a request you started yourself — approving one someone " +
                        "sent you releases your data to them."
                )
                // By reference the nonce is inside the request the wallet
                // fetches, so its absence from the code means nothing.
                if (!byReference && !signedRequestUnreadable && !noncePresent) {
                    notes.add("No nonce: the same code can be replayed, so treat its contents as untrusted.")
                }
                if (clientIDPrefix == "origin") {
                    notes.add(
                        "The verifier uses the origin: prefix, which is reserved for browser " +
                            "requests — a wallet must refuse it in a QR code."
                    )
                }
                if (biometricClaims.isNotEmpty()) {
                    notes.add(
                        "It asks for biometric data (${biometricClaims.joinToString(", ")}): " +
                            "only approve that for a verifier you trust."
                    )
                }
            }

            Kind.CREDENTIAL_OFFER -> {
                notes.add(
                    "Installing a credential from an unsolicited QR is how fake wallets " +
                        "and impersonation kits spread."
                )
                if (preAuthorized && !transactionCodeRequired) {
                    notes.add(
                        "Pre-authorised with no transaction code: whoever redeems this code " +
                            "first gets the credential, so do not share or post it."
                    )
                }
            }

            Kind.CREDENTIAL, Kind.PRESENTATION -> notes.add(
                "This is the credential's own text. Scan does not verify the issuer's " +
                    "signature, its revocation status, or that the document is genuine."
            )
        }
        if (listOfNotNull(counterpartyHost, referenceHost)
                .any { it != ShownHost.UNUSUAL && QrRiskAssessor.looksLookalike(it) }
        ) {
            notes.add("The requesting domain looks like a typosquat of a well-known brand.")
        }
        val shown = listOf(counterpartyHost, referenceHost, authorizationServerHost, responseURI?.let { ShownHost.of(it) })
        if (ShownHost.UNUSUAL in shown) notes.add(ShownHost.HIDDEN_HOST_CAUTION)
        return notes
    }

    /** The line under the cautions: what Scan did not do with this code. */
    val footnote: String
        get() = when (kind) {
            Kind.PRESENTATION_REQUEST -> if (byReference) {
                "Scan decodes this code to explain it. It does not fetch the request, " +
                    "answer it, or verify any signature."
            } else {
                "Scan decodes this code to explain it. It does not answer the request " +
                    "or verify its signature."
            }

            Kind.CREDENTIAL_OFFER -> if (byReference) {
                "Scan decodes this code to explain it. It does not fetch the offer or " +
                    "contact the issuer."
            } else {
                "Scan decodes this code to explain it. It does not redeem the offer or " +
                    "contact the issuer."
            }

            Kind.CREDENTIAL, Kind.PRESENTATION ->
                "Scan decodes this code to explain it. It does not verify the signature, " +
                    "check revocation, or contact the issuer."
        }

    /** The built-in explainer's key points for this code. */
    fun explanationPoints(): List<String> = when (kind) {
        Kind.PRESENTATION_REQUEST -> buildList {
            add("A site is asking your digital-identity wallet to release attributes.")
            if (requested.isEmpty() && byReference) {
                add(
                    "The request itself is not in the code: your wallet fetches it from " +
                        "${referenceHostInSentence ?: "the verifier"}."
                )
            } else if (claimsByCredential.isNotEmpty()) {
                // The claims themselves, not the credentials' names: a
                // name says what a credential *can* hold, which is not
                // what this verifier asked for.
                add("Requested: ${claimsByCredential.joinToString("; ")}.")
            } else {
                add("Requested: $requestedSummary.")
            }
            add("Verifier: $counterpartyRow.")
        }

        Kind.CREDENTIAL_OFFER -> buildList {
            add("An issuer is offering to add a credential to your digital-identity wallet.")
            if (requested.isEmpty() && byReference) {
                add(
                    "The offer itself is not in the code: your wallet fetches it from " +
                        "${referenceHostInSentence ?: "the issuer"}."
                )
            } else {
                add("Offered: $requestedSummary.")
            }
            add("Issuer: $counterpartyRow.")
            if (preAuthorized) {
                add(
                    if (transactionCodeRequired) {
                        "Your wallet will ask for a transaction code before the credential is issued."
                    } else {
                        "No sign-in or code is needed: whoever redeems the code gets the credential."
                    }
                )
            } else if (authorizationCodeGrant) {
                add(
                    "You'll sign in at ${authorizationServerHost?.let { ShownHost.inSentence(it) } ?: "the issuer"} " +
                        "before the credential is issued."
                )
            }
        }

        Kind.CREDENTIAL -> listOf(
            "A digital-identity credential issued by $counterpartyDescription.",
            "Credential: ${VerifiableCredentialParser.typeName(credentialType)}.",
        )

        Kind.PRESENTATION -> listOf(
            "A wallet's presentation of digital-identity credentials, from $counterpartyDescription.",
            "Credential: ${VerifiableCredentialParser.typeName(credentialType)}.",
        )
    }

    // MARK: Row text

    private val requestedRow: String
        get() = when {
            requested.isNotEmpty() -> requestedSummary
            byReference -> "Not in the code — your wallet fetches the details"
            else -> "Not stated"
        }

    /**
     * The Verifier / Issuer row: a host, a DID, or what kind of identifier
     * it is when it names nobody.
     */
    private val counterpartyRow: String
        get() = when {
            clientIDPrefix == "x509_hash" -> "Unnamed — identified by certificate hash"
            counterparty.isNullOrEmpty() -> if (byReference) "Not in the code" else "Not stated"
            counterpartyHost == ShownHost.UNUSUAL -> ShownHost.UNUSUAL
            else -> counterpartyDescription
        }

    private val referenceHostInSentence: String?
        get() = referenceHost?.let { ShownHost.inSentence(it) }

    private fun fetchedFrom(host: String): String =
        if (referenceByPost) "Your wallet fetches it from $host by POST" else "Your wallet fetches it from $host"

    private val replayProtection: String
        get() = when {
            byReference -> "Inside the fetched request — not in the code"
            signedRequestUnreadable -> "Inside the signed request — not readable"
            noncePresent -> if (statePresent) "Nonce + state" else "Nonce present"
            else -> "Missing — can be replayed"
        }

    private val signIn: String?
        get() {
            val at = authorizationServerHost?.let { ShownHost.inSentence(it) } ?: "the issuer"
            return when {
                preAuthorized && authorizationCodeGrant -> "Pre-authorised, or you sign in at $at — your wallet picks"
                preAuthorized -> "Not needed — pre-authorised by the issuer"
                authorizationCodeGrant -> "You'll sign in at $at"
                else -> null
            }
        }

    private val transactionCodeRow: String
        get() {
            if (!transactionCodeRequired) return "Not required — whoever redeems the code gets the credential"
            val n = transactionCodeLength ?: return "Required"
            val unit = if (transactionCodeIsText) {
                if (n == 1) "character" else "characters"
            } else {
                if (n == 1) "digit" else "digits"
            }
            return "Required — $n $unit"
        }

    private val claimsSummary: String
        get() {
            val shown = claims.take(12).joinToString(", ")
            return if (claims.size > 12) "$shown + ${claims.size - 12} more" else shown
        }

    private fun stamp(date: Date): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(date)

    /** The "Answer goes to" and token "Issuer" rows' host (see [ShownHost]). */
    private fun hostOf(raw: String): String? = ShownHost.of(raw)
}

object VerifiableCredentialParser {

    /**
     * URI schemes that invoke an OpenID4VC wallet. Deliberately narrow —
     * a bare `https://` must never land here.
     *
     * - `openid4vp` — OpenID4VP 1.0 §13.1.2 (IANA, provisional).
     * - `eudi-openid4vp`, `mdoc-openid4vp` — the EU reference wallet's;
     *   the second is ISO/IEC 18013-7 Annex B (IANA, provisional).
     * - `haip-vp`, `haip-vci` — OpenID4VC HAIP 1.0 §5.1 / §4.2 (IANA,
     *   permanent); `haip` is the HAIP drafts' single scheme before them.
     * - `openid-credential-offer` — OpenID4VCI 1.0 §4.1 (IANA, permanent).
     * - `openid-vc` — Microsoft Entra Verified ID.
     */
    val requestSchemes: Set<String> = setOf(
        "openid4vp", "eudi-openid4vp", "mdoc-openid4vp", "haip-vp", "haip",
        "openid-vc", "openid-credential-offer", "haip-vci",
    )

    /** The schemes that only ever carry a credential offer. */
    private val offerSchemes: Set<String> = setOf("openid-credential-offer", "haip-vci")

    // MARK: Recognition

    fun looksLikeCredentialRequest(raw: String): Boolean {
        val schemeEnd = raw.indexOf(':')
        if (schemeEnd < 0) return false
        val scheme = raw.substring(0, schemeEnd).lowercase(Locale.ROOT)
        if (scheme !in requestSchemes) return false
        // A request must carry at least one protocol parameter.
        val rest = raw.substring(schemeEnd + 1).lowercase(Locale.ROOT)
        return rest.contains("client_id") || rest.contains("request_uri") ||
            rest.contains("request=") ||
            rest.contains("dcql_query") || rest.contains("presentation_definition") ||
            rest.contains("credential_offer") || rest.contains("scope=") ||
            rest.contains("nonce")
    }

    /**
     * Three dot-separated base64URL segments with a JSON object payload —
     * and only with claim names that mean "credential", so ordinary login
     * tokens a user pastes in are not dragged into this category. An
     * SD-JWT appends `~`-separated disclosures (RFC 9901 §4); only the
     * issuer-signed JWT in front of them is tested here.
     */
    fun looksLikeCredentialToken(raw: String): Boolean {
        val segments = raw.substringBefore('~').split(".")
        if (segments.size != 3 && segments.size != 4) return false
        if (segments[0].length <= 8 || !segments[0].all(::isBase64URLChar) ||
            !segments[1].all(::isBase64URLChar)
        ) {
            return false
        }
        val claims = PayloadCodec.jsonObjectFromBase64URL(segments[1]) ?: return false
        // Presence, not value: iOS tests `claims["vct"] != nil`, which a
        // JSON `null` (NSNull) passes, so `"vct": null` still counts there.
        if ("vct" in claims || "_sd" in claims || "_sd_alg" in claims) return true
        if ("vp" in claims) return true
        return "vc" in claims
    }

    private fun isBase64URLChar(c: Char): Boolean = c.isLetterOrDigit() || c == '-' || c == '_'

    // MARK: Parsing

    fun parse(raw: String): VerifiableCredentialPayload? {
        val trimmed = PayloadCodec.trimSwiftWhitespace(raw)
        return if (looksLikeCredentialToken(trimmed)) parseToken(trimmed) else parseRequest(trimmed)
    }

    // MARK: Requests & offers

    private fun parseRequest(raw: String): VerifiableCredentialPayload? {
        val schemeEnd = raw.indexOf(':')
        if (schemeEnd < 0) return null
        val scheme = raw.substring(0, schemeEnd).lowercase(Locale.ROOT)
        // These are not ordinary URLs: `openid4vp://authorize?…` carries the
        // parameters after an authority that does not exist, and
        // `openid-credential-offer://?…` after an empty one. Only the query
        // matters: everything after the first `?`, up to a fragment.
        val afterScheme = raw.substring(schemeEnd + 1)
        val queryStart = afterScheme.indexOf('?')
        val query = if (queryStart < 0) "" else afterScheme.substring(queryStart + 1)
        val items = queryItemsByLowercasedName(query)

        val isOffer = scheme in offerSchemes || items["credential_offer"] != null ||
            items["credential_offer_uri"] != null
        return if (isOffer) parseOffer(raw, items) else parsePresentationRequest(raw, scheme, items)
    }

    /**
     * OpenID4VP Authorization Request. By value, every parameter is in the
     * query — or inside a Request Object JWT passed as `request` (RFC 9101),
     * which a wallet reads instead of the query. By reference
     * (`request_uri`), the query holds only `client_id` and where to fetch
     * the rest.
     */
    private fun parsePresentationRequest(
        raw: String,
        scheme: String,
        items: Map<String, String?>,
    ): VerifiableCredentialPayload {
        var params: Map<String, Any?> = items
        var signedRequestUnreadable = false
        nonEmpty(items["request"])?.let { jwt ->
            val payload = jwtPayload(jwt)
            if (payload != null) {
                // iOS fills in the outer `client_id` only when the key is absent:
                // a JSON `null` there is NSNull, not nil.
                val outer = items["client_id"]
                params = if ("client_id" !in payload && outer != null) payload + ("client_id" to outer) else payload
            } else {
                signedRequestUnreadable = true
            }
        }

        val requestURI = nonEmpty(items["request_uri"])
        val byReference = requestURI != null

        val requested = mutableListOf<String>()
        val formats = mutableListOf<String>()
        val claims = mutableListOf<String>()
        var claimsByCredential = emptyList<String>()
        var biometric = emptyList<String>()
        jsonDocument(params["dcql_query"])?.let { dcql ->
            val query = dcqlCredentials(dcql)
            requested += query.names
            formats += query.formats
            claims += query.claims
            claimsByCredential = query.byCredential
            biometric = query.biometric
        }
        jsonDocument(params["presentation_definition"])?.let { requested += presentationDefinitionCredentials(it) }
        nonEmpty(params["presentation_definition_uri"] as? String)?.let { pdUri ->
            requested += "presentation definition fetched from ${host(pdUri)?.let { ShownHost.inSentence(it) } ?: pdUri}"
        }
        (params["scope"] as? String)?.let { scope ->
            requested += scope.split(" ").filter { it.isNotEmpty() }.map { scopeName(it) }
        }

        var format = dedupe(formats).joinToString(", ")
        if (format.isEmpty()) nonEmpty(params["format"] as? String)?.let { format = it }
        // ISO/IEC 18013-7 Annex B: this scheme only ever asks for an mdoc.
        if (format.isEmpty() && scheme == "mdoc-openid4vp") format = "mso_mdoc"

        val clientID = nonEmpty(params["client_id"] as? String)
        val identity = clientIdentity(clientID, params["client_id_scheme"] as? String)

        return VerifiableCredentialPayload(
            kind = VerifiableCredentialPayload.Kind.PRESENTATION_REQUEST,
            raw = raw,
            format = format.ifEmpty { null },
            counterparty = clientID,
            counterpartyHost = identity.host,
            responseURI = nonEmpty(params["response_uri"] as? String)
                ?: nonEmpty(params["redirect_uri"] as? String),
            noncePresent = nonEmpty(params["nonce"] as? String) != null,
            statePresent = nonEmpty(params["state"] as? String) != null,
            requested = dedupe(requested),
            // A request with nothing decodable in it is still a request; only
            // mark it undecoded when there is genuinely no protocol content.
            decoded = requested.isNotEmpty() || clientID != null || byReference,
            counterpartyName = identity.name,
            clientIDPrefix = identity.prefix,
            byReference = byReference,
            referenceHost = requestURI?.let { host(it) },
            referenceByPost = items["request_uri_method"]?.lowercase(Locale.ROOT) == "post",
            signedRequestUnreadable = signedRequestUnreadable && !byReference,
            claims = dedupe(claims).take(64),
            claimsByCredential = claimsByCredential,
            biometricClaims = dedupe(biometric),
        )
    }

    /**
     * OpenID4VCI 1.0 Credential Offer (§4.1): `credential_issuer`,
     * `credential_configuration_ids` and `grants`, by value in
     * `credential_offer` or by reference in `credential_offer_uri`.
     */
    private fun parseOffer(raw: String, items: Map<String, String?>): VerifiableCredentialPayload {
        var issuer = nonEmpty(items["credential_issuer"])
        var requested = emptyList<String>()
        var grants = Grants()
        var byReference = false
        var referenceHost: String? = null
        val offer = items["credential_offer"]?.let { PayloadCodec.jsonObjectFromString(it) }
        if (offer != null) {
            nonEmpty(offer["credential_issuer"] as? String)?.let { issuer = it }
            val ids = stringList(offer["credential_configuration_ids"])
            val legacy = offer["credentials"] as? List<*>
            if (ids != null) {
                requested = dedupe(ids.filter { it.isNotEmpty() }.map { typeName(it) })
            } else if (legacy != null) {
                // Drafts before 1.0: configuration ids as strings, or (draft
                // 11) objects spelling out a format and a type.
                requested = dedupe(legacy.mapNotNull { legacyOfferedName(it) })
            }
            grants = readGrants(offer["grants"] as? Map<*, *>)
        } else {
            nonEmpty(items["credential_offer_uri"])?.let { uri ->
                byReference = true
                referenceHost = host(uri)
            }
        }
        return VerifiableCredentialPayload(
            kind = VerifiableCredentialPayload.Kind.CREDENTIAL_OFFER,
            raw = raw,
            counterparty = issuer,
            counterpartyHost = issuer?.let { host(it) },
            requested = requested,
            decoded = byReference || issuer != null || requested.isNotEmpty(),
            byReference = byReference,
            referenceHost = referenceHost,
            preAuthorized = grants.preAuthorized,
            transactionCodeRequired = grants.transactionCodeRequired,
            transactionCodeLength = grants.transactionCodeLength,
            transactionCodeIsText = grants.transactionCodeIsText,
            authorizationCodeGrant = grants.authorizationCodeGrant,
            authorizationServerHost = grants.authorizationServerHost,
        )
    }

    private const val PRE_AUTHORIZED_GRANT = "urn:ietf:params:oauth:grant-type:pre-authorized_code"

    private data class Grants(
        val preAuthorized: Boolean = false,
        val transactionCodeRequired: Boolean = false,
        val transactionCodeLength: Int? = null,
        val transactionCodeIsText: Boolean = false,
        val authorizationCodeGrant: Boolean = false,
        val authorizationServerHost: String? = null,
    )

    /**
     * Which way the wallet will redeem the offer. Only the *shape* of each
     * grant is read: `pre-authorized_code` is a bearer secret and
     * `issuer_state` is opaque, so neither is kept.
     */
    private fun readGrants(grants: Map<*, *>?): Grants {
        if (grants == null) return Grants()
        var result = Grants()
        (grants[PRE_AUTHORIZED_GRANT] as? Map<*, *>)?.let { pre ->
            // `tx_code` present — even empty — means a code will be asked
            // for; draft 12 and earlier said `user_pin_required` instead.
            val tx = pre["tx_code"] as? Map<*, *>
            result = if (tx != null) {
                result.copy(
                    preAuthorized = true,
                    transactionCodeRequired = true,
                    transactionCodeLength = (tx["length"] as? Long)?.takeIf { it in 1L..100L }?.toInt(),
                    transactionCodeIsText = (tx["input_mode"] as? String) == "text",
                )
            } else {
                result.copy(
                    preAuthorized = true,
                    transactionCodeRequired = pre["user_pin_required"] as? Boolean ?: false,
                )
            }
        }
        (grants["authorization_code"] as? Map<*, *>)?.let { auth ->
            result = result.copy(
                authorizationCodeGrant = true,
                authorizationServerHost = (auth["authorization_server"] as? String)?.let { host(it) },
            )
        }
        return result
    }

    private fun legacyOfferedName(entry: Any?): String? {
        if (entry is String) return if (entry.isEmpty()) null else typeName(entry)
        val obj = entry as? Map<*, *> ?: return null
        nonEmpty(obj["doctype"] as? String)?.let { return typeName(it) }
        nonEmpty(obj["vct"] as? String)?.let { return typeName(it) }
        stringList(obj["types"])?.let { types -> nonEmpty(types.lastOrNull())?.let { return typeName(it) } }
        (obj["credential_definition"] as? Map<*, *>)?.let { definition ->
            stringList(definition["type"])?.let { types ->
                nonEmpty(types.lastOrNull())?.let { return typeName(it) }
            }
        }
        return nonEmpty(obj["format"] as? String)
    }

    // MARK: Verifier identity

    /**
     * OpenID4VP 1.0 Client Identifier Prefixes (§5.9.3), plus the
     * `x509_san_uri` of the drafts before it.
     */
    private val clientIDPrefixes = listOf(
        "redirect_uri", "x509_san_dns", "x509_hash", "decentralized_identifier",
        "verifier_attestation", "openid_federation", "origin", "x509_san_uri",
    )

    /** Pre-1.0 requests named the prefix in a separate `client_id_scheme`. */
    private val legacyClientIDSchemes: Map<String, String> = mapOf(
        "redirect_uri" to "redirect_uri", "x509_san_dns" to "x509_san_dns",
        "x509_san_uri" to "x509_san_uri", "did" to "decentralized_identifier",
        "entity_id" to "openid_federation", "verifier_attestation" to "verifier_attestation",
    )

    private data class Identity(val prefix: String?, val host: String?, val name: String?)

    /**
     * What `client_id` says about the verifier: the prefix it uses, the
     * host it names, or the non-host name (a DID, a client id) to show.
     */
    private fun clientIdentity(clientID: String?, legacyScheme: String?): Identity {
        if (clientID == null) return Identity(null, null, null)
        for (prefix in clientIDPrefixes) {
            if (clientID.startsWith("$prefix:")) {
                return identity(prefix, clientID.substring(prefix.length + 1))
            }
        }
        legacyScheme?.let { legacyClientIDSchemes[it] }?.let { return identity(it, clientID) }
        if (clientID.startsWith("did:")) return identity("decentralized_identifier", clientID)
        return identity(null, clientID)
    }

    private fun identity(prefix: String?, value: String): Identity = when (prefix) {
        // A hash of the signing certificate: it names nobody.
        "x509_hash" -> Identity(prefix, null, null)
        "decentralized_identifier" -> {
            val did = didIdentity(value)
            Identity(prefix, did.first, did.second)
        }
        "x509_san_dns" -> if (isHostName(value)) Identity(prefix, value, null) else Identity(prefix, null, value)
        // A URL (redirect_uri, openid_federation, origin, x509_san_uri, an
        // https client id) names its host; an attestation subject or a bare
        // client id may itself be a host name.
        else -> {
            val host = host(value)
            when {
                host != null -> Identity(prefix, host, null)
                isHostName(value) -> Identity(prefix, value, null)
                else -> Identity(prefix, null, value)
            }
        }
    }

    /**
     * `did:web` names a host; key-only DIDs name nobody; any other DID is
     * shown as itself, unless it is too long to read.
     */
    private fun didIdentity(did: String): Pair<String?, String> {
        if (!did.startsWith("did:")) return null to did
        val method = did.removePrefix("did:").substringBefore(':')
        if (method == "web") {
            // did:web:<host>[%3A<port>][:<path>…] — a DNS name has no `%`.
            val host = did.removePrefix("did:web:").substringBefore(':').substringBefore('%')
            if (isHostName(host)) return host to host
        }
        if (method in setOf("key", "jwk", "peer")) return null to "Unnamed — a bare key (did:$method)"
        if (did.toByteArray(Charsets.UTF_8).size > 64) return null to "did:$method (long identifier)"
        return null to did
    }

    /** An ASCII DNS name with at least one dot. */
    private fun isHostName(s: String): Boolean {
        if (s.toByteArray(Charsets.UTF_8).size > 253 || !s.contains('.') || s.startsWith('.') ||
            s.endsWith('.') || s.startsWith('-')
        ) {
            return false
        }
        return s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' }
    }

    // MARK: Tokens (SD-JWT VC / VP JWT)

    private fun parseToken(raw: String): VerifiableCredentialPayload? {
        val segments = raw.substringBefore('~').split(".")
        if (segments.size < 3) return null
        val header = PayloadCodec.jsonObjectFromBase64URL(segments[0]) ?: return null
        val claims = PayloadCodec.jsonObjectFromBase64URL(segments[1]) ?: return null

        val typ = (header["typ"] as? String)?.lowercase(Locale.ROOT)
        // An SD-JWT followed by a Key Binding JWT is what a wallet sends a
        // verifier (RFC 9901 §4), not what an issuer hands out.
        val keyBindingJWT = raw.contains('~') && raw.substringAfterLast('~').count { it == '.' } == 2
        val isPresentation = "vp" in claims || typ?.contains("kb") == true || keyBindingJWT
        val sd = (claims["_sd"] as? List<*>)?.size

        var credentialType = (claims["vct"] as? String) ?: typeFromLegacy(claims)
        var tokenRequested = emptyList<String>()
        val vp = claims["vp"] as? Map<*, *>
        val inner = vp?.get("verifiableCredential") as? List<*>
        if (vp != null && inner != null) {
            tokenRequested = inner.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
            if (credentialType == null) {
                credentialType = stringList(vp["@context"])?.lastOrNull()
            }
        }

        val issuer = claims["iss"] as? String
        return VerifiableCredentialPayload(
            kind = if (isPresentation) VerifiableCredentialPayload.Kind.PRESENTATION
                else VerifiableCredentialPayload.Kind.CREDENTIAL,
            raw = raw,
            format = typ?.let { if (it.contains("sd-jwt")) "dc+sd-jwt" else it },
            counterparty = (claims["iss"] as? String) ?: (claims["client_id"] as? String),
            counterpartyHost = issuer?.let { host(it) },
            responseURI = null,
            noncePresent = (claims["nonce"] as? String) != null,
            statePresent = (claims["state"] as? String) != null,
            issuer = issuer,
            credentialType = credentialType,
            issuedAt = dateFrom(claims["iat"]),
            expiresAt = dateFrom(claims["exp"]),
            selectivelyDisclosable = sd,
            keyBinding = "cnf" in claims,
            requested = tokenRequested,
            decoded = issuer != null || credentialType != null,
        )
    }

    /** W3C VC-JWT puts the type list under `vc.type`. */
    private fun typeFromLegacy(claims: Map<*, *>): String? {
        val vc = claims["vc"] as? Map<*, *> ?: return null
        val types = stringList(vc["type"]) ?: return null
        return types.lastOrNull()
    }

    // MARK: Request-document extraction

    private class DcqlQuery(
        val names: List<String>,
        val formats: List<String>,
        val claims: List<String>,
        val byCredential: List<String>,
        val biometric: List<String>,
    )

    /**
     * DCQL (OpenID4VP 1.0 §6): each Credential Query has a verifier-local
     * `id` ("pid", "my_credential"), a `format`, and a `meta` that says
     * what credential it actually is — so `meta` names it.
     *
     * `byCredential` reads the claims credential by credential — up to
     * four credentials and eight claims each, the rest counted — and is
     * empty when the query names no claims at all.
     */
    private fun dcqlCredentials(json: Map<*, *>): DcqlQuery {
        val credentials = objectList(json["credentials"])
            ?: return DcqlQuery(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val names = mutableListOf<String>()
        val formats = mutableListOf<String>()
        val claims = mutableListOf<String>()
        var byCredential = mutableListOf<String>()
        val biometric = mutableListOf<String>()
        for (entry in credentials) {
            val format = nonEmpty(entry["format"] as? String)
            val name = dcqlName(entry, format)
            names += name
            if (format != null) formats += format
            val spoken = mutableListOf<String>()
            for (claim in objectList(entry["claims"]).orEmpty()) {
                val path = claim["path"] as? List<*> ?: continue
                val shown = claimPath(path, format == "mso_mdoc") ?: continue
                val leaf = claimLeaf(path) ?: continue
                claims += shown
                val said = leaf.replace('_', ' ')
                spoken += said
                if (isBiometric(leaf)) biometric += said
            }
            val distinct = dedupe(spoken)
            if (distinct.isEmpty()) {
                byCredential += name
            } else {
                val shown = distinct.take(8).joinToString(", ")
                byCredential += (if (distinct.size > 8) "$shown + ${distinct.size - 8} more" else shown) + " from $name"
            }
        }
        if (claims.isEmpty()) {
            byCredential = mutableListOf()
        } else if (byCredential.size > 4) {
            byCredential = (byCredential.take(4) + "+ ${byCredential.size - 4} more").toMutableList()
        }
        return DcqlQuery(names, formats, claims, byCredential, biometric)
    }

    /** The last name in a claims path — `street_address` of `["address", "street_address"]`, the element of an mdoc path. */
    private fun claimLeaf(path: List<*>): String? =
        path.mapNotNull { it as? String }.filter { it.isNotEmpty() }.lastOrNull()

    /**
     * A portrait (ISO/IEC 18013-5 and the PID's mdoc), the PID's SD-JWT
     * `picture`, a signature image or any ISO biometric template.
     */
    private fun isBiometric(leaf: String): Boolean {
        val name = leaf.lowercase(Locale.ROOT)
        return name in setOf("portrait", "picture", "signature_usual_mark", "fingerprint", "iris") ||
            name.startsWith("biometric_template")
    }

    /**
     * `meta.vct_values` (SD-JWT VC), `meta.doctype_value` (mdoc) or
     * `meta.type_values` (W3C VC) first, then the `id`, then the format.
     */
    private fun dcqlName(entry: Map<*, *>, format: String?): String {
        (entry["meta"] as? Map<*, *>)?.let { meta ->
            stringList(meta["vct_values"])?.let { vct -> nonEmpty(vct.firstOrNull())?.let { return typeName(it) } }
            nonEmpty(meta["doctype_value"] as? String)?.let { return typeName(it) }
            stringLists(meta["type_values"])?.let { types ->
                nonEmpty(types.firstOrNull()?.lastOrNull())?.let { return typeName(it) }
            }
        }
        nonEmpty(entry["id"] as? String)?.let { return typeName(it) }
        return format ?: "credential"
    }

    /**
     * A claims path pointer (§7) as a person reads it: its names joined
     * with dots; array indices and the `null` wildcard are dropped. An mdoc
     * path is `[namespace, element]`, and the element is what matters.
     */
    private fun claimPath(path: List<*>, mdoc: Boolean): String? {
        val names = path.mapNotNull { it as? String }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return null
        if (mdoc && path.size == 2 && names.size == 2) return names[1]
        return names.joinToString(".")
    }

    private fun presentationDefinitionCredentials(json: Map<*, *>): List<String> {
        val descriptors = objectList(json["input_descriptors"]) ?: return emptyList()
        return descriptors.map { d ->
            val name = d["name"] as? String
            if (!name.isNullOrEmpty()) return@map name
            val id = d["id"] as? String
            if (!id.isNullOrEmpty()) return@map typeName(id)
            val fields = (d["constraints"] as? Map<*, *>)?.let { objectList(it["fields"]) }
            val path = fields?.firstOrNull()?.let { stringList(it["path"]) }
            if (!path.isNullOrEmpty()) return@map path[0]
            "credential"
        }
    }

    // MARK: Naming

    /**
     * Known `vct` / doctype / credential-configuration identifiers.
     * Unmatched ones are shown verbatim rather than guessed at.
     */
    fun typeName(raw: String?): String {
        if (raw.isNullOrEmpty()) return "Not stated"
        var key = raw.lowercase(Locale.ROOT)
        knownTypes[key]?.let { return it }
        if (key.startsWith(EUDI_PREFIX)) {
            // The EU reference issuer names its credential configurations
            // `<attestation>_<format>`: `eu.europa.ec.eudi.pid_mdoc`.
            configurationSuffixes.firstOrNull { key.endsWith(it) }?.let { key = key.dropLast(it.length) }
            configurationAliases[key.removePrefix(EUDI_PREFIX)]?.let { alias ->
                knownTypes[alias]?.let { return it }
            }
        }
        if (key.startsWith("urn:eudi:pid:")) {
            // A national PID extends the EU type: `urn:eudi:pid:de:1`.
            val country = key.removePrefix("urn:eudi:pid:").substringBefore(':')
            if (country.length == 2 && country.all { it in 'a'..'z' }) {
                return "EU Digital Identity — PID (${country.uppercase(Locale.ROOT)})"
            }
        }
        // Families: every `urn:eu.europa.ec.eudi:*` is an eIDAS attribute set.
        if (key.startsWith("urn:eu.europa.ec.eudi:") || key.startsWith(EUDI_PREFIX) ||
            key.startsWith("urn:eudi:")
        ) {
            // The interesting part is the attribute-set name: the last
            // non-numeric segment of `urn:eu.europa.ec.eudi:pid:1`.
            val parts = key.split(':', '.', '/')
            val leaf = parts.asReversed().firstOrNull { p ->
                p.isNotEmpty() && !p.all { it in '0'..'9' }
            }
            return if (leaf != null) "eIDAS ${leaf.replace('_', ' ').uppercase(Locale.ROOT)} attribute set"
            else "eIDAS attribute set"
        }
        if (key.startsWith("https://") || key.startsWith("http://")) {
            return host(raw)?.takeIf { it != ShownHost.UNUSUAL } ?: raw
        }
        return raw
    }

    fun isKnownType(raw: String): Boolean = knownTypes[raw.lowercase(Locale.ROOT)] != null

    /**
     * No list of attributes in the name: a request shows it next to what it
     * asks for, and a PID can hold far more than a name, a birth date and a
     * nationality.
     */
    private const val PID_NAME = "EU Digital Identity — PID"
    private const val EHIC_NAME = "European Health Insurance Card"

    /**
     * Lower-cased keys. The PID and EHIC identifiers are the ARF's and the
     * EU reference issuer's (`urn:eudi:pid:1` since ARF 1.8; the older
     * `urn:eu.europa.ec.eudi:pid:1` is still issued); the ISO doctypes are
     * the ones OpenID4VP 1.0's own DCQL examples request.
     */
    private val knownTypes: Map<String, String> = mapOf(
        "urn:eudi:pid:1" to PID_NAME,
        "urn:eu.europa.ec.eudi:pid:1" to PID_NAME,
        "eu.europa.ec.eudi.pid.1" to PID_NAME,
        "urn:eu.europa.ec.eudi:pid:1:strict" to "EU Digital Identity — PID (full)",
        "urn:eu.europa.ec.eudi:attestation:1" to "eIDAS attribute attestation",
        "urn:eu.europa.ec.eudi:attest:1" to "eIDAS attribute attestation",
        "urn:eudi:ehic:1" to EHIC_NAME,
        "urn:eu.europa.ec.eudi:ehic:1" to EHIC_NAME,
        "eu.europa.ec.eudi.ehic.1" to EHIC_NAME,
        "urn:eu.europa.ec.eudi:age:1" to "eIDAS age attribute set",
        "urn:eu.europa.ec.eudi:mdl:1" to "eIDAS driving licence attributes",
        "urn:eu.europa.ec.eudi:attributes:1" to "eIDAS general attributes",
        "org.iso.18013.5.1.mdl" to "Mobile driving licence (ISO 18013-5)",
        "org.iso.23220.photoid.1" to "Photo ID (ISO/IEC 23220)",
        "org.iso.23220.2.photoid.1" to "Photo ID (ISO/IEC 23220)",
        "org.iso.7367.1.mvrc" to "Vehicle registration certificate (ISO 7367)",
        "identity_proof_request" to "Identity proof request",
        "high_age_request" to "Age verification request",
        "email_proof_request" to "Email ownership proof",
        "phone_proof_request" to "Phone ownership proof",
        "wallet_unit_token" to "Wallet attestation",
    )

    private const val EUDI_PREFIX = "eu.europa.ec.eudi."

    /** Longest first: `_mdoc_deferred` must not lose only its `_mdoc`. */
    private val configurationSuffixes = listOf("_mdoc_deferred", "_vc_sd_jwt", "_sd_jwt_vc", "_mdoc")

    /** Configuration names whose attestation is not `eu.europa.ec.eudi.<name>.1`. */
    private val configurationAliases: Map<String, String> = mapOf(
        "pid" to "eu.europa.ec.eudi.pid.1",
        "ehic" to "eu.europa.ec.eudi.ehic.1",
        "mdl" to "org.iso.18013.5.1.mdl",
        "photoid" to "org.iso.23220.photoid.1",
    )

    /**
     * OpenID4VP `scope` shorthand — the EUDI reference implementation uses
     * terse scope names rather than full `vct` URNs. A scope that is a
     * credential identifier reads as one; anything else is title-cased.
     */
    fun scopeName(scope: String): String {
        val named = typeName(scope)
        if (scope.isNotEmpty() && named != scope) return named
        return scope.replace('_', ' ').asCapitalizedWords()
    }

    // MARK: Small utilities

    private fun nonEmpty(s: String?): String? = s?.takeIf { it.isNotEmpty() }

    /**
     * A query parameter carries a JSON document as text; inside a Request
     * Object the same parameter is the JSON object itself.
     */
    private fun jsonDocument(value: Any?): Map<*, *>? = when (value) {
        is String -> PayloadCodec.jsonObjectFromString(value)
        is Map<*, *> -> value
        else -> null
    }

    /** The claims of a compact JWS, unverified. */
    private fun jwtPayload(jwt: String): Map<String, Any?>? {
        val segments = jwt.split(".")
        if (segments.size != 3) return null
        return PayloadCodec.jsonObjectFromBase64URL(segments[1])
    }

    /**
     * The host a URL-valued parameter names (see [ShownHost]); null for a
     * URN or anything that is not a `scheme://` address.
     */
    private fun host(raw: String): String? {
        if (raw.startsWith("urn:")) return null
        return ShownHost.of(raw)
    }

    /** The last instant `iat` / `exp` may name: 9999-12-31T23:59:59Z. */
    private const val LATEST_DATE = 253_402_300_799.0

    /**
     * `iat` / `exp`: epoch seconds as a JSON number or as a string of ASCII
     * digits with at most one fraction, between 1970 and the end of 9999.
     * Anything else is no date: `(x * 1000).toLong()` would saturate 1e300
     * to year 292278994 and read "NaN" as 1970, where iOS printed nothing —
     * a different meaningless date on each phone. Mirrors iOS `date(from:)`.
     */
    private fun dateFrom(value: Any?): Date? {
        val seconds = when (value) {
            is Number -> value.toDouble()
            is String -> if (isDecimalNumeral(value)) value.toDoubleOrNull() else null
            else -> null
        } ?: return null
        if (seconds.isNaN() || seconds < 0 || seconds > LATEST_DATE) return null
        return Date((seconds * 1000).toLong())
    }

    /** `123` or `123.45`: ASCII digits, optionally one `.` and more digits. */
    private fun isDecimalNumeral(s: String): Boolean {
        val parts = s.split('.')
        return parts.size in 1..2 && parts.all { part -> part.isNotEmpty() && part.all { it in '0'..'9' } }
    }

    /**
     * A JSON array of strings, or null when the value is not one — Swift's
     * `as? [String]`, which fails outright on a mixed array.
     */
    private fun stringList(value: Any?): List<String>? =
        (value as? List<*>)?.map { it as? String ?: return null }

    /** Swift's `as? [[String]]`: every element an array of strings, or null. */
    private fun stringLists(value: Any?): List<List<String>>? =
        (value as? List<*>)?.map { stringList(it) ?: return null }

    /**
     * A JSON array of objects, or null when the value is not one — Swift's
     * `as? [[String: Any]]`. One stray string among the credential queries
     * voids the whole list there rather than being skipped, so it does
     * here: a sloppy request reads "Not stated" on both phones instead of
     * a different partial list on each.
     */
    private fun objectList(value: Any?): List<Map<*, *>>? =
        (value as? List<*>)?.map { it as? Map<*, *> ?: return null }

    private fun dedupe(items: List<String>): List<String> {
        val seen = HashSet<String>()
        return items.filter { seen.add(it) }
    }
}
