package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

/**
 * The eIDAS 2.0 / OpenID4VC codes added in 1.10: the
 * `openid4vp://authorize` presentation request, the
 * `openid-credential-offer://` offer, an SD-JWT VC token, and — just as
 * important — an ordinary login JWT that must stay plain text. The `ios…`
 * tests rebuild the iOS suite's fixtures byte for byte.
 */
@RunWith(RobolectricTestRunner::class)
class VerifiableCredentialPayloadTest {

    private val presentationRequest = "openid4vp://authorize" +
        "?client_id=https%3A%2F%2Fverifier.example.gov" +
        "&nonce=abc123&state=xyz789" +
        "&response_uri=https%3A%2F%2Fverifier.example.gov%2Fwallet" +
        "&dcql_query=%7B%22credentials%22%3A%5B%7B%22format%22%3A%22dc%2Bsd-jwt%22%2C" +
        "%22meta%22%3A%7B%22vct_values%22%3A%5B%22urn%3Aeu.europa.ec.eudi%3Apid%3A1%22%5D%7D%7D" +
        "%2C%7B%22format%22%3A%22mso_mdoc%22%7D%5D%7D"

    /** An OpenID4VCI 1.0 offer (§4.1.1 shape) for the EU reference issuer's PID. */
    private val credentialOffer = "openid-credential-offer://?credential_offer=" + alnumEncoded(
        "{\"credential_issuer\":\"https://issuer.example.eu\"," +
            "\"credential_configuration_ids\":[\"eu.europa.ec.eudi.pid_vc_sd_jwt\"]," +
            "\"grants\":{\"urn:ietf:params:oauth:grant-type:pre-authorized_code\":" +
            "{\"pre-authorized_code\":\"SplxlOBeZQQYbYS6WxSbIA\"," +
            "\"tx_code\":{\"length\":6,\"input_mode\":\"numeric\"}}}}"
    )

    private val sdJwtVc = "eyJhbGciOiJFUzI1NiIsInR5cCI6ImRjK3NkLWp3dCJ9" +
        ".eyJpc3MiOiJodHRwczovL2lzc3Vlci5leGFtcGxlLmV1IiwidmN0IjoidXJuOmV1LmV1cm9wYS5lYy5ldWRp" +
        "OnBpZDoxIiwiaWF0IjoxNzc4ODAzMjAwLCJleHAiOjE4MTAzMzkyMDAsIl9zZCI6WyJxMk5RMmZMLi4uIiwiS1" +
        "pmMGFRLi4uIiwiVG5SNmMuLi4iXSwiX3NkX2FsZyI6InNoYS0yNTYiLCJjbmYiOnsieCI6IlRuZWNIMC1qSDRF" +
        "OFJReHdFNWJFUG9EWUYzSlZBejhjVDBEeXFBIn19" +
        ".AQIDBAVzaWdu"

    private val loginJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
        ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNjAwMDAwMDAwLCJleHAiOjE3" +
        "MDAwMDAwMDB9" +
        ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    // ---- Presentation request -----------------------------------------

    @Test fun recognisesOpenID4VPRequest() {
        assertThat(VerifiableCredentialParser.looksLikeCredentialRequest(presentationRequest)).isTrue()
        assertThat(VerifiableCredentialParser.looksLikeCredentialToken(presentationRequest)).isFalse()
        // A bare https:// must never land here.
        assertThat(VerifiableCredentialParser.looksLikeCredentialRequest("https://example.com")).isFalse()
    }

    @Test fun parsesPresentationRequest() {
        val p = ScanPayloadParser.parse(presentationRequest)
        assertThat(p).isInstanceOf(ScanPayload.VerifiableCredential::class.java)
        assertThat(p.kindLabel).isEqualTo("Credential request")
        val vc = (p as ScanPayload.VerifiableCredential).payload
        assertThat(vc.kind).isEqualTo(VerifiableCredentialPayload.Kind.PRESENTATION_REQUEST)
        assertThat(vc.counterparty).isEqualTo("https://verifier.example.gov")
        assertThat(vc.counterpartyHost).isEqualTo("verifier.example.gov")
        assertThat(vc.counterpartyDescription).isEqualTo("verifier.example.gov")
        assertThat(vc.noncePresent).isTrue()
        assertThat(vc.statePresent).isTrue()
        assertThat(vc.responseURI).isEqualTo("https://verifier.example.gov/wallet")
        assertThat(vc.decoded).isTrue()
    }

    @Test fun namesTheRequestedCredentialsFromDCQL() {
        // A query with no `id` is named by its `meta.vct_values`, and failing
        // that by its `format` — what the iOS parser shows, and more use to
        // a reader than a generic "credential".
        val vc = VerifiableCredentialParser.parse(presentationRequest)!!
        assertThat(vc.requested).containsExactly(
            "EU Digital Identity — PID", "mso_mdoc",
        ).inOrder()
        assertThat(vc.requestedSummary)
            .isEqualTo("EU Digital Identity — PID + 1 more")
        val rows = vc.labelledFields().associate { it.label to it.value }
        assertThat(rows["Type"]).isEqualTo("Credential request")
        assertThat(rows["Asking for"])
            .isEqualTo("EU Digital Identity — PID + 1 more")
        assertThat(rows["Verifier"]).isEqualTo("verifier.example.gov")
        assertThat(rows["Answer goes to"]).isEqualTo("verifier.example.gov")
        assertThat(rows["Anti-replay"]).isEqualTo("Nonce + state")
        assertThat(rows["↳"]).isEqualTo("mso_mdoc")
    }

    @Test fun dcqlNamesByMetaThenIdThenFormat() {
        // `meta` says what the credential is; `id` is only the verifier's
        // handle for it ("pid"), so `meta` wins when both are there.
        val dcql = "{\"credentials\":[{\"id\":\"eu.europa.ec.eudi.pid.1\"}," +
            "{\"format\":\"dc+sd-jwt\",\"meta\":{\"vct_values\":[\"urn:eu.europa.ec.eudi:ehic:1\"]}}," +
            "{\"id\":\"pid\",\"format\":\"dc+sd-jwt\",\"meta\":{\"vct_values\":[]}}," +
            "{\"id\":\"ehic\",\"format\":\"mso_mdoc\",\"meta\":{\"doctype_value\":\"eu.europa.ec.eudi.ehic.1\"}}," +
            "{\"format\":\"mso_mdoc\"},{}]}"
        val vc = VerifiableCredentialParser.parse(
            "openid4vp://authorize?client_id=https%3A%2F%2Fv.example&nonce=1&dcql_query=" + alnumEncoded(dcql)
        )!!
        assertThat(vc.requested).containsExactly(
            "EU Digital Identity — PID",
            "European Health Insurance Card", "pid", "mso_mdoc", "credential",
        ).inOrder()
        assertThat(vc.format).isEqualTo("dc+sd-jwt, mso_mdoc")
    }

    @Test fun aMixedCredentialListIsVoidedNotPartlyRead() {
        // A pre-1.0 offer's `credentials` may mix configuration ids and
        // objects, so each entry is read on its own; an entry naming no
        // credential is skipped, not guessed at.
        val offer = "{\"credential_issuer\":\"https://i.example\",\"formats\":[\"jwt_vc_json\"]," +
            "\"credentials\":[\"UniversityDegree\",{\"credential_issuer\":\"https://other.example/x\"}]}"
        val vc = VerifiableCredentialParser.parse(
            "openid-credential-offer://?credential_offer=" + alnumEncoded(offer)
        )!!
        assertThat(vc.kind).isEqualTo(VerifiableCredentialPayload.Kind.CREDENTIAL_OFFER)
        assertThat(vc.requested).containsExactly("UniversityDegree")
        assertThat(vc.format).isNull()
        assertThat(vc.decoded).isTrue()
        val rows = vc.labelledFields().associate { it.label to it.value }
        assertThat(rows["Offering"]).isEqualTo("UniversityDegree")
        assertThat(rows["Issuer"]).isEqualTo("i.example")
        assertThat(rows).doesNotContainKey("Claims")

        // iOS casts DCQL `credentials` to an array of objects: one stray
        // string voids the list. Reading the rest would show a different
        // partial answer on each platform.
        val dcql = "{\"credentials\":[\"pid\",{\"id\":\"eu.europa.ec.eudi.pid.1\"}]}"
        val request = VerifiableCredentialParser.parse(
            "openid4vp://authorize?client_id=https%3A%2F%2Fv.example&dcql_query=" + alnumEncoded(dcql)
        )!!
        assertThat(request.requested).isEmpty()
    }

    @Test fun namesPresentationDefinitionDescriptors() {
        val pd = "{\"id\":\"pd\",\"input_descriptors\":[{\"id\":\"eu.europa.ec.eudi.pid.1\",\"name\":\"\"}," +
            "{\"name\":\"Proof of age\"},{\"constraints\":{\"fields\":[{\"path\":[\"${'$'}.vct\"]}]}},{}]}"
        val vc = VerifiableCredentialParser.parse(
            "openid4vp://?client_id=https%3A%2F%2Fv.example&nonce=1&presentation_definition=" +
                alnumEncoded(pd) + "&presentation_definition_uri=https%3A%2F%2Fv.example%2Fpd.json"
        )!!
        assertThat(vc.requested).containsExactly(
            "EU Digital Identity — PID", "Proof of age", "${'$'}.vct",
            "credential", "presentation definition fetched from v.example",
        ).inOrder()
        assertThat(vc.requestedSummary)
            .isEqualTo("EU Digital Identity — PID + 4 more")
    }

    @Test fun namesScopeShorthand() {
        val scope = "org.iso.18013.5.1.mDL eu.europa.ec.eudi.pid.1 some_brand_scope identity_proof_request"
        val vc = VerifiableCredentialParser.parse(
            "openid4vp://?client_id=https%3A%2F%2Fv.example&nonce=abc&format=jwt_vp&scope=" + alnumEncoded(scope)
        )!!
        assertThat(vc.requested).containsExactly(
            "Mobile driving licence (ISO 18013-5)", "EU Digital Identity — PID",
            "Some Brand Scope", "Identity proof request",
        ).inOrder()
        assertThat(vc.format).isEqualTo("jwt_vp")
    }

    @Test fun aRequestWithNoProtocolContentIsFlaggedUndecoded() {
        val vc = VerifiableCredentialParser.parse("openid4vp://authorize")!!
        assertThat(vc.decoded).isFalse()
        assertThat(vc.labelledFields().associate { it.label to it.value }["Claims"])
            .isEqualTo("Not decodable — possibly a newer format")
        assertThat(VerifiableCredentialParser.looksLikeCredentialRequest("openid4vp://authorize")).isFalse()
    }

    @Test fun warnsWhenTheRequestCarriesNoNonce() {
        val bare = "openid4vp://authorize?client_id=https%3A%2F%2Fverifier.example.gov"
        val vc = VerifiableCredentialParser.parse(bare)!!
        assertThat(vc.noncePresent).isFalse()
        assertThat(vc.decoded).isTrue()
        assertThat(vc.labelledFields().associate { it.label to it.value }["Anti-replay"])
            .isEqualTo("Missing — can be replayed")
        assertThat(vc.cautions()).contains(
            "No nonce: the same code can be replayed, so treat its contents as untrusted."
        )
        assertThat(vc.cautions().first()).startsWith("Only approve a request you started yourself")
    }

    // ---- Credential offer ---------------------------------------------

    @Test fun parsesCredentialOffer() {
        assertThat(VerifiableCredentialParser.looksLikeCredentialRequest(credentialOffer)).isTrue()
        val p = ScanPayloadParser.parse(credentialOffer)
        assertThat(p).isInstanceOf(ScanPayload.VerifiableCredential::class.java)
        assertThat(p.kindLabel).isEqualTo("Credential offer")
        val vc = (p as ScanPayload.VerifiableCredential).payload
        assertThat(vc.kind).isEqualTo(VerifiableCredentialPayload.Kind.CREDENTIAL_OFFER)
        assertThat(vc.counterparty).isEqualTo("https://issuer.example.eu")
        assertThat(vc.counterpartyHost).isEqualTo("issuer.example.eu")
        assertThat(vc.requested).containsExactly(
            "EU Digital Identity — PID"
        )
        val rows = vc.labelledFields().associate { it.label to it.value }
        assertThat(rows["Offering"]).isEqualTo("EU Digital Identity — PID")
        assertThat(rows["Issuer"]).isEqualTo("issuer.example.eu")
        assertThat(rows["Transaction code"]).isEqualTo("Required — 6 digits")
        assertThat(rows).doesNotContainKey("Format")
        assertThat(rows).doesNotContainKey("Anti-replay")
        assertThat(vc.labelledFields().none { it.value.contains("SplxlOBeZQQYbYS6WxSbIA") }).isTrue()
        assertThat(vc.cautions())
            .contains(
                "Installing a credential from an unsolicited QR is how fake wallets " +
                    "and impersonation kits spread."
            )
    }

    // ---- SD-JWT VC token ----------------------------------------------

    @Test fun recognisesAndParsesSDJwtCredential() {
        assertThat(VerifiableCredentialParser.looksLikeCredentialToken(sdJwtVc)).isTrue()
        val p = ScanPayloadParser.parse(sdJwtVc)
        assertThat(p).isInstanceOf(ScanPayload.VerifiableCredential::class.java)
        assertThat(p.kindLabel).isEqualTo("Verifiable credential")
        val vc = (p as ScanPayload.VerifiableCredential).payload
        assertThat(vc.kind).isEqualTo(VerifiableCredentialPayload.Kind.CREDENTIAL)
        assertThat(vc.issuer).isEqualTo("https://issuer.example.eu")
        assertThat(vc.counterpartyHost).isEqualTo("issuer.example.eu")
        assertThat(vc.credentialType).isEqualTo("urn:eu.europa.ec.eudi:pid:1")
        assertThat(vc.issuedAt).isEqualTo(Date(1778803200L * 1000))
        assertThat(vc.expiresAt).isEqualTo(Date(1810339200L * 1000))
        assertThat(vc.selectivelyDisclosable).isEqualTo(3)
        assertThat(vc.keyBinding).isTrue()
        assertThat(vc.format).isEqualTo("dc+sd-jwt")
        assertThat(vc.decoded).isTrue()

        val rows = vc.labelledFields().associate { it.label to it.value }
        assertThat(rows["Type"]).isEqualTo("Verifiable credential")
        assertThat(rows["Credential"])
            .isEqualTo("EU Digital Identity — PID")
        assertThat(rows["Issuer"]).isEqualTo("issuer.example.eu")
        assertThat(rows["Hidden claims"]).isEqualTo("3 (selective disclosure)")
        assertThat(rows["Proof of possession"]).isEqualTo("Bound to a device key")
        assertThat(rows.keys).containsAtLeast("Issued", "Expires")
        assertThat(vc.cautions().first()).startsWith("This is the credential's own text.")
    }

    @Test fun doesNotAbsorbAnOrdinaryLoginJWT() {
        assertThat(VerifiableCredentialParser.looksLikeCredentialToken(loginJwt)).isFalse()
        assertThat(VerifiableCredentialParser.looksLikeCredentialRequest(loginJwt)).isFalse()
        val p = ScanPayloadParser.parse(loginJwt)
        assertThat(p).isInstanceOf(ScanPayload.Text::class.java)
    }

    // ---- Naming tables -------------------------------------------------

    @Test fun translatesKnownTypeIdentifiers() {
        assertThat(VerifiableCredentialParser.typeName("urn:eu.europa.ec.eudi:ehic:1"))
            .isEqualTo("European Health Insurance Card")
        assertThat(VerifiableCredentialParser.typeName("eu.europa.ec.eudi.mdl.1"))
            .isEqualTo("eIDAS MDL attribute set")
        assertThat(VerifiableCredentialParser.typeName(null)).isEqualTo("Not stated")
        assertThat(VerifiableCredentialParser.typeName("https://issuer.example.gov/types/1"))
            .isEqualTo("issuer.example.gov")
        assertThat(VerifiableCredentialParser.isKnownType("urn:eu.europa.ec.eudi:ehic:1")).isTrue()
        assertThat(VerifiableCredentialParser.isKnownType("urn:example:whatever")).isFalse()
        assertThat(VerifiableCredentialParser.scopeName("identity_proof_request"))
            .isEqualTo("Identity proof request")
        assertThat(VerifiableCredentialParser.scopeName("high_age_request"))
            .isEqualTo("Age verification request")
        assertThat(VerifiableCredentialParser.scopeName("some_brand_scope"))
            .isEqualTo("Some Brand Scope")
    }

    @Test fun presentationContextNamesTheTypeOnlyWhenEveryEntryIsAString() {
        fun vp(context: String) = VerifiableCredentialParser.parse(
            base64URL("{\"alg\":\"ES256\",\"typ\":\"JWT\"}") + "." +
                base64URL("{\"iss\":\"did:example:holder\",\"vp\":{\"@context\":$context,\"verifiableCredential\":[]}}") +
                ".c2ln"
        )!!
        // iOS reads `@context` as `[String]`, so one JSON-LD object in it
        // means no type at all rather than "the last string".
        assertThat(vp("[\"https://www.w3.org/2018/credentials/v1\",{\"@vocab\":\"x\"},\"https://x.example/ctx\"]").credentialType)
            .isNull()
        val plain = vp("[\"https://www.w3.org/2018/credentials/v1\",\"https://x.example/ctx\"]")
        assertThat(plain.credentialType).isEqualTo("https://x.example/ctx")
        assertThat(plain.kind).isEqualTo(VerifiableCredentialPayload.Kind.PRESENTATION)
        assertThat(plain.labelledFields().associate { it.label to it.value }["Credential"]).isEqualTo("x.example")
    }

    // ---- The iOS suite's fixtures (ScanTests: eIDAS 2.0 / OpenID4VP) --

    @Test fun iosParsesOpenID4VPPresentationRequest() {
        val dcql = "{\"credentials\":[{\"id\":\"eu.europa.ec.eudi.pid.1\",\"format\":\"dc+sd-jwt\"}]}"
        val raw = "openid4vp://authorize?client_id=https%3A%2F%2Fverifier.example.gov" +
            "&response_uri=https%3A%2F%2Fwallet.example%2Fcb&nonce=abc123&state=xyz" +
            "&dcql_query=" + alnumEncoded(dcql)
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.VerifiableCredential::class.java)
        val vc = (p as ScanPayload.VerifiableCredential).payload
        assertThat(vc.kind).isEqualTo(VerifiableCredentialPayload.Kind.PRESENTATION_REQUEST)
        assertThat(vc.counterpartyHost).isEqualTo("verifier.example.gov")
        assertThat(vc.noncePresent).isTrue()
        assertThat(vc.statePresent).isTrue()
        assertThat(vc.requested.first()).contains("PID")
        assertThat(vc.responseURI).contains("wallet.example")
        assertThat(vc.labelledFields().map { it.label }).contains("Asking for")
        assertThat(vc.cautions().any { it.contains("started") }).isTrue()
    }

    @Test fun iosParsesCredentialOffer() {
        val offer = "{\"credential_issuer\":\"https://issuer.example\"," +
            "\"credential_configuration_ids\":[\"urn:eu.europa.ec.eudi:pid:1\"]}"
        val p = ScanPayloadParser.parse("openid-credential-offer://?credential_offer=" + alnumEncoded(offer))
        assertThat(p).isInstanceOf(ScanPayload.VerifiableCredential::class.java)
        val vc = (p as ScanPayload.VerifiableCredential).payload
        assertThat(vc.kind).isEqualTo(VerifiableCredentialPayload.Kind.CREDENTIAL_OFFER)
        assertThat(vc.requested.first()).contains("PID")
        assertThat(vc.cautions().any { it.contains("unsolicited") }).isTrue()
    }

    @Test fun iosParsesSDJWTVerifiableCredential() {
        val header = base64URL("{\"alg\":\"ES256\",\"typ\":\"dc+sd-jwt\"}")
        val claims = base64URL(
            "{\"iss\":\"https://issuer.example\",\"iat\":1750000000,\"exp\":1800000000," +
                "\"vct\":\"urn:eu.europa.ec.eudi:pid:1\",\"_sd\":[\"a\",\"b\",\"c\"]," +
                "\"cnf\":{\"x\":1}}"
        )
        val token = "$header.$claims.Zm9vYmFy"
        assertThat(VerifiableCredentialParser.looksLikeCredentialToken(token)).isTrue()
        val p = ScanPayloadParser.parse(token)
        assertThat(p).isInstanceOf(ScanPayload.VerifiableCredential::class.java)
        val vc = (p as ScanPayload.VerifiableCredential).payload
        assertThat(vc.kind).isEqualTo(VerifiableCredentialPayload.Kind.CREDENTIAL)
        assertThat(vc.issuer).isEqualTo("https://issuer.example")
        assertThat(vc.selectivelyDisclosable).isEqualTo(3)
        assertThat(vc.keyBinding).isTrue()
        assertThat(vc.issuedAt).isEqualTo(Date(1_750_000_000L * 1000))
        assertThat(vc.labelledFields().any { it.value.contains("PID") }).isTrue()
        assertThat(vc.labelledFields().map { it.label }).contains("Issued")
        // A key-binding JWT after the signature is still the same credential.
        assertThat(VerifiableCredentialParser.parse("$token.a2I")?.kind)
            .isEqualTo(VerifiableCredentialPayload.Kind.CREDENTIAL)
    }

    @Test fun iosOrdinaryLoginJWTStaysText() {
        val plain = base64URL("{\"alg\":\"HS256\"}") + "." +
            base64URL("{\"sub\":\"12345\",\"exp\":1800000000}") + ".c2ln"
        assertThat(VerifiableCredentialParser.looksLikeCredentialToken(plain)).isFalse()
        assertThat(ScanPayloadParser.parse(plain).kindLabel).isEqualTo("Text")
    }

    @Test fun presentationIsClassifiedFromVpClaim() {
        val header = base64URL("""{"alg":"ES256","typ":"kb+jwt"}""")
        val claims = base64URL(
            "{\"iss\":\"https://verifier.example.gov\",\"nonce\":\"n-1\"," +
                "\"vp\":{\"verifiableCredential\":[{\"id\":\"https://issuer.example.eu/c/1\"}]}}"
        )
        val token = "$header.$claims.sig-1"
        assertThat(VerifiableCredentialParser.looksLikeCredentialToken(token)).isTrue()
        val vc = VerifiableCredentialParser.parse(token)!!
        assertThat(vc.kind).isEqualTo(VerifiableCredentialPayload.Kind.PRESENTATION)
        assertThat(vc.counterparty).isEqualTo("https://verifier.example.gov")
        assertThat(vc.requested).containsExactly("https://issuer.example.eu/c/1")
        assertThat(vc.format).isEqualTo("kb+jwt")
    }

    // ---- OpenID4VP 1.0 / OpenID4VCI 1.0 / HAIP spec fixtures ----------
    //
    // The same inputs as the iOS `CredentialAndPassportSpecTests`, byte for
    // byte, with the same expected rows.

    private val pidName = "EU Digital Identity — PID"
    private val mdlName = "Mobile driving licence (ISO 18013-5)"
    private val approveOnlyYours = "Only approve a request you started yourself — approving one " +
        "someone sent you releases your data to them."
    private val unsolicited = "Installing a credential from an unsolicited QR is how fake wallets " +
        "and impersonation kits spread."

    /** HAIP 1.0: signed request by reference, `x509_hash` verifier, POST fetch. */
    private val haipRequest = "haip-vp://?client_id=x509_hash%3AUvo3HtuIxuhC92rShpgqcT3YXwrqRxWEviRiA0OZszk" +
        "&request_uri=https%3A%2F%2Fverifier.example.org%2Frequest%2Fvapof4ql2i7m41m68uep" +
        "&request_uri_method=post"

    /** OpenID4VP 1.0 §5.10's by-reference shape with an `x509_san_dns` verifier. */
    private val x509DnsRequest = "openid4vp://?client_id=x509_san_dns%3Aclient.example.org" +
        "&request_uri=https%3A%2F%2Fclient.example.org%2F567545564"

    /** The IANA registration's example for ISO/IEC 18013-7 Annex B. */
    private val mdocRequest = "mdoc-openid4vp://?client_id=example.com" +
        "&request_uri=https%3A%2F%2Fexample.com%2F567545564"

    /** A by-value request for a PID and an mDL (OpenID4VP 1.0 Appendix D.2 shape). */
    private val dcqlRequest = "openid4vp://?response_type=vp_token&response_mode=direct_post" +
        "&client_id=redirect_uri%3Ahttps%3A%2F%2Fclient.example.org%2Fpost" +
        "&response_uri=https%3A%2F%2Fclient.example.org%2Fpost" +
        "&nonce=n-0S6_WzA2Mj&state=eyJhb4f&dcql_query=" + alnumEncoded(
            """{"credentials":[{"id":"pid","format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:pid:1"]},""" +
                """"claims":[{"path":["given_name"]},{"path":["family_name"]},{"path":["address","street_address"]}]},""" +
                """{"id":"mdl","format":"mso_mdoc","meta":{"doctype_value":"org.iso.18013.5.1.mDL"},""" +
                """"claims":[{"path":["org.iso.18013.5.1","given_name"]},{"path":["org.iso.18013.5.1","portrait"]}]}]}"""
        )

    /** OpenID4VCI 1.0 §4.1.2's by-value offer URI, verbatim. */
    private val specOffer = "openid-credential-offer://?credential_offer=%7B%22credential_issuer%22:" +
        "%22https://credential-issuer.example.com%22,%22credential_configuration_ids%22:" +
        "%5B%22org.iso.18013.5.1.mDL%22%5D,%22grants%22:%7B%22urn:ietf:params:oauth:grant-type:" +
        "pre-authorized_code%22:%7B%22pre-authorized_code%22:%22oaKazRN8I0IbtZ0C7JuMn5%22," +
        "%22tx_code%22:%7B%22input_mode%22:%22text%22,%22description%22:%22Please%20enter%20the%20" +
        "serial%20number%20of%20your%20physical%20drivers%20license%22%7D%7D%7D%7D"

    /** OpenID4VCI 1.0 §4.1.3's by-reference offer URI, verbatim. */
    private val specOfferByReference = "openid-credential-offer://?credential_offer_uri=" +
        "https%3A%2F%2Fserver%2Eexample%2Ecom%2Fcredential-offer%2FGkurKxf5T0Y-mnPFCHqWOMiZi4VS138cQO_V7PZHAdM"

    private fun offer(json: String) =
        VerifiableCredentialParser.parse("openid-credential-offer://?credential_offer=" + alnumEncoded(json))!!

    private fun rowsOf(p: VerifiableCredentialPayload) = p.labelledFields().associate { it.label to it.value }

    @Test fun haipByReferenceRequestIsNotAReplayAlarm() {
        assertThat(VerifiableCredentialParser.looksLikeCredentialRequest(haipRequest)).isTrue()
        val p = ScanPayloadParser.parse(haipRequest)
        assertThat(p).isInstanceOf(ScanPayload.VerifiableCredential::class.java)
        val vc = (p as ScanPayload.VerifiableCredential).payload
        assertThat(vc.kind).isEqualTo(VerifiableCredentialPayload.Kind.PRESENTATION_REQUEST)
        assertThat(vc.byReference).isTrue()
        assertThat(vc.referenceHost).isEqualTo("verifier.example.org")
        assertThat(vc.counterpartyHost).isNull()
        assertThat(vc.decoded).isTrue()
        assertThat(vc.labelledFields()).containsExactly(
            LabelledField("Type", "Credential request"),
            LabelledField("Asking for", "Not in the code — your wallet fetches the details"),
            LabelledField("Verifier", "Unnamed — identified by certificate hash"),
            LabelledField("Request", "Your wallet fetches it from verifier.example.org by POST"),
            LabelledField("Anti-replay", "Inside the fetched request — not in the code"),
        ).inOrder()
        assertThat(vc.cautions()).containsExactly(approveOnlyYours)
        assertThat(vc.counterpartyDescription).isEqualTo("a verifier identified only by a certificate hash")
        assertThat(vc.footnote).isEqualTo(
            "Scan decodes this code to explain it. It does not fetch the request, answer it, " +
                "or verify any signature."
        )
    }

    @Test fun byReferenceRequestNamesTheDnsVerifierAndTheFetch() {
        val vc = VerifiableCredentialParser.parse(x509DnsRequest)!!
        val rows = rowsOf(vc)
        assertThat(rows["Verifier"]).isEqualTo("client.example.org")
        assertThat(rows["Request"]).isEqualTo("Your wallet fetches it from client.example.org")
        assertThat(rows["Anti-replay"]).isEqualTo("Inside the fetched request — not in the code")
        assertThat(vc.counterpartyHost).isEqualTo("client.example.org")
        assertThat(vc.clientIDPrefix).isEqualTo("x509_san_dns")
        assertThat(vc.cautions()).containsExactly(approveOnlyYours)
    }

    @Test fun mdocOpenID4VPRequestFromISO18013Part7() {
        assertThat(VerifiableCredentialParser.looksLikeCredentialRequest(mdocRequest)).isTrue()
        val p = ScanPayloadParser.parse(mdocRequest)
        assertThat(p).isInstanceOf(ScanPayload.VerifiableCredential::class.java)
        val vc = (p as ScanPayload.VerifiableCredential).payload
        assertThat(vc.labelledFields()).containsExactly(
            LabelledField("Type", "Credential request"),
            LabelledField("Format", "mso_mdoc"),
            LabelledField("Asking for", "Not in the code — your wallet fetches the details"),
            LabelledField("Verifier", "example.com"),
            LabelledField("Request", "Your wallet fetches it from example.com"),
            LabelledField("Anti-replay", "Inside the fetched request — not in the code"),
        ).inOrder()
    }

    @Test fun dcqlQueriesAreNamedByWhatTheyAskFor() {
        val vc = VerifiableCredentialParser.parse(dcqlRequest)!!
        assertThat(vc.requested).containsExactly(pidName, mdlName).inOrder()
        assertThat(vc.claims).containsExactly(
            "given_name", "family_name", "address.street_address", "portrait",
        ).inOrder()
        assertThat(vc.labelledFields()).containsExactly(
            LabelledField("Type", "Credential request"),
            LabelledField("Format", "dc+sd-jwt, mso_mdoc"),
            LabelledField("Asking for", "$pidName + 1 more"),
            LabelledField("Verifier", "client.example.org"),
            LabelledField("Answer goes to", "client.example.org"),
            LabelledField("Anti-replay", "Nonce + state"),
            LabelledField("↳", mdlName),
            LabelledField("Claims asked for", "given_name, family_name, address.street_address, portrait"),
        ).inOrder()
        // The verifier-local query ids are never what the user is shown.
        assertThat(vc.labelledFields().map { it.value }).containsNoneOf("pid", "mdl")
        assertThat(vc.clientIDPrefix).isEqualTo("redirect_uri")
        assertThat(vc.cautions()).containsExactly(approveOnlyYours, biometricCaution("portrait")).inOrder()
        assertThat(vc.footnote).isEqualTo(
            "Scan decodes this code to explain it. It does not answer the request or verify its signature."
        )
    }

    @Test fun dcqlSpecExampleAndClaimPaths() {
        fun request(dcql: String) = VerifiableCredentialParser.parse(
            "openid4vp://?client_id=redirect_uri%3Ahttps%3A%2F%2Fv.example%2Fcb&nonce=1&dcql_query=" +
                alnumEncoded(dcql)
        )!!
        // OpenID4VP 1.0 Appendix D.1, verbatim.
        val d1 = request(
            """{"credentials":[{"id":"my_credential","format":"mso_mdoc",""" +
                """"meta":{"doctype_value":"org.iso.7367.1.mVRC"},"claims":[""" +
                """{"path":["org.iso.7367.1","vehicle_holder"]},{"path":["org.iso.18013.5.1","first_name"]}]}]}"""
        )
        assertThat(d1.requested).containsExactly("Vehicle registration certificate (ISO 7367)")
        assertThat(d1.claims).containsExactly("vehicle_holder", "first_name").inOrder()

        // Array indices and the null wildcard drop out of a path; a path of
        // nothing but them is no claim at all.
        val paths = request(
            """{"credentials":[{"id":"pid","format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:pid:1"]},""" +
                """"claims":[{"path":["nationalities",null]},{"path":["degrees",0,"type"]},{"path":[1]},""" +
                """{"path":"given_name"},{"id":"x"}]}]}"""
        )
        assertThat(paths.claims).containsExactly("nationalities", "degrees.type").inOrder()

        // Twelve claims shown, the rest counted.
        val many = (1..15).joinToString(",") { "{\"path\":[\"c$it\"]}" }
        val capped = request(
            """{"credentials":[{"id":"pid","format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:pid:1"]},""" +
                """"claims":[$many]}]}"""
        )
        assertThat(rowsOf(capped)["Claims asked for"])
            .isEqualTo("c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12 + 3 more")
    }

    @Test fun signedRequestObjectByValueCarriesItsNonce() {
        val payload = """{"client_id":"x509_san_dns:client.example.org","response_type":"vp_token",""" +
            """"response_mode":"direct_post","response_uri":"https://client.example.org/post",""" +
            """"nonce":"n-0S6_WzA2Mj","dcql_query":{"credentials":[{"id":"pid","format":"dc+sd-jwt",""" +
            """"meta":{"vct_values":["urn:eudi:pid:1"]}}]}}"""
        val jwt = base64URL("""{"alg":"ES256","typ":"oauth-authz-req+jwt"}""") + "." + base64URL(payload) + ".c2ln"
        val vc = VerifiableCredentialParser.parse(
            "openid4vp://?client_id=x509_san_dns%3Aclient.example.org&request=$jwt"
        )!!
        assertThat(vc.noncePresent).isTrue()
        assertThat(vc.requested).containsExactly(pidName)
        val rows = rowsOf(vc)
        assertThat(rows["Anti-replay"]).isEqualTo("Nonce present")
        assertThat(rows["Answer goes to"]).isEqualTo("client.example.org")
        assertThat(rows["Verifier"]).isEqualTo("client.example.org")
        assertThat(vc.cautions()).containsExactly(approveOnlyYours)

        // A request object that does not decode may still hold a nonce.
        val opaque = VerifiableCredentialParser.parse(
            "openid4vp://?client_id=x509_san_dns%3Aclient.example.org&request=a.b.c"
        )!!
        assertThat(opaque.signedRequestUnreadable).isTrue()
        assertThat(rowsOf(opaque)["Anti-replay"]).isEqualTo("Inside the signed request — not readable")
        assertThat(opaque.cautions()).containsExactly(approveOnlyYours)
    }

    @Test fun clientIdentifierPrefixesNameTheVerifier() {
        fun verifier(clientID: String, extra: String = "") = VerifiableCredentialParser.parse(
            "openid4vp://?client_id=" + alnumEncoded(clientID) + extra +
                "&request_uri=https%3A%2F%2Fverifier.example.org%2Fr%2F1"
        )!!
        val cases = listOf(
            "redirect_uri:https://client.example.org/cb" to "client.example.org",
            "x509_san_dns:client.example.org" to "client.example.org",
            "x509_hash:Uvo3HtuIxuhC92rShpgqcT3YXwrqRxWEviRiA0OZszk" to "Unnamed — identified by certificate hash",
            "decentralized_identifier:did:example:123" to "did:example:123",
            "decentralized_identifier:did:web:verifier.example.org" to "verifier.example.org",
            "decentralized_identifier:did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK"
                to "Unnamed — a bare key (did:key)",
            "verifier_attestation:verifier.example" to "verifier.example",
            "openid_federation:https://federation-verifier.example.com" to "federation-verifier.example.com",
            "origin:https://verifier.example.com/" to "verifier.example.com",
            "s6BhdRkqt3" to "s6BhdRkqt3",
            "https://verifier.example.gov" to "verifier.example.gov",
            "did:example:123" to "did:example:123",
        )
        for ((clientID, expected) in cases) {
            assertThat(rowsOf(verifier(clientID))["Verifier"]).isEqualTo(expected)
        }
        // A pre-1.0 request named its prefix in `client_id_scheme`.
        assertThat(rowsOf(verifier("verifier.example.org", "&client_id_scheme=x509_san_dns"))["Verifier"])
            .isEqualTo("verifier.example.org")
        assertThat(verifier("decentralized_identifier:did:web:verifier.example.org").counterpartyHost)
            .isEqualTo("verifier.example.org")
        // `origin:` is reserved for browser requests; a QR must not use it.
        assertThat(verifier("origin:https://verifier.example.com/").cautions()).contains(
            "The verifier uses the origin: prefix, which is reserved for browser requests — " +
                "a wallet must refuse it in a QR code."
        )
    }

    @Test fun onlyRealWalletSchemesAreClaimed() {
        assertThat(VerifiableCredentialParser.requestSchemes).containsExactly(
            "openid4vp", "eudi-openid4vp", "mdoc-openid4vp", "haip-vp", "haip",
            "openid-vc", "openid-credential-offer", "haip-vci",
        )
        assertThat(VerifiableCredentialParser.looksLikeCredentialRequest("pre-authorized-code://?client_id=x")).isFalse()
        assertThat(VerifiableCredentialParser.looksLikeCredentialRequest("openid-fhie://?client_id=x")).isFalse()
        assertThat(
            VerifiableCredentialParser.looksLikeCredentialRequest(
                "eudi-openid4vp://?client_id=x509_san_dns%3Averifier.example.org" +
                    "&request_uri=https%3A%2F%2Fverifier.example.org%2Fr%2F1"
            )
        ).isTrue()
        val vci = VerifiableCredentialParser.parse("haip-vci://?credential_offer_uri=https%3A%2F%2Fissuer.example.eu%2Fo%2F9")!!
        assertThat(vci.kind).isEqualTo(VerifiableCredentialPayload.Kind.CREDENTIAL_OFFER)
        assertThat(rowsOf(vci)["Offer"]).isEqualTo("Your wallet fetches it from issuer.example.eu")

        // Microsoft Entra Verified ID: a request_uri and nothing else.
        val entra = VerifiableCredentialParser.parse("openid-vc://?request_uri=https%3A%2F%2Fverifiedid.example%2Freq%2F1")!!
        assertThat(entra.decoded).isTrue()
        assertThat(rowsOf(entra)["Verifier"]).isEqualTo("Not in the code")
        assertThat(rowsOf(entra)).doesNotContainKey("Claims")
    }

    @Test fun specCredentialOfferByValue() {
        assertThat(VerifiableCredentialParser.looksLikeCredentialRequest(specOffer)).isTrue()
        val p = ScanPayloadParser.parse(specOffer)
        assertThat(p).isInstanceOf(ScanPayload.VerifiableCredential::class.java)
        val vc = (p as ScanPayload.VerifiableCredential).payload
        assertThat(vc.labelledFields()).containsExactly(
            LabelledField("Type", "Credential offer"),
            LabelledField("Offering", mdlName),
            LabelledField("Issuer", "credential-issuer.example.com"),
            LabelledField("Sign-in", "Not needed — pre-authorised by the issuer"),
            LabelledField("Transaction code", "Required"),
        ).inOrder()
        assertThat(vc.cautions()).containsExactly(unsolicited)
        assertThat(vc.footnote).isEqualTo(
            "Scan decodes this code to explain it. It does not redeem the offer or contact the issuer."
        )
        // The pre-authorised code is a bearer secret.
        assertThat(vc.labelledFields().none { it.value.contains("oaKazRN8I0IbtZ0C7JuMn5") }).isTrue()
        assertThat(vc.explanationPoints().none { it.contains("oaKazRN8I0IbtZ0C7JuMn5") }).isTrue()
    }

    @Test fun credentialOfferGrants() {
        val issuer = "\"credential_issuer\":\"https://credential-issuer.example.com\""
        val pre = "\"urn:ietf:params:oauth:grant-type:pre-authorized_code\""
        // OpenID4VCI 1.0 §4.1.1.
        val twoConfigs = offer(
            "{$issuer,\"credential_configuration_ids\":[\"UniversityDegreeCredential\",\"org.iso.18013.5.1.mDL\"]," +
                "\"grants\":{$pre:{\"pre-authorized_code\":\"oaKazRN8I0IbtZ0C7JuMn5\",\"tx_code\":{\"length\":4," +
                "\"input_mode\":\"numeric\",\"description\":\"Please provide the one-time code that was sent via e-mail\"}}}}"
        )
        assertThat(rowsOf(twoConfigs)["Offering"]).isEqualTo("UniversityDegreeCredential + 1 more")
        assertThat(rowsOf(twoConfigs)["↳"]).isEqualTo(mdlName)
        assertThat(rowsOf(twoConfigs)["Transaction code"]).isEqualTo("Required — 4 digits")
        assertThat(twoConfigs.cautions()).containsExactly(unsolicited)

        // §4.1.3, authorization code flow: the issuer_state stays out of sight.
        val signIn = offer(
            "{$issuer,\"credential_configuration_ids\":[\"UniversityDegreeCredential\"]," +
                "\"grants\":{\"authorization_code\":{\"issuer_state\":\"eyJhbGciOiJSU0Et...FYUaBy\"}}}"
        )
        assertThat(rowsOf(signIn)["Sign-in"]).isEqualTo("You'll sign in at the issuer")
        assertThat(rowsOf(signIn)).doesNotContainKey("Transaction code")
        assertThat(signIn.labelledFields().none { it.value.contains("eyJhbGciOiJSU0Et") }).isTrue()

        // §4.1.3, an empty tx_code still means a code will be asked for.
        val empty = offer(
            "{$issuer,\"credential_configuration_ids\":[\"UniversityDegree_LDP_VC\"]," +
                "\"grants\":{$pre:{\"pre-authorized_code\":\"adhjhdjajkdkhjhdj\",\"tx_code\":{}}}}"
        )
        assertThat(rowsOf(empty)["Transaction code"]).isEqualTo("Required")

        // No tx_code: a bearer offer.
        val bearer = offer(
            "{$issuer,\"credential_configuration_ids\":[\"eu.europa.ec.eudi.pid_mdoc\"]," +
                "\"grants\":{$pre:{\"pre-authorized_code\":\"adhjhdjajkdkhjhdj\"}}}"
        )
        assertThat(rowsOf(bearer)["Transaction code"])
            .isEqualTo("Not required — whoever redeems the code gets the credential")
        assertThat(bearer.cautions()).containsExactly(
            unsolicited,
            "Pre-authorised with no transaction code: whoever redeems this code first gets the " +
                "credential, so do not share or post it.",
        ).inOrder()

        // Both grants, text code: the wallet picks.
        val both = offer(
            "{$issuer,\"credential_configuration_ids\":[\"UniversityDegreeCredential\"]," +
                "\"grants\":{\"authorization_code\":{\"authorization_server\":\"https://auth.example.com\"}," +
                "$pre:{\"pre-authorized_code\":\"x\",\"tx_code\":{\"length\":6,\"input_mode\":\"text\"}}}}"
        )
        assertThat(rowsOf(both)["Sign-in"])
            .isEqualTo("Pre-authorised, or you sign in at auth.example.com — your wallet picks")
        assertThat(rowsOf(both)["Transaction code"]).isEqualTo("Required — 6 characters")

        // A length that is not a JSON integer is not a length; `1` is not `true`.
        fun txRow(tx: String) = rowsOf(
            offer("{$issuer,\"credential_configuration_ids\":[\"x\"],\"grants\":{$pre:{\"pre-authorized_code\":\"x\",$tx}}}")
        )["Transaction code"]
        assertThat(txRow("\"tx_code\":{\"length\":4.0}")).isEqualTo("Required")
        assertThat(txRow("\"tx_code\":{\"length\":true}")).isEqualTo("Required")
        assertThat(txRow("\"tx_code\":{\"length\":1}")).isEqualTo("Required — 1 digit")
        assertThat(txRow("\"user_pin_required\":true")).isEqualTo("Required")
        assertThat(txRow("\"user_pin_required\":1"))
            .isEqualTo("Not required — whoever redeems the code gets the credential")
    }

    @Test fun specCredentialOfferByReference() {
        val vc = VerifiableCredentialParser.parse(specOfferByReference)!!
        assertThat(vc.kind).isEqualTo(VerifiableCredentialPayload.Kind.CREDENTIAL_OFFER)
        assertThat(vc.decoded).isTrue()
        assertThat(vc.labelledFields()).containsExactly(
            LabelledField("Type", "Credential offer"),
            LabelledField("Offering", "Not in the code — your wallet fetches the details"),
            LabelledField("Issuer", "Not in the code"),
            LabelledField("Offer", "Your wallet fetches it from server.example.com"),
        ).inOrder()
        assertThat(vc.footnote).isEqualTo(
            "Scan decodes this code to explain it. It does not fetch the offer or contact the issuer."
        )
        assertThat(vc.explanationPoints()).containsExactly(
            "An issuer is offering to add a credential to your digital-identity wallet.",
            "The offer itself is not in the code: your wallet fetches it from server.example.com.",
            "Issuer: Not in the code.",
        ).inOrder()
    }

    @Test fun euReferenceIdentifiersReadAsTheirCredential() {
        val vc = offer(
            "{\"credential_issuer\":\"https://issuer.example.eu\",\"credential_configuration_ids\":[" +
                "\"eu.europa.ec.eudi.pid_vc_sd_jwt\",\"eu.europa.ec.eudi.pid_mdoc\"," +
                "\"eu.europa.ec.eudi.mdl_mdoc\",\"eu.europa.ec.eudi.por_sd_jwt_vc\"]}"
        )
        assertThat(vc.requested).containsExactly(pidName, mdlName, "eIDAS POR attribute set").inOrder()
        val names = mapOf(
            "urn:eudi:pid:1" to pidName,
            "urn:eudi:pid:de:1" to "EU Digital Identity — PID (DE)",
            "eu.europa.ec.eudi.photoid" to "Photo ID (ISO/IEC 23220)",
            "eu.europa.ec.eudi.ehic_sd_jwt_vc" to "European Health Insurance Card",
            "urn:eudi:ehic:1" to "European Health Insurance Card",
            "eu.europa.ec.eudi.pid_mdoc_deferred" to pidName,
            "eu.europa.ec.eudi.tax_residency_vc_sd_jwt" to "eIDAS TAX RESIDENCY attribute set",
            "org.iso.18013.5.1.mDL" to mdlName,
            "org.iso.23220.photoid.1" to "Photo ID (ISO/IEC 23220)",
            "eu.europa.ec.eudi.mdl.1" to "eIDAS MDL attribute set",
            "urn:eu.europa.ec.eudi:pboc:1" to "eIDAS PBOC attribute set",
            "urn:eu.europa.ec.eudi:purse:1" to "eIDAS PURSE attribute set",
        )
        for ((id, expected) in names) {
            assertThat(VerifiableCredentialParser.typeName(id)).isEqualTo(expected)
        }
        // A `scope` that is a credential identifier reads as one.
        assertThat(VerifiableCredentialParser.scopeName("urn:eudi:pid:de:1")).isEqualTo("EU Digital Identity — PID (DE)")
        assertThat(VerifiableCredentialParser.scopeName("eu.europa.ec.eudi.pid_mdoc")).isEqualTo(pidName)
        assertThat(VerifiableCredentialParser.scopeName("some_brand_scope")).isEqualTo("Some Brand Scope")
        assertThat(VerifiableCredentialParser.scopeName("")).isEqualTo("")
    }

    @Test fun legacyDraftOfferStillReads() {
        // Draft 11 (EBSI-era): `credentials` objects and `user_pin_required`.
        val vc = offer(
            "{\"credential_issuer\":\"https://issuer.example.eu\",\"credentials\":[{\"format\":\"jwt_vc\"," +
                "\"types\":[\"VerifiableCredential\",\"VerifiableAttestation\",\"CTWalletSamePreAuthorisedInTime\"]}," +
                "\"UniversityDegree\"],\"grants\":{\"urn:ietf:params:oauth:grant-type:pre-authorized_code\":" +
                "{\"pre-authorized_code\":\"eyJ0eXAi\",\"user_pin_required\":true}}}"
        )
        assertThat(vc.requested).containsExactly("CTWalletSamePreAuthorisedInTime", "UniversityDegree").inOrder()
        assertThat(rowsOf(vc)["Transaction code"]).isEqualTo("Required")
        assertThat(rowsOf(vc)["Issuer"]).isEqualTo("issuer.example.eu")
    }

    @Test fun sdJwtWithKeyBindingIsAPresentation() {
        val issuerJWT = base64URL("""{"alg":"ES256","typ":"dc+sd-jwt"}""") + "." +
            base64URL(
                """{"iss":"https://pid-issuer.example.eu","vct":"urn:eudi:pid:1","iat":1750000000,""" +
                    """"_sd":["a1","b2"],"_sd_alg":"sha-256","cnf":{"jwk":{"kty":"EC"}}}"""
            ) + ".c2lnbmF0dXJl"
        val disclosure = base64URL("""["2GLC42sKQveCfGfryNRN9w","given_name","Erika"]""")
        val kb = base64URL("""{"alg":"ES256","typ":"kb+jwt"}""") + "." +
            base64URL("""{"nonce":"1234567890","aud":"x509_hash:abc","iat":1750000100,"sd_hash":"0_Af-2B"}""") +
            ".a2Jz"

        val presentation = "$issuerJWT~$disclosure~$kb"
        assertThat(VerifiableCredentialParser.looksLikeCredentialToken(presentation)).isTrue()
        val p = ScanPayloadParser.parse(presentation)
        assertThat(p).isInstanceOf(ScanPayload.VerifiableCredential::class.java)
        val vp = (p as ScanPayload.VerifiableCredential).payload
        assertThat(vp.kind).isEqualTo(VerifiableCredentialPayload.Kind.PRESENTATION)
        assertThat(vp.credentialType).isEqualTo("urn:eudi:pid:1")
        assertThat(rowsOf(vp)["Credential"]).isEqualTo(pidName)
        assertThat(rowsOf(vp)["Issuer"]).isEqualTo("pid-issuer.example.eu")

        val credential = VerifiableCredentialParser.parse("$issuerJWT~$disclosure~")!!
        assertThat(credential.kind).isEqualTo(VerifiableCredentialPayload.Kind.CREDENTIAL)
        assertThat(credential.selectivelyDisclosable).isEqualTo(2)
    }

    @Test fun explainerSpeaksPerKind() {
        val offerExplained = HeuristicExplainer.explain(specOffer, ScanPayloadParser.parse(specOffer))
        assertThat(offerExplained.headline).isEqualTo("Credential offer")
        assertThat(offerExplained.keyPoints).containsAtLeast(
            "An issuer is offering to add a credential to your digital-identity wallet.",
            "Offered: $mdlName.",
            "Issuer: credential-issuer.example.com.",
            "Your wallet will ask for a transaction code before the credential is issued.",
        ).inOrder()
        assertThat(offerExplained.keyPoints.none { it.contains("oaKazRN8I0IbtZ0C7JuMn5") }).isTrue()
        assertThat(offerExplained.summary).doesNotContain("oaKazRN8I0IbtZ0C7JuMn5")

        val requestExplained = HeuristicExplainer.explain(haipRequest, ScanPayloadParser.parse(haipRequest))
        assertThat(requestExplained.keyPoints).containsAtLeast(
            "A site is asking your digital-identity wallet to release attributes.",
            "The request itself is not in the code: your wallet fetches it from verifier.example.org.",
            "Verifier: Unnamed — identified by certificate hash.",
        ).inOrder()
        assertThat(requestExplained.cautions)
            .doesNotContain("No nonce: the same code can be replayed, so treat its contents as untrusted.")
    }

    // ---- Review fixes: the iOS `CredentialAndPassportSpecTests` vectors --

    private val typosquat = "The requesting domain looks like a typosquat of a well-known brand."
    private val bearer = "Pre-authorised with no transaction code: whoever redeems this code first gets the " +
        "credential, so do not share or post it."

    private fun biometricCaution(claims: String) =
        "It asks for biometric data ($claims): only approve that for a verifier you trust."

    private fun parse(raw: String) = VerifiableCredentialParser.parse(raw)!!

    private fun percentEncoded(bytes: ByteArray): String = buildString {
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
        }
    }

    /** A by-value request whose verifier and answer address are the given URLs. */
    private fun hostRequest(clientID: String, responseURI: String) = parse(
        "openid4vp://?client_id=" + alnumEncoded(clientID) + "&response_uri=" + alnumEncoded(responseURI) + "&nonce=n-1"
    )

    @Test fun credentialHostsAreTheBrowsersHost() {
        val cases = listOf(
            "münchen.de" to "münchen.de",
            "bücher.example" to "bücher.example",
            "[2001:db8::1]:8443" to "2001:db8::1",
            "ｅｘａｍｐｌｅ.com" to "example.com",
            "ex​ample.com" to "example.com",
            "Verifier.Example.ORG" to "verifier.example.org",
        )
        for ((written, shown) in cases) {
            val vc = hostRequest("redirect_uri:https://$written/cb", "https://$written/post")
            assertWithMessage(written).that(rowsOf(vc)["Verifier"]).isEqualTo(shown)
            assertWithMessage(written).that(rowsOf(vc)["Answer goes to"]).isEqualTo(shown)
            assertWithMessage(written).that(vc.counterpartyHost).isEqualTo(shown)
            assertWithMessage(written).that(vc.cautions()).containsExactly(approveOnlyYours)
        }
        // A backslash ends the host, as it does in a browser.
        val backslash = hostRequest("redirect_uri:https://verifier.example\\cb", "https://verifier.example\\post")
        assertThat(rowsOf(backslash)["Verifier"]).isEqualTo("verifier.example")
        assertThat(rowsOf(backslash)["Answer goes to"]).isEqualTo("verifier.example")
        // … a combining mark after it included: delimiters are found by char.
        assertThat(
            rowsOf(hostRequest("redirect_uri:https://verifier.example\\́cb", "https://verifier.example/post"))["Verifier"]
        ).isEqualTo("verifier.example")
        // Look-alikes warn in any alphabet.
        for (written in listOf("pаypal.com", "раураl.com")) {
            val vc = parse("openid4vp://?client_id=" + alnumEncoded("https://$written") + "&nonce=n-1")
            assertWithMessage(written).that(rowsOf(vc)["Verifier"]).isEqualTo(written)
            assertWithMessage(written).that(vc.cautions()).containsExactly(approveOnlyYours, typosquat).inOrder()
        }
    }

    @Test fun anAddressThatHidesItsHostIsNotNamed() {
        val unusual = ShownHost.UNUSUAL
        val backslash = hostRequest(
            "redirect_uri:https://evil.example\\@verifier.gov/cb",
            "https://evil.example\\@verifier.gov/post",
        )
        assertThat(rowsOf(backslash)["Verifier"]).isEqualTo(unusual)
        assertThat(rowsOf(backslash)["Answer goes to"]).isEqualTo(unusual)
        assertThat(backslash.cautions()).containsExactly(approveOnlyYours, ShownHost.HIDDEN_HOST_CAUTION).inOrder()
        assertThat(backslash.explanationPoints().last()).isEqualTo("Verifier: $unusual.")
        for (written in listOf(
            "https://paypal.com@evil.example/cb", "https://evil.example%2Fpaypal.com/cb",
            "https://a b.example/cb", "https:///cb", "https://ev‮il.example/cb",
            "https://evil\u0085.example/cb", "https://evil.example\u0000/cb", "https://[::1/cb",
            "https://[::1]́:443/cb",
        )) {
            val vc = hostRequest("redirect_uri:$written", "https://verifier.example/post")
            assertWithMessage(written).that(rowsOf(vc)["Verifier"]).isEqualTo(unusual)
            assertWithMessage(written).that(vc.cautions().last()).isEqualTo(ShownHost.HIDDEN_HOST_CAUTION)
        }
        // In a sentence, and for the other hosts a code names.
        val fetched = parse(
            "openid-credential-offer://?credential_offer_uri=" + alnumEncoded("https://good.example@evil.example/o/1")
        )
        assertThat(rowsOf(fetched)["Offer"]).isEqualTo("Your wallet fetches it from an unusual address (not shown)")
        assertThat(fetched.cautions().last()).isEqualTo(ShownHost.HIDDEN_HOST_CAUTION)
        val signIn = offer(
            "{\"credential_issuer\":\"https://issuer.example\",\"credential_configuration_ids\":[\"x\"]," +
                "\"grants\":{\"authorization_code\":{\"authorization_server\":\"https://a@b.example\"}}}"
        )
        assertThat(rowsOf(signIn)["Sign-in"]).isEqualTo("You'll sign in at an unusual address (not shown)")
        val token = parse(
            base64URL("""{"alg":"ES256","typ":"dc+sd-jwt"}""") + "." +
                base64URL("""{"iss":"https://paypal.com@evil.example","vct":"urn:eudi:pid:1"}""") + ".c2ln"
        )
        assertThat(rowsOf(token)["Issuer"]).isEqualTo(unusual)
        assertThat(token.explanationPoints().first())
            .isEqualTo("A digital-identity credential issued by an unusual address (not shown).")
        assertThat(token.cautions().last()).isEqualTo(ShownHost.HIDDEN_HOST_CAUTION)
    }

    /** OpenID4VP by value, with the verifier on a look-alike host. */
    private val lookalikeQuery = "client_id=" + "redirect_uri%3Ahttps%3A%2F%2Fpaypa1-verify.com%2Fcb" +
        "&response_uri=https%3A%2F%2Fpaypa1-verify.com%2Fpost&nonce=n-1&dcql_query=" +
        "%7B%22credentials%22%3A%5B%7B%22id%22%3A%22pid%22%2C%22format%22%3A%22dc%2Bsd-jwt%22%2C" +
        "%22meta%22%3A%7B%22vct_values%22%3A%5B%22urn%3Aeudi%3Apid%3A1%22%5D%7D%7D%5D%7D"

    @Test fun aStrayParameterDoesNotHideTheVerifier() {
        val control = parse("openid4vp://?$lookalikeQuery")
        assertThat(control.labelledFields()).containsExactly(
            LabelledField("Type", "Credential request"),
            LabelledField("Format", "dc+sd-jwt"),
            LabelledField("Asking for", pidName),
            LabelledField("Verifier", "paypa1-verify.com"),
            LabelledField("Answer goes to", "paypa1-verify.com"),
            LabelledField("Anti-replay", "Nonce present"),
        ).inOrder()
        assertThat(control.cautions()).containsExactly(approveOnlyYours, typosquat).inOrder()
        for (suffix in listOf(
            "&x=a b", "&x=café", "&x=50%", "&x=%", "&x=%zz", "&x=%FF", "&x=a|b",
            """&client_metadata={"client_name":"V"}""",
        )) {
            val vc = parse("openid4vp://?$lookalikeQuery$suffix")
            assertWithMessage(suffix).that(vc.labelledFields()).isEqualTo(control.labelledFields())
            assertWithMessage(suffix).that(vc.requested).isEqualTo(control.requested)
            assertWithMessage(suffix).that(vc.counterpartyHost).isEqualTo("paypa1-verify.com")
            assertWithMessage(suffix).that(vc.cautions()).isEqualTo(control.cautions())
        }
        for (variant in listOf("openid4vp://?$lookalikeQuery&state=%zz", "openid4vp://?state=a b&$lookalikeQuery")) {
            val vc = parse(variant)
            assertWithMessage(variant).that(rowsOf(vc)["Verifier"]).isEqualTo("paypa1-verify.com")
            assertWithMessage(variant).that(rowsOf(vc)["Anti-replay"]).isEqualTo("Nonce + state")
            assertWithMessage(variant).that(vc.cautions()).isEqualTo(control.cautions())
        }
        val scoped = parse("openid4vp://?$lookalikeQuery&scope=openid pid")
        assertThat(rowsOf(scoped)["Asking for"]).isEqualTo("$pidName + 2 more")
        assertThat(rowsOf(scoped)["Verifier"]).isEqualTo("paypa1-verify.com")
        assertThat(scoped.cautions()).isEqualTo(control.cautions())
        // Encoded identifiers next to a raw JSON query.
        val rawJson = parse(
            "openid4vp://?client_id=redirect_uri%3Ahttps%3A%2F%2Fpaypa1-verify.com%2Fcb" +
                "&response_uri=https%3A%2F%2Fpaypa1-verify.com%2Fpost&nonce=n-1" +
                """&dcql_query={"credentials":[{"id":"pid","format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:pid:1"]}}]}"""
        )
        assertThat(rawJson.labelledFields()).isEqualTo(control.labelledFields())
        assertThat(rawJson.cautions()).isEqualTo(control.cautions())
        // By reference, with a raw space elsewhere.
        val reference = parse(
            "openid4vp://?client_id=redirect_uri%3Ahttps%3A%2F%2Fpaypa1-verify.com%2Fcb" +
                "&request_uri=https%3A%2F%2Fpaypa1-verify.com%2Frequest%2F1&x=a b"
        )
        assertThat(reference.referenceHost).isEqualTo("paypa1-verify.com")
        assertThat(rowsOf(reference)["Request"]).isEqualTo("Your wallet fetches it from paypa1-verify.com")
        assertThat(reference.cautions()).containsExactly(approveOnlyYours, typosquat).inOrder()
    }

    @Test fun anOfferSurvivesAStrayParameter() {
        val grants = """"grants":{"urn:ietf:params:oauth:grant-type:pre-authorized_code":""" +
            """{"pre-authorized_code":"SplxlOBeZQQYbYS6WxSbIA"}}"""
        val issuer = "openid-credential-offer://?credential_offer=" + alnumEncoded(
            """{"credential_issuer":"https://issuer.example",""" +
                """"credential_configuration_ids":["eu.europa.ec.eudi.pid_vc_sd_jwt"],""" + grants + "}"
        )
        for (suffix in listOf("", "&x=%", "&note=a b")) {
            val vc = parse(issuer + suffix)
            assertWithMessage(suffix).that(vc.labelledFields()).containsExactly(
                LabelledField("Type", "Credential offer"),
                LabelledField("Offering", pidName),
                LabelledField("Issuer", "issuer.example"),
                LabelledField("Sign-in", "Not needed — pre-authorised by the issuer"),
                LabelledField("Transaction code", "Not required — whoever redeems the code gets the credential"),
            ).inOrder()
            assertWithMessage(suffix).that(vc.cautions()).containsExactly(unsolicited, bearer).inOrder()
        }
        val lookalike = parse(
            "openid-credential-offer://?credential_offer=" + alnumEncoded(
                """{"credential_issuer":"https://paypa1.com",""" +
                    """"credential_configuration_ids":["eu.europa.ec.eudi.pid_vc_sd_jwt"],""" + grants + "}"
            ) + "&note=a b"
        )
        assertThat(rowsOf(lookalike)["Issuer"]).isEqualTo("paypa1.com")
        assertThat(lookalike.preAuthorized).isTrue()
        assertThat(lookalike.cautions()).containsExactly(unsolicited, bearer, typosquat).inOrder()

        // What does not decode is kept as written — never dropped, never U+FFFD.
        val nonce = parse("openid4vp://?client_id=https%3A%2F%2Fverifier.example.org&nonce=%FF")
        assertThat(nonce.noncePresent).isTrue()
        assertThat(rowsOf(nonce)["Anti-replay"]).isEqualTo("Nonce present")
        assertThat(parse("openid-credential-offer://?credential_offer_uri=%96").labelledFields()).containsExactly(
            LabelledField("Type", "Credential offer"),
            LabelledField("Offering", "Not in the code — your wallet fetches the details"),
            LabelledField("Issuer", "Not in the code"),
            LabelledField("Offer", "Your wallet fetches it from the issuer"),
        ).inOrder()
        assertThat(rowsOf(parse("openid4vp://?client_id=abc%zz&nonce=1"))["Verifier"]).isEqualTo("abc%zz")
        // A fragment is no part of the query.
        val fragment = parse("openid4vp://?client_id=https%3A%2F%2Fverifier.example.org&nonce=1#frag")
        assertThat(fragment.noncePresent).isTrue()
        assertThat(fragment.labelledFields().none { it.value.contains("frag") }).isTrue()
    }

    @Test fun deepJsonIsRefusedNotRecursed() {
        // About 500 nested containers fit in one QR code; iOS refuses them
        // before `JSONSerialization` can overrun an App Intent's stack, and
        // this reader stops at the same depth.
        val deep = "{\"\":".repeat(511) + "1" + "}".repeat(511)
        val offerCode = parse("openid-credential-offer://?credential_offer=$deep")
        assertThat(rowsOf(offerCode)["Offering"]).isEqualTo("Not stated")
        assertThat(rowsOf(offerCode)["Claims"]).isEqualTo("Not decodable — possibly a newer format")
        assertThat(rowsOf(parse("openid4vp://?client_id=x&dcql_query=$deep"))["Asking for"]).isEqualTo("Not stated")
    }

    @Test fun onlyUtf8ClaimSetsAreRead() {
        val header = base64URL("""{"alg":"ES256","typ":"dc+sd-jwt"}""")
        val claims = """{"iss":"https://issuer.example","vct":"urn:eudi:pid:1"}"""
        assertThat(ScanPayloadParser.parse("$header.${base64URL(claims)}.c2ln").kindLabel).isEqualTo("Verifiable credential")
        val utf16 = claims.toByteArray(Charsets.UTF_16LE)
        for (bytes in listOf(utf16, byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16, claims.toByteArray(Charsets.UTF_32LE))) {
            assertThat(ScanPayloadParser.parse("$header.${base64URL(bytes)}.c2ln").kindLabel).isEqualTo("Text")
        }
        val requestClaims = """{"client_id":"https://good.example","nonce":"n-1"}"""
        val utf16Request = parse(
            "openid4vp://?client_id=x&request=$header." + base64URL(requestClaims.toByteArray(Charsets.UTF_16LE)) + ".c2ln"
        )
        assertThat(rowsOf(utf16Request)["Verifier"]).isEqualTo("x")
        assertThat(rowsOf(utf16Request)["Anti-replay"]).isEqualTo("Inside the signed request — not readable")
        val offerJson = """{"credential_issuer":"https://issuer.example",""" +
            """"credential_configuration_ids":["eu.europa.ec.eudi.pid_vc_sd_jwt"]}"""
        assertThat(
            parse("openid-credential-offer://?credential_offer=" + percentEncoded(offerJson.toByteArray(Charsets.UTF_16LE)))
                .labelledFields()
        ).containsExactly(
            LabelledField("Type", "Credential offer"),
            LabelledField("Offering", "Not stated"),
            LabelledField("Issuer", "Not stated"),
            LabelledField("Claims", "Not decodable — possibly a newer format"),
        ).inOrder()
        // U+001F is not whitespace to Swift, U+0085 is.
        val unitSeparator = parse("openid4vp://?client_id=x&request=$header.%1F${base64URL(requestClaims)}.c2ln")
        assertThat(rowsOf(unitSeparator)["Anti-replay"]).isEqualTo("Inside the signed request — not readable")
        val nextLine = parse("openid4vp://?client_id=x&request=$header.%C2%85${base64URL(requestClaims)}.c2ln")
        assertThat(rowsOf(nextLine)["Verifier"]).isEqualTo("good.example")
        assertThat(rowsOf(nextLine)["Anti-replay"]).isEqualTo("Nonce present")
    }

    @Test fun credentialDatesOutOfRangeAreLeftOut() {
        val header = base64URL("""{"alg":"ES256","typ":"dc+sd-jwt"}""")
        fun token(value: String) = parse(
            "$header." + base64URL("""{"iss":"https://issuer.example","vct":"urn:eudi:pid:1","iat":$value,"exp":$value}""") +
                ".c2ln"
        )
        for (value in listOf(
            "1e300", "-1e300", "1e18", "\"Infinity\"", "\"NaN\"", "\"nan\"", "\"inf\"", "-1",
            "\"1e9\"", "\" 1767225600\"", "\"1767225600.\"", "true", "253402300800",
        )) {
            val vc = token(value)
            assertWithMessage(value).that(vc.issuedAt).isNull()
            assertWithMessage(value).that(vc.expiresAt).isNull()
            assertWithMessage(value).that(rowsOf(vc)).doesNotContainKey("Issued")
            assertWithMessage(value).that(rowsOf(vc)).doesNotContainKey("Expires")
        }
        for ((value, millis) in listOf(
            "1767225600" to 1_767_225_600_000L, "\"1767225600\"" to 1_767_225_600_000L,
            "1767225600.5" to 1_767_225_600_500L, "\"1767225600.5\"" to 1_767_225_600_500L,
            "0" to 0L, "253402300799" to 253_402_300_799_000L,
        )) {
            val vc = token(value)
            assertWithMessage(value).that(vc.issuedAt?.time).isEqualTo(millis)
            assertWithMessage(value).that(vc.expiresAt?.time).isEqualTo(millis)
            assertWithMessage(value).that(rowsOf(vc)["Issued"].isNullOrEmpty()).isFalse()
            assertWithMessage(value).that(rowsOf(vc)["Expires"].isNullOrEmpty()).isFalse()
        }
    }

    @Test fun requestExplanationNamesTheClaimsAskedFor() {
        val vc = parse(dcqlRequest)
        assertThat(vc.claimsByCredential).containsExactly(
            "given name, family name, street address from $pidName",
            "given name, portrait from $mdlName",
        ).inOrder()
        assertThat(vc.biometricClaims).containsExactly("portrait")
        assertThat(vc.explanationPoints()).containsExactly(
            "A site is asking your digital-identity wallet to release attributes.",
            "Requested: given name, family name, street address from $pidName; given name, portrait from $mdlName.",
            "Verifier: client.example.org.",
        ).inOrder()
        val explained = HeuristicExplainer.explain(dcqlRequest, ScanPayloadParser.parse(dcqlRequest))
        val text = (listOf(explained.summary) + explained.keyPoints).joinToString(" ")
        assertThat(text).contains("street")
        assertThat(text).contains("portrait")
        assertThat(text).doesNotContain("birth date")
        assertThat(text).doesNotContain("nationality")
        assertThat(explained.cautions).contains(biometricCaution("portrait"))

        // Eight claims a credential and four credentials are named, the rest counted.
        val ten = (1..10).joinToString(",") { """{"path":["c_$it"]}""" }
        val dcql = """{"credentials":[{"id":"alpha","format":"dc+sd-jwt","claims":[""" + ten + "]}," +
            """{"id":"beta","format":"dc+sd-jwt"},""" +
            """{"id":"gamma","format":"mso_mdoc","claims":[{"path":["org.iso.18013.5.1","signature_usual_mark"]}]},""" +
            """{"id":"delta","format":"dc+sd-jwt","claims":[{"path":["picture"]}]},""" +
            """{"id":"epsilon","format":"dc+sd-jwt","claims":[{"path":["x"]}]}]}"""
        val capped = parse(
            "openid4vp://?client_id=redirect_uri%3Ahttps%3A%2F%2Fv.example%2Fcb&nonce=1&dcql_query=" + alnumEncoded(dcql)
        )
        assertThat(capped.claimsByCredential).containsExactly(
            "c 1, c 2, c 3, c 4, c 5, c 6, c 7, c 8 + 2 more from alpha",
            "beta",
            "signature usual mark from gamma",
            "picture from delta",
            "+ 1 more",
        ).inOrder()
        assertThat(capped.biometricClaims).containsExactly("signature usual mark", "picture").inOrder()
        assertThat(capped.cautions())
            .containsExactly(approveOnlyYours, biometricCaution("signature usual mark, picture")).inOrder()
        // No claims at all: the credentials' names, as before.
        val unnamed = parse(
            "openid4vp://?client_id=redirect_uri%3Ahttps%3A%2F%2Fv.example%2Fcb&nonce=1&dcql_query=" +
                alnumEncoded("""{"credentials":[{"id":"pid","format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:pid:1"]}}]}""")
        )
        assertThat(unnamed.claimsByCredential).isEmpty()
        assertThat(unnamed.explanationPoints()[1]).isEqualTo("Requested: $pidName.")
    }

    private fun base64URL(s: String): String = base64URL(s.toByteArray(Charsets.UTF_8))

    private fun base64URL(bytes: ByteArray): String =
        android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )

    private companion object {
        const val HEX = "0123456789ABCDEF"
    }

    /** Swift `addingPercentEncoding(withAllowedCharacters: .alphanumerics)`. */
    private fun alnumEncoded(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code) {
                append(c.toChar())
            } else {
                append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
        }
    }
}
