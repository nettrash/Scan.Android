package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mirrors the iOS assistant tests. The deterministic [HeuristicExplainer] is
 * the engine that answers on Android — there is no on-device GenAI model in
 * this build and no network path — so its wording, and the redaction that runs
 * in front of it, are the parts under test.
 */
@RunWith(RobolectricTestRunner::class)
class AssistantTest {

    // ---- SecretRedaction ---------------------------------------------------

    @Test fun wifiPasswordIsBlankedBeforeTheEngineSeesIt() {
        val out = SecretRedaction.redactForModel("WIFI:T:WPA;S:BlueHouse;P:hunter2hidden;H:false;;")
        assertThat(out).doesNotContain("hunter2hidden")
        assertThat(out).contains("P:<redacted>")
        assertThat(out).contains("S:BlueHouse")
    }

    @Test fun otpAuthSecretIsBlanked() {
        val out = SecretRedaction.redactForModel(
            "otpauth://totp/ACME:alice@acme.com?secret=JBSWY3DPEHPK3PXP&issuer=ACME",
        )
        assertThat(out).doesNotContain("JBSWY3DPEHPK3PXP")
        assertThat(out).contains("secret=<redacted>")
    }

    @Test fun freeTextPasswordAndPinAreBlanked() {
        assertThat(SecretRedaction.redactForModel("password: sunshine2026"))
            .doesNotContain("sunshine2026")
        assertThat(SecretRedaction.redactForModel("pin = 4821"))
            .doesNotContain("4821")
    }

    @Test fun nostrKeyAndPemBodyAreBlankedButStillRecognisable() {
        val nostrap = SecretRedaction.redactForModel("nsec1qvz8cq6d8t0f0y3gqk0q0m3v9z2xk")
        assertThat(nostrap).doesNotContain("qvz8cq6d8t0f0y3gqk0q0m3v9z2xk")
        assertThat(nostrap).startsWith("nsec1")

        val pem = SecretRedaction.redactForModel(
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEAy7Kd3nZ0m1VhQ3YrT0pLbCjF\n-----END RSA PRIVATE KEY-----",
        )
        assertThat(pem).doesNotContain("MIIEowIBAAKCAQEAy7Kd3nZ0m1VhQ3YrT0pLbCjF")
        // The marker stays so the risk assessor can still name the secret.
        assertThat(pem).contains("-----BEGIN RSA PRIVATE KEY-----")
    }

    @Test fun longOpaqueBlobsAreOmittedAndOutputIsCapped() {
        val invoice = "lnbc" + "u".repeat(400)
        assertThat(SecretRedaction.redactForModel(invoice)).contains("<encoded-data omitted>")

        // Wordy text so the blob sweep leaves it alone and only the cap bites.
        val huge = "scan this ".repeat(600)
        assertThat(SecretRedaction.redactForModel(huge).length).isEqualTo(2000)
    }

    // ---- HeuristicExplainer.explain ---------------------------------------

    @Test fun explainsWifi() {
        val raw = "WIFI:T:WPA;S:BlueHouse;P:hunter2;H:true;;"
        val e = HeuristicExplainer.explain(raw, ScanPayloadParser.parse(raw))
        assertThat(e.headline).isEqualTo("A Wi-Fi login")
        assertThat(e.keyPoints).contains("Joins the Wi-Fi network \u201cBlueHouse\u201d.")
        assertThat(e.keyPoints).contains("Security: WPA / WPA2.")
        assertThat(e.keyPoints).contains("The network hides its name.")
        assertThat(e.cautions).contains("Only join networks whose name you can see printed by the venue.")
        assertThat(e.source).isEqualTo(AssistantSource.BUILT_IN)
    }

    @Test fun explainsEInvoice() {
        val invoice = InvoicePayload(
            standard = "Peppol BIS Billing 3.0",
            sellerName = "Acme d.o.o.",
            totalAmount = "1.234,56",
            currency = "EUR",
            wasEncoded = false,
            raw = "<xml/>",
        )
        val e = HeuristicExplainer.explain("<xml/>", ScanPayload.EInvoice(invoice))
        assertThat(e.headline).isEqualTo("An electronic invoice")
        assertThat(e.keyPoints).contains("An electronic-invoice summary in Peppol BIS Billing 3.0.")
        assertThat(e.keyPoints).contains("Total 1.234,56.")
        assertThat(e.cautions).contains(
            "Reconcile the IBAN and amount against the document before paying.",
        )
    }

    @Test fun explainsAnMdocEngagementAndSaysScanIsNotAReader() {
        // The iOS `testBuiltInExplainerWordingMatchesAndroid` mdoc case.
        val raw = IdentityPayloadTest.ANNEX_D_QR
        val e = HeuristicExplainer.explain(raw, ScanPayloadParser.parse(raw))
        assertThat(e.headline).isEqualTo("A digital-ID engagement code")
        assertThat(e.keyPoints.first()).startsWith("A digital-ID wallet's code for an ID reader")
        // The caution that matters: only show the code to the reader you mean.
        assertThat(e.cautions).contains("Only show your own code to the reader you mean to share with.")
    }

    @Test fun riskyPayloadsLeakTheirRiskDetailIntoCautions() {
        val raw = "otpauth://totp/ACME:alice@acme.com?secret=JBSWY3DPEHPK3PXP&issuer=ACME"
        val e = HeuristicExplainer.explain(raw, ScanPayloadParser.parse(raw))
        assertThat(e.headline).isEqualTo("A two-factor enrolment")
        assertThat(e.cautions.any { it.contains("anyone holding this code", ignoreCase = true) }).isTrue()
    }

    // ---- HeuristicExplainer.compose ---------------------------------------

    @Test fun composeWifiStopsTheSsidAtTheKeyword() {
        val composed = HeuristicExplainer.compose("wifi BlueHouse password hunter2 hidden")
        assertThat(composed).isNotNull()
        val result = composed!!
        assertThat(result.kind).isEqualTo(ComposeKind.WIFI)
        // The boundary matters: the ssid is the network name, not the rest of
        // the sentence.
        assertThat(result.value("ssid")).isEqualTo("BlueHouse")
        assertThat(result.value("password")).isEqualTo("hunter2")
        assertThat(result.value("hidden")).isEqualTo("true")
        assertThat(result.source).isEqualTo(ComposeResult.ComposeSource.BUILT_IN)
    }

    @Test fun composeWifiReadsNamedNetworksAndSecurity() {
        val result = HeuristicExplainer.compose("wifi network named Casa Verde password verde2026 wpa3")
        assertThat(result?.value("ssid")).isEqualTo("Casa Verde")
        assertThat(result?.value("security")).isEqualTo("WPA3")
        assertThat(result?.value("password")).isEqualTo("verde2026")
    }

    @Test fun composeUrlTakesTheLiteralLink() {
        val result = HeuristicExplainer.compose("url https://example.com/menu")
        assertThat(result?.kind).isEqualTo(ComposeKind.URL)
        assertThat(result?.text).isEqualTo("https://example.com/menu")
        assertThat(result?.values).isEmpty()
        assertThat(result?.isBlank).isFalse()
    }

    @Test fun composeDeclinesAmbiguousRequests() {
        // The rule-based engine only claims a result when the request names
        // the thing explicitly; anything else leaves the form alone.
        assertThat(HeuristicExplainer.compose("make me a code for my coffee shop")).isNull()
        assertThat(HeuristicExplainer.compose("wifi")).isNull()
    }

    @Test fun composeValueIgnoresBlankAndCase() {
        val result = ComposeResult(
            kind = ComposeKind.WIFI,
            values = mapOf("ssid" to "  ", "password" to "hunter2"),
            text = "",
            source = ComposeResult.ComposeSource.BUILT_IN,
        )
        assertThat(result.value("SSID")).isNull()
        assertThat(result.value("Password")).isEqualTo("hunter2")
        assertThat(result.value("missing")).isNull()
    }

    // ---- ScanAssistant -----------------------------------------------------

    @Test fun availabilitySaysUnavailableRatherThanBroken() {
        // No on-device GenAI backend ships today, and the toggle being off is
        // also "unavailable" — the built-in explainer answers either way.
        assertThat(ScanAssistant.availability(assistantEnabled = true))
            .isEqualTo(AssistantAvailability.UNAVAILABLE)
        assertThat(ScanAssistant.availability(assistantEnabled = false))
            .isEqualTo(AssistantAvailability.UNAVAILABLE)
        assertThat(ScanAssistant.statusLine(assistantEnabled = true))
            .contains("built-in explainer")
        // Switched off, the line says so instead of naming an engine (iOS
        // `assistantStatusLine`).
        assertThat(ScanAssistant.statusLine(assistantEnabled = false))
            .isEqualTo("The assistant is off, so results show no \u201CWhat is this code?\u201D button.")
    }

    @Test fun explainAlwaysAnswersWithTheBuiltInEngine() = runBlocking {
        val raw = "https://example.com/menu"
        val e = ScanAssistant.explain(raw, ScanPayload.Url(raw))
        assertThat(e.source).isEqualTo(AssistantSource.BUILT_IN)
        assertThat(e.summary).isNotEmpty()
        assertThat(e.keyPoints).isNotEmpty()
    }

    @Test fun explainNeverEchoesTheWifiPassword() = runBlocking {
        val raw = "WIFI:T:WPA;S:BlueHouse;P:supersecret123;H:false;;"
        val e = ScanAssistant.explain(raw, ScanPayloadParser.parse(raw))
        val everything = listOf(e.headline, e.summary) + e.keyPoints + e.cautions
        assertThat(everything.any { it.contains("supersecret123") }).isFalse()
    }

    @Test fun composeFallsThroughToTheRuleEngine() = runBlocking {
        val result = ScanAssistant.compose("wifi BlueHouse password hunter2 hidden")
        assertThat(result?.value("ssid")).isEqualTo("BlueHouse")
        assertThat(ScanAssistant.compose("something clever")).isNull()
    }

    // ---- Parity with iOS (SafetyParityTests) --------------------------------

    @Test fun explainReadsTheRealPayloadSoASeedPhraseKeepsItsDangerCaution() = runBlocking {
        val seed = "abandon ability able about above absent absorb abstract absurd abuse access accident"
        val e = ScanAssistant.explain(seed, null)
        assertThat(e.cautions.any { it.contains("seed phrase") }).isTrue()
        assertThat((listOf(e.headline, e.summary) + e.keyPoints).none { it.contains("abandon") }).isTrue()
    }

    @Test fun aNostrPrivateKeyIsNeverRepeated() {
        val raw = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
        val e = HeuristicExplainer.explain(raw, ScanPayloadParser.parse(raw))
        assertThat(e.keyPoints).contains("A Nostr private key (nsec).")
        val everything = listOf(e.headline, e.summary) + e.keyPoints + e.cautions
        assertThat(everything.none { it.contains("vl029") || it.contains("nlfe5") }).isTrue()
    }

    @Test fun anOpenNetworkIsExplainedAsOpenWhateverTheCodeLeftOut() {
        for (raw in listOf("WIFI:S:Cafe;P:;;", "WIFI:T:nopass;S:Cafe;;", "WIFI:S:Cafe;;")) {
            val e = HeuristicExplainer.explain(raw, ScanPayloadParser.parse(raw))
            assertThat(e.keyPoints).containsAtLeast("Security: None.", "No password is needed \u2014 the network is open.")
        }
        val wpa = "WIFI:T:WPA;S:Cafe;P:;;"
        assertThat(HeuristicExplainer.explain(wpa, ScanPayloadParser.parse(wpa)).keyPoints)
            .containsNoneOf("Security: None.", "No password is needed \u2014 the network is open.")
    }

    @Test fun textIsCountedInTheCharactersAPersonSees() {
        fun first(text: String) = HeuristicExplainer.explain(text, ScanPayload.Text(text)).keyPoints.first()
        assertThat(first("\uD83D\uDC4D\uD83C\uDFFD")).isEqualTo("Plain text, 1 character.")
        assertThat(first("cafe\u0301")).isEqualTo("Plain text, 4 characters.")
        assertThat(first("\uD83C\uDDF7\uD83C\uDDF8 Belgrade")).isEqualTo("Plain text, 10 characters.")
        val sms = "SMSTO:+381641234567:\uD83D\uDC4B\uD83C\uDFFD" + "x".repeat(100)
        assertThat(HeuristicExplainer.explain(sms, ScanPayloadParser.parse(sms)).keyPoints)
            .contains("Pre-filled text: \uD83D\uDC4B\uD83C\uDFFD" + "x".repeat(79) + ".")
    }

    @Test fun anInternationalisedLinkIsExplainedByTheNameItIsWrittenIn() {
        val raw = "https://m\u00FCnchen.de/"
        val e = HeuristicExplainer.explain(raw, ScanPayloadParser.parse(raw))
        assertThat(e.keyPoints).contains("Opens m\u00FCnchen.de in your browser.")
        assertThat(e.cautions).containsExactly("Check the spelling of the domain before you sign in or pay.")
    }

    @Test fun aModelSeesOnlyTheRedactedCodeAndTheCautionsStayTheRules() = runBlocking {
        var seen: String? = null
        ScanAssistant.registerBackend(object : ScanAssistant.OnDeviceAssistantBackend {
            override fun readiness(): AssistantAvailability = AssistantAvailability.READY
            override suspend fun explain(redactedRaw: String): AssistantExplanation {
                seen = redactedRaw
                return AssistantExplanation(
                    headline = "Model",
                    summary = "From the model.",
                    keyPoints = listOf("A point."),
                    cautions = listOf("A model caution."),
                    source = AssistantSource.ON_DEVICE_MODEL,
                )
            }
            override suspend fun compose(request: String): ComposeResult? = null
        })
        try {
            val raw = "https://user:hunter2@evil.example/?token=abcdef123456"
            val e = ScanAssistant.explain(raw, ScanPayloadParser.parse(raw))
            assertThat(seen).isEqualTo("https://user:<redacted>@evil.example/?token=<redacted>")
            // The model's words, the rules' cautions — never the model's own
            // warnings (iOS `testAModelAnswerKeepsTheRuleCautionsOnly`).
            val rules = HeuristicExplainer.explain(raw, ScanPayloadParser.parse(raw)).cautions
            assertThat(e.cautions).isEqualTo(rules)
            assertThat(e.cautions).isNotEmpty()
            assertThat(listOf(e.headline, e.summary)).containsExactly("Model", "From the model.").inOrder()
            assertThat(e.source).isEqualTo(AssistantSource.ON_DEVICE_MODEL)
        } finally {
            ScanAssistant.registerBackend(null)
        }
    }

    @Test fun aBackendThatNeverAnswersFallsBackInTime() = runBlocking {
        assertThat(ScanAssistant.MODEL_DEADLINE_MILLIS).isEqualTo(10_000L)
        ScanAssistant.registerBackend(object : ScanAssistant.OnDeviceAssistantBackend {
            override fun readiness(): AssistantAvailability = AssistantAvailability.READY
            override suspend fun explain(redactedRaw: String): AssistantExplanation = awaitCancellation()
            override suspend fun compose(request: String): ComposeResult = awaitCancellation()
        })
        try {
            val raw = "https://example.com/menu"
            val started = System.nanoTime()
            val e = ScanAssistant.explain(raw, ScanPayloadParser.parse(raw), deadlineMillis = 200)
            assertThat(e).isEqualTo(HeuristicExplainer.explain(raw, ScanPayloadParser.parse(raw)))
            val composed = ScanAssistant.compose("wifi BlueHouse password hunter2 hidden", deadlineMillis = 200)
            assertThat(composed?.source).isEqualTo(ComposeResult.ComposeSource.BUILT_IN)
            assertThat(System.nanoTime() - started).isLessThan(5_000_000_000L)
        } finally {
            ScanAssistant.registerBackend(null)
        }
    }

    @Test fun theExplainerNamesTheHostTheLinkReallyOpens() {
        fun points(raw: String) = HeuristicExplainer.explain(raw, ScanPayloadParser.parse(raw)).keyPoints
        for (raw in listOf("https://paypal.com\\@evil.example/login", "https://www.paypal.com\\@paypa1.com/signin")) {
            assertWithMessage(raw).that(points(raw).none { it.startsWith("Opens paypal.com") || it.startsWith("Opens www.paypal.com") })
                .isTrue()
        }
        assertThat(points("https://paypal.com\\@evil.example/login")).contains("Opens evil.example in your browser.")
        assertThat(points("https:paypa1.com/login")).contains("Opens paypa1.com in your browser.")
    }

    @Test fun composeIgnoresABlankRequest() = runBlocking {
        assertThat(ScanAssistant.compose("   ")).isNull()
    }
}
