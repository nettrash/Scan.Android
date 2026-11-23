package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The ISO/IEC 18013-5 / 18013-7 mdoc engagement codes added in 1.10:
 * `mdoc:` (the QR a digital-ID wallet shows an ID reader, or — second
 * edition — a reader shows a wallet) and `mdoc://` (a website's online
 * request). Mirrors the iOS `MdocEngagementTests` fixture for fixture and
 * row for row. The first fixture is the standard's own DeviceEngagement
 * test vector; the others reuse its key.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityPayloadTest {

    companion object {
        /** ISO/IEC 18013-5:2021 Annex D.3.1: the DeviceEngagement test vector, as printed. */
        const val ANNEX_D_HEX =
            "a30063312e30018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e" +
                "5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67" +
                "0281830201a300f401f50b5045efef742b2c4837a9a3b0e1d05a6917"

        /** `ANNEX_D_HEX` as clause 8.2.2.3 puts it in a QR: `mdoc:` + base64url, no padding. */
        const val ANNEX_D_QR =
            "mdoc:owBjMS4wAYIB2BhYS6QBAiABIVggWojRgrzl9C76WZQ_MzWdLoqWj_KJ2T5fpES2JDQxZ_4iWCCxboz4WN3" +
                "HaQQHumHUwzgjeoz8895qpnL8YKVXqjL8ZwKBgwIBowD0AfULUEXv73QrLEg3qaOw4dBaaRc"

        /** Spruce isomdl's QR example: Bluetooth peripheral server mode only. */
        const val ISOMDL_QR =
            "mdoc:owBjMS4wAYIB2BhYS6QBAiABIVgglyWXuAyJ6iRNc8OlYXenvkJt23rJPdtIhlawXqr-yf0iWCC1GQSH8tI" +
                "wTYVwha_ZoPL20_saYXrGIbrCm133H0ki-QKBgwIBowD1AfQKUH2RiuAEbUVzrsrOiUnSPDw"

        /**
         * Annex D's key with NFC, both Bluetooth modes (UUIDs and an address), Wi-Fi Aware with the
         * passphrase "wifi-pass-hunter2", and WebAPI + OIDC server retrieval with tokens.
         */
        const val RICH_QR =
            "mdoc:pABjMS4wAYIB2BhYS6QBAiABIVggWojRgrzl9C76WZQ_MzWdLoqWj_KJ2T5fpES2JDQxZ_4iWCCxboz4WN3" +
                "HaQQHumHUwzgjeoz8895qpnL8YKVXqjL8ZwKDgwEBogAY_wEZAQCDAgGlAPUB9QpQRe_vdCssSDepo7Dh0FppFwt" +
                "QRe_vdCssSDepo7Dh0FppFxRGChssPU5fgwMBogBxd2lmaS1wYXNzLWh1bnRlcjICBgOiZndlYkFwaYMBeCFodHR" +
                "wczovL21kbC5pc3N1ZXIuZXhhbXBsZS9hcGkvdjFzV0VCQVBJLVRPS0VOLVNFQ1JFVGRvaWRjgwF4I2h0dHBzOi8" +
                "vSUQuSXNzdWVyLkV4YW1wbGU6ODQ0My9vaWRjcU9JREMtVE9LRU4tU0VDUkVU"

        /** A second-edition Reader QR: ReaderEngagement 1.1, Bluetooth only, no keys 5 or 6. */
        const val READER_QR =
            "mdoc:owBjMS4xAYIB2BhYS6QBAiABIVggWojRgrzl9C76WZQ_MzWdLoqWj_KJ2T5fpES2JDQxZ_4iWCCxboz4WN3" +
                "HaQQHumHUwzgjeoz8895qpnL8YKVXqjL8ZwKBgwIBowD0AfULUEXv73QrLEg3qaOw4dBaaRc"

        /** DeviceEngagement 1.1 with OriginInfos (5) and Capabilities (6) and an X25519 key. */
        const val DEVICE_ENGAGEMENT_11_QR =
            "mdoc:pQBjMS4xAYIB2BhYKKMBASAEIVggWojRgrzl9C76WZQ_MzWdLoqWj_KJ2T5fpES2JDQxZ_4CgYMCAaMA9QH" +
                "0ClBF7-90KyxIN6mjsOHQWmkXBYAGoQL1"

        /**
         * ISO/IEC 18013-7 Annex A, laid out as the multipaz verifier builds it: an HTTP method
         * (type 4) to https://verifier.example.com/verifier/annexARequest?sessionId=1234.
         */
        const val ONLINE_URI =
            "mdoc://pABjMS4xAYIB2BhYS6QBAiABIVggWojRgrzl9C76WZQ_MzWdLoqWj_KJ2T5fpES2JDQxZ_4iWCCxboz4W" +
                "N3HaQQHumHUwzgjeoz8895qpnL8YKVXqjL8ZwKBgwQBoQB4Qmh0dHBzOi8vdmVyaWZpZXIuZXhhbXBsZS5jb20vd" +
                "mVyaWZpZXIvYW5uZXhBUmVxdWVzdD9zZXNzaW9uSWQ9MTIzNAWA"

        /** The same request to https://issuer.gov@evil.example/x — user-info hiding the real host. */
        const val SPOOF_URI =
            "mdoc://pABjMS4xAYIB2BhYS6QBAiABIVggWojRgrzl9C76WZQ_MzWdLoqWj_KJ2T5fpES2JDQxZ_4iWCCxboz4W" +
                "N3HaQQHumHUwzgjeoz8895qpnL8YKVXqjL8ZwKBgwQBoQB4IWh0dHBzOi8vaXNzdWVyLmdvdkBldmlsLmV4YW1wb" +
                "GUveAWA"

        /** `{0: "1.0", 1: "nope", 2: []}` — Security is not an array. */
        const val BAD_SECURITY_QR = "mdoc:owBjMS4wAWRub3BlAoA"

        /** Cipher suite 2, a key on curve 300, method type 9 and a Bluetooth method with no options. */
        const val ODD_QR = "mdoc:owBjMS4wAYIC2BhHogECIBkBLAKCgwkBoIMCAaA"

        /** Version "1.0-beta", NFC only. */
        const val ODD_VERSION_QR =
            "mdoc:owBoMS4wLWJldGEBggHYGFhLpAECIAEhWCBaiNGCvOX0LvpZlD8zNZ0uipaP8onZPl-kRLYkNDFn_iJYILF" +
                "ujPhY3cdpBAe6YdTDOCN6jPzz3mqmcvxgpVeqMvxnAoGDAQGiABj_ARkBAA"

        /** A COSE key labelled by text: present, but not readable. */
        const val TEXT_LABEL_KEY_QR = "mdoc:ogBjMS4wAYIB2BhGoWNrdHkC"

        /** No Security at all. */
        const val NO_KEY_QR = "mdoc:ogBjMS4wAoGDAgGiAPUB9A"

        /** multipaz's second-edition device QR transcript: DeviceEngagement 1.1, OriginInfos, Capabilities. */
        const val MULTIPAZ_DEVICE_QR =
            "mdoc:pQBjMS4xAYIB2BhYS6QBAiABIVggWojRgrzl9C76WZQ_MzWdLoqWj_KJ2T5fpES2JDQxZ_4iWCCxboz4WN3" +
                "HaQQHumHUwzgjeoz8895qpnL8YKVXqjL8ZwKBgwIBowD0AfULUAAAAAGhI0jOiWtMdpczc-YFgAaiA_UE9Q"

        /** multipaz's Reader QR transcript (Annex D's reader key): ReaderEngagement 1.1, GATT central. */
        const val MULTIPAZ_READER_QR =
            "mdoc:owBjMS4xAYIB2BhYS6QBAiABIVggYOM5I4UEH1FAMFHyQVUxy1bdP5mccWhwE6rGdovIGH4iWCDljeuP2-k" +
                "H991TaCRVUaNHlvfSIVxEDDObsPe2e-zN-gKBgwIBowD0AfULUAAAAAGhI0jOiWtMdpczc-Y"

        /** The same reader offering both GATT modes plus L2CAP (BleOptions keys 2, 3 and 21). */
        const val MULTIPAZ_READER_L2CAP_QR =
            "mdoc:owBjMS4xAYIB2BhYS6QBAiABIVggYOM5I4UEH1FAMFHyQVUxy1bdP5mccWhwE6rGdovIGH4iWCDljeuP2-k" +
                "H991TaCRVUaNHlvfSIVxEDDObsPe2e-zN-gKBgwIBpwD1AfUC9AP1ClAAAAACoSNIzolrTHaXM3PmC1AAAAABoSN" +
                "IzolrTHaXM3PmFRiA"

    }

    private fun engagement(raw: String): MobileDrivingLicensePayload {
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.MobileDrivingLicense::class.java)
        return (p as ScanPayload.MobileDrivingLicense).payload
    }

    private fun rows(p: MobileDrivingLicensePayload): List<String> =
        p.labelledFields().map { "${it.label}: ${it.value}" }

    private fun explain(raw: String) = HeuristicExplainer.explain(raw, ScanPayloadParser.parse(raw))

    private val holderRows = listOf(
        "Format: ISO/IEC 18013-5 device engagement",
        "Shown by: A digital-ID wallet (mDL, EU PID or other mdoc)",
        "Meant for: An ID reader",
    )

    @Test fun annexDFixtureIsTheStandardsOwnBytes() {
        assertThat("mdoc:" + base64URL(hex(ANNEX_D_HEX))).isEqualTo(ANNEX_D_QR)
        assertThat(MobileDrivingLicenseParser.decodeBase64URL(ANNEX_D_QR.substring(5))!!.toList())
            .isEqualTo(hex(ANNEX_D_HEX).toList())
    }

    @Test fun decodesTheAnnexDDeviceEngagement() {
        val p = engagement(ANNEX_D_QR)
        assertThat(p.kind).isEqualTo(MobileDrivingLicensePayload.Kind.DEVICE_ENGAGEMENT)
        assertThat(p.decoded).isTrue()
        assertThat(p.version).isEqualTo("1.0")
        assertThat(p.cipherSuite).isEqualTo(1uL)
        assertThat(p.key).isEqualTo(MobileDrivingLicensePayload.KeyType(kty = 2uL, crv = 1uL))
        assertThat(p.channels).containsExactly(
            MobileDrivingLicensePayload.Channel.Bluetooth(peripheralServer = false, centralClient = true),
        )
        assertThat(p.serverRetrieval).isEmpty()
        assertThat(ScanPayloadParser.parse(ANNEX_D_QR).kindLabel).isEqualTo("Digital ID")
        assertThat(rows(p)).isEqualTo(holderRows + listOf(
            "ID data: None — only how to connect",
            "Version: 1.0",
            "Channels: Bluetooth LE",
            "Bluetooth LE: Central client mode",
            "Cipher suite: 1 (ECDH, AES-256-GCM)",
            "Device key: P-256 (EC2), one-time key",
        ))

        val e = explain(ANNEX_D_QR)
        assertThat(e.headline).isEqualTo("A digital-ID engagement code")
        assertThat(e.keyPoints).isEqualTo(listOf(
            "A digital-ID wallet's code for an ID reader (ISO/IEC 18013-5), for a mobile driving licence, an EU PID or another mdoc.",
            "A reader can connect over Bluetooth LE.",
            "It carries a fresh one-time key, not the ID itself.",
        ))
        assertThat(e.cautions).containsAtLeast(
            "Scan is not an ID reader: it cannot request or read the document.",
            "A nearby reader can use this code to start a request, which the wallet still asks its holder to approve.",
            "Only show your own code to the reader you mean to share with.",
        )
    }

    @Test fun decodesIsomdlsPeripheralServerExample() {
        val p = engagement(ISOMDL_QR)
        assertThat(p.kind).isEqualTo(MobileDrivingLicensePayload.Kind.DEVICE_ENGAGEMENT)
        assertThat(p.channels).containsExactly(
            MobileDrivingLicensePayload.Channel.Bluetooth(peripheralServer = true, centralClient = false),
        )
        assertThat(rows(p)).isEqualTo(holderRows + listOf(
            "ID data: None — only how to connect",
            "Version: 1.0",
            "Channels: Bluetooth LE",
            "Bluetooth LE: Peripheral server mode",
            "Cipher suite: 1 (ECDH, AES-256-GCM)",
            "Device key: P-256 (EC2), one-time key",
        ))
    }

    @Test fun showsHostsButNeverTokensPassphrasesOrRadioIdentifiers() {
        val p = engagement(RICH_QR)
        assertThat(p.channels).containsExactly(
            MobileDrivingLicensePayload.Channel.Nfc,
            MobileDrivingLicensePayload.Channel.Bluetooth(peripheralServer = true, centralClient = true),
            MobileDrivingLicensePayload.Channel.WifiAware,
        ).inOrder()
        assertThat(p.serverRetrieval).containsExactly(
            MobileDrivingLicensePayload.ServerRetrieval("WebAPI", "mdl.issuer.example"),
            MobileDrivingLicensePayload.ServerRetrieval("OIDC", "id.issuer.example"),
        ).inOrder()
        assertThat(rows(p)).isEqualTo(holderRows + listOf(
            "ID data: None — only how to connect",
            "Version: 1.0",
            "Channels: NFC, Bluetooth LE, Wi-Fi Aware",
            "Bluetooth LE: Peripheral server and central client modes",
            "Server retrieval: WebAPI at mdl.issuer.example, OIDC at id.issuer.example",
            "Cipher suite: 1 (ECDH, AES-256-GCM)",
            "Device key: P-256 (EC2), one-time key",
        ))
        val token = "It also carries a server-retrieval token: whoever scans it can ask the issuer for " +
            "the holder's data directly."
        assertThat(p.warnings())
            .isEqualTo(listOf("Only show your own code to the reader you mean to share with.", token))

        val e = explain(RICH_QR)
        assertThat(e.keyPoints).contains("A reader can connect over NFC, Bluetooth LE or Wi-Fi Aware.")
        assertThat(e.cautions).contains(token)
        val shown = (rows(p) + e.keyPoints + e.cautions + p.warnings() + listOf(e.headline, e.summary, p.notice))
            .joinToString("\n")
        for (secret in listOf("hunter2", "TOKEN", "SECRET", "45ef", "45EF", "0a1b2c", "/api/v1", "8443", "/oidc")) {
            assertThat(shown).doesNotContain(secret)
        }
    }

    @Test fun readerQRIsTheReadersCodeNotTheHolders() {
        val p = engagement(READER_QR)
        assertThat(p.kind).isEqualTo(MobileDrivingLicensePayload.Kind.READER_ENGAGEMENT)
        assertThat(ScanPayloadParser.parse(READER_QR).kindLabel).isEqualTo("ID Reader")
        assertThat(rows(p)).isEqualTo(listOf(
            "Format: ISO/IEC 18013-5 reader engagement",
            "Shown by: An ID reader",
            "Meant for: A digital-ID wallet (mDL, EU PID or other mdoc)",
            "ID data: None — only how to connect",
            "Version: 1.1",
            "Channels: Bluetooth LE",
            "Bluetooth LE: Central client mode",
            "Cipher suite: 1 (ECDH, AES-256-GCM)",
            "Reader key: P-256 (EC2), one-time key",
        ))
        val e = explain(READER_QR)
        assertThat(e.headline).isEqualTo("An ID reader's engagement code")
        assertThat(e.keyPoints).isEqualTo(listOf(
            "An ID reader's code for a digital-ID wallet (ISO/IEC 18013-5): it invites a wallet to connect so the reader can ask for an ID.",
            "A wallet would connect over Bluetooth LE.",
            "It carries the reader's one-time key, not anyone's ID.",
        ))
        assertThat(e.cautions).containsAtLeast(
            "Scan is not a wallet: it cannot answer the reader or share a document.",
            "Scanned with a wallet, it lets the reader ask for data, and the wallet asks you to approve what is shared.",
            "Only scan a reader's code with your wallet where you meant to show your ID.",
        )
    }

    @Test fun deviceEngagement11StaysTheHolders() {
        // OriginInfos and Capabilities only ever ride in a DeviceEngagement.
        val p = engagement(DEVICE_ENGAGEMENT_11_QR)
        assertThat(p.kind).isEqualTo(MobileDrivingLicensePayload.Kind.DEVICE_ENGAGEMENT)
        assertThat(p.key).isEqualTo(MobileDrivingLicensePayload.KeyType(kty = 1uL, crv = 4uL))
        assertThat(rows(p)).isEqualTo(holderRows + listOf(
            "ID data: None — only how to connect",
            "Version: 1.1",
            "Channels: Bluetooth LE",
            "Bluetooth LE: Peripheral server mode",
            "Cipher suite: 1 (ECDH, AES-256-GCM)",
            "Device key: X25519 (OKP), one-time key",
        ))
    }

    @Test fun secondEditionQRsAreToldApart() {
        // multipaz's transcripts: its device QR carries OriginInfos and
        // Capabilities, its Reader QR (Annex D's reader key) neither.
        val device = engagement(MULTIPAZ_DEVICE_QR)
        assertThat(device.kind).isEqualTo(MobileDrivingLicensePayload.Kind.DEVICE_ENGAGEMENT)
        assertThat(rows(device)).isEqualTo(holderRows + listOf(
            "ID data: None — only how to connect",
            "Version: 1.1",
            "Channels: Bluetooth LE",
            "Bluetooth LE: Central client mode",
            "Cipher suite: 1 (ECDH, AES-256-GCM)",
            "Device key: P-256 (EC2), one-time key",
        ))
        val reader = engagement(MULTIPAZ_READER_QR)
        assertThat(reader.kind).isEqualTo(MobileDrivingLicensePayload.Kind.READER_ENGAGEMENT)
        assertThat(rows(reader).take(3)).isEqualTo(listOf(
            "Format: ISO/IEC 18013-5 reader engagement",
            "Shown by: An ID reader",
            "Meant for: A digital-ID wallet (mDL, EU PID or other mdoc)",
        ))
        assertThat(rows(reader).last()).isEqualTo("Reader key: P-256 (EC2), one-time key")
        val l2cap = engagement(MULTIPAZ_READER_L2CAP_QR)
        assertThat(l2cap.kind).isEqualTo(MobileDrivingLicensePayload.Kind.READER_ENGAGEMENT)
        assertThat(rows(l2cap)).contains("Bluetooth LE: Peripheral server and central client modes")
    }

    @Test fun onlineRequestShowsTheSitesHostOnly() {
        val p = engagement(ONLINE_URI)
        assertThat(p.kind).isEqualTo(MobileDrivingLicensePayload.Kind.ONLINE_REQUEST)
        assertThat(p.channels).containsExactly(MobileDrivingLicensePayload.Channel.Website("verifier.example.com"))
        assertThat(ScanPayloadParser.parse(ONLINE_URI).kindLabel).isEqualTo("ID Request")
        assertThat(rows(p)).isEqualTo(listOf(
            "Format: ISO/IEC 18013-7 online request",
            "Shown by: A website",
            "Meant for: A digital-ID wallet (mDL, EU PID or other mdoc)",
            "ID data: None — only how to connect",
            "Version: 1.1",
            "Channels: Website",
            "Website: verifier.example.com",
            "Cipher suite: 1 (ECDH, AES-256-GCM)",
            "Reader key: P-256 (EC2), one-time key",
        ))
        val e = explain(ONLINE_URI)
        assertThat(e.headline).isEqualTo("An online ID request")
        assertThat(e.keyPoints).isEqualTo(listOf(
            "A website's request for a digital ID (ISO/IEC 18013-7): it asks a wallet to share a mobile driving licence, an EU PID or another mdoc online.",
            "A wallet would connect to verifier.example.com.",
            "It carries the site's one-time key, not anyone's ID.",
        ))
        assertThat(e.cautions).containsAtLeast(
            "Scan is not a wallet: it cannot answer this request or share a document.",
            "Opened in a wallet, it lets the site ask for data, and the wallet asks you to approve what is shared.",
            "Only continue in your wallet for a site you opened yourself, and check its address.",
        )
        val shown = (rows(p) + e.keyPoints + e.cautions).joinToString("\n")
        assertThat(shown).doesNotContain("sessionId")
        assertThat(shown).doesNotContain("1234")

        // Only the authority is the engagement; a path or query after it is not.
        assertThat(rows(engagement("$ONLINE_URI/ignored?x=1#y"))).isEqualTo(rows(p))
    }

    @Test fun websiteBehindUserInfoIsNotShown() {
        val p = engagement(SPOOF_URI)
        assertThat(p.channels).containsExactly(MobileDrivingLicensePayload.Channel.Website(null))
        assertThat(rows(p)).contains("Website: Unusual address — not shown")
        val e = explain(SPOOF_URI)
        val shown = (rows(p) + e.keyPoints + e.cautions).joinToString("\n")
        assertThat(shown).doesNotContain("issuer.gov")
        assertThat(shown).doesNotContain("evil")
    }

    @Test fun sheetTextPerKind() {
        val device = engagement(ANNEX_D_QR)
        val reader = engagement(READER_QR)
        val online = engagement(ONLINE_URI)
        assertThat(device.warnings())
            .isEqualTo(listOf("Only show your own code to the reader you mean to share with."))
        assertThat(reader.warnings())
            .isEqualTo(listOf("Only scan a reader's code with your wallet where you meant to show your ID."))
        assertThat(online.warnings())
            .isEqualTo(listOf("Only continue in your wallet for a site you opened yourself, and check its address."))
        assertThat(device.notice).isEqualTo(
            "Scan is not an ID reader and cannot request or read the document. A nearby reader can use this code to start a request, which the wallet still asks its holder to approve. The code carries a fresh one-time key, so it is not the ID itself.",
        )
        assertThat(reader.notice).isEqualTo(
            "Scan is not a wallet and cannot answer the reader. Scanned with a wallet, the code lets the reader ask for data, and the wallet asks you to approve what is shared. It carries the reader's one-time key, not anyone's ID.",
        )
        assertThat(online.notice).isEqualTo(
            "Scan is not a wallet and cannot answer this request. Opened in a wallet, the code lets the site ask for data, and the wallet asks you to approve what is shared. It carries the site's one-time key, not anyone's ID.",
        )
    }

    @Test fun hostIsStrictAndAsciiOnly() {
        val cases = listOf(
            "https://Verifier.Example.COM:443/x" to "verifier.example.com",
            "HTTP://192.168.1.10/a?b#c" to "192.168.1.10",
            "https://xn--mller-kva.de" to "xn--mller-kva.de",
            "https://example.com?x" to "example.com",
            "https://a@b.example/" to null,
            "https://example.com\\@evil.example/" to null,
            "https://exa\u212Ample.com/" to null,         // Kelvin sign, which lower-cases to "k"
            "https://ex\u0430mple.com/" to null,          // Cyrillic a
            "https://example..com/" to null,
            "https://.example.com/" to null,
            "https://example.com:65536/" to null,
            "https://example.com:/" to null,
            "https://[::1]/" to null,
            "https://exa mple.com/" to null,
            "ftp://example.com/" to null,
            "https://" to null,
            "example.com" to null,
        )
        for ((url, expected) in cases) {
            assertThat(MobileDrivingLicenseParser.hostOfURL(url)).isEqualTo(expected)
        }
    }

    @Test fun recognisesOnlyTheMdocScheme() {
        for (raw in listOf(ANNEX_D_QR, "MDOC:" + ANNEX_D_QR.substring(5), ONLINE_URI, "mdoc:AAAA")) {
            assertThat(MobileDrivingLicenseParser.looksLikeMdocURI(raw)).isTrue()
        }
        val annexB = "mdoc-openid4vp://?client_id=x&request_uri=https%3A%2F%2Fv.example%2Fr"
        for (raw in listOf(
            "mdoc:", "mdoc://", "mdoc:///x", "mdocx:owBj", "mdoc owBj", "mdoc:owBj ok",
            "https://example.com/mdoc:owBj", "mdoc:owBj\u0301",
            "mDL=?docType=org.iso.18013.5.1.mDL&readerRequest=oQ", annexB,
        )) {
            assertThat(MobileDrivingLicenseParser.looksLikeMdocURI(raw)).isFalse()
        }
        // ISO/IEC 18013-7 Annex B is an OpenID4VP request, not an engagement:
        // the dispatch leaves it to the verifiable-credential parser.
        assertThat(ScanPayloadParser.parse(annexB)).isNotInstanceOf(ScanPayload.MobileDrivingLicense::class.java)
    }

    @Test fun undecodableMdocIsRecognisedButSaysSo() {
        val undecodable = listOf(
            "mdoc:AAAA",                                    // CBOR 0 0 0: not one map
            BAD_SECURITY_QR,                                // Security is text
            ANNEX_D_QR.dropLast(1) + "d",                   // spare bits set
            "mdoc:ow=Bj",                                   // padding in the middle
            "mdoc:o",                                       // impossible length
            ANNEX_D_QR + "AAAA",                            // data after the map
            "mdoc:" + "A".repeat(5_000),                    // over the length cap
        )
        for (raw in undecodable) {
            val p = engagement(raw)
            assertThat(p.decoded).isFalse()
            assertThat(rows(p)).isEqualTo(holderRows + "Contents: Not decodable — damaged or a newer version")
        }
        assertThat(explain("mdoc:AAAA").keyPoints).isEqualTo(listOf(
            "A digital-ID wallet's code for an ID reader (ISO/IEC 18013-5), for a mobile driving licence, an EU PID or another mdoc.",
            "Its contents could not be decoded — it may be damaged or a newer version.",
        ))
        val online = engagement("mdoc://AAAA")
        assertThat(online.kind).isEqualTo(MobileDrivingLicensePayload.Kind.ONLINE_REQUEST)
        assertThat(online.decoded).isFalse()
    }

    @Test fun oddValuesAreNamedNotGuessed() {
        val odd = engagement(ODD_QR)
        assertThat(odd.channels).containsExactly(
            MobileDrivingLicensePayload.Channel.Other(9uL),
            MobileDrivingLicensePayload.Channel.Bluetooth(peripheralServer = false, centralClient = false),
        ).inOrder()
        assertThat(rows(odd)).isEqualTo(holderRows + listOf(
            "ID data: None — only how to connect",
            "Version: 1.0",
            "Channels: Type 9, Bluetooth LE",
            "Bluetooth LE: No mode stated",
            "Cipher suite: 2 (unknown)",
            "Device key: Curve 300 (EC2), one-time key",
        ))
        assertThat(explain(ODD_QR).keyPoints).contains("A reader can connect over Bluetooth LE.")

        assertThat(rows(engagement(ODD_VERSION_QR)).drop(3)).isEqualTo(listOf(
            "ID data: None — only how to connect",
            "Version: Unrecognised",
            "Channels: NFC",
            "Cipher suite: 1 (ECDH, AES-256-GCM)",
            "Device key: P-256 (EC2), one-time key",
        ))
        assertThat(rows(engagement(TEXT_LABEL_KEY_QR)).takeLast(3)).isEqualTo(listOf(
            "Channels: None stated",
            "Cipher suite: 1 (ECDH, AES-256-GCM)",
            "Device key: Present, type not readable",
        ))
        assertThat(rows(engagement(NO_KEY_QR)).takeLast(4)).isEqualTo(listOf(
            "Channels: Bluetooth LE",
            "Bluetooth LE: Peripheral server mode",
            "Cipher suite: Not stated",
            "Device key: Missing",
        ))
    }

    @Test fun hostileEngagementsFailCleanly() {
        val version = cborHead(0, 0) to cborText("1.0")
        // Retrieval methods nested far past the reader's cap — decoded
        // directly, and through a code short enough to stay under the
        // length cap.
        assertThat(MobileDrivingLicenseParser.decodeEngagement(
            cborMap(listOf(version, cborHead(0, 2) to ByteArray(20_000) { 0x81.toByte() } + byteArrayOf(1))),
        )).isNull()
        val deep = cborMap(listOf(version, cborHead(0, 2) to ByteArray(2_000) { 0x81.toByte() } + byteArrayOf(1)))
        assertThat(engagement("mdoc:" + base64URL(deep)).decoded).isFalse()
        // A map that promises four billion entries, and a Security array
        // that promises as many.
        assertThat(MobileDrivingLicenseParser.decodeEngagement(
            byteArrayOf(0xBA.toByte(), -1, -1, -1, -1) + version.first + version.second,
        )).isNull()
        assertThat(MobileDrivingLicenseParser.decodeEngagement(
            cborMap(listOf(version, cborHead(0, 1) to byteArrayOf(0x9A.toByte(), -1, -1, -1, -1, 1))),
        )).isNull()

        // Random bytes, half of them behind a map head: never a crash.
        var state = 0x9E3779B9.toInt()
        fun next(): Int {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return state
        }
        repeat(3_000) { round ->
            val bytes = MutableList((next().toUInt() % 96u).toInt()) { next().toByte() }
            if (round % 2 == 0) bytes.add(0, (0xA0 or (next().toUInt() % 8u).toInt()).toByte())
            val raw = (if (round % 3 == 0) "mdoc://" else "mdoc:") + base64URL(bytes.toByteArray())
            MobileDrivingLicenseParser.parse(raw)?.labelledFields()
        }
    }

    @Test fun titleCaseFallbackMatchesFoundationCapitalized() {
        // Each expectation was read off Foundation's `String.capitalized`.
        val cases = mapOf(
            "some custom element" to "Some Custom Element",
            "o'neil" to "O'neil",
            "it\u2019s" to "It\u2019s",
            "org.iso.mdl" to "Org.Iso.Mdl",
            "x2y" to "X2Y",
            "123abc" to "123Abc",
            "e-mail" to "E-Mail",
            "a_b" to "A_B",
            "mIxEd CaSe" to "Mixed Case",
            "\u01C6emal" to "\u01C5emal",
            "\u01C4EMAL" to "\u01C4emal",
            "\u00DFx" to "Ssx",
            "stra\u00DFe" to "Stra\u00DFe",
            "\u03A3\u0391\u03A3" to "\u03A3\u03B1\u03C3",
            "x\u0301y" to "X\u0301y",
            "\u05D0b" to "\u05D0B",
            "\uD801\uDC28x" to "\uD801\uDC00x",
            "  lead" to "  Lead",
            "" to "",
        )
        for ((input, expected) in cases) {
            assertThat(input.asCapitalizedWords()).isEqualTo(expected)
        }
        assertThat("line2address".asCapitalizedWords()).isEqualTo("Line2Address")
        assertThat(VerifiableCredentialParser.scopeName("o'neil_scope")).isEqualTo("O'neil Scope")
    }

    // ---- Fixture helpers (the iOS test helpers) -------------------------

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun cborHead(major: Int, arg: Int): ByteArray {
        val m = major shl 5
        return when {
            arg < 24 -> byteArrayOf((m or arg).toByte())
            arg < 256 -> byteArrayOf((m or 24).toByte(), arg.toByte())
            else -> byteArrayOf((m or 25).toByte(), (arg shr 8).toByte(), (arg and 0xFF).toByte())
        }
    }

    private fun cborText(s: String): ByteArray =
        s.toByteArray(Charsets.UTF_8).let { cborHead(3, it.size) + it }

    private fun cborMap(pairs: List<Pair<ByteArray, ByteArray>>): ByteArray =
        pairs.fold(cborHead(5, pairs.size)) { acc, (k, v) -> acc + k + v }

    private fun base64URL(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
