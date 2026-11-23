package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.security.MessageDigest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mirrors the iOS `QrRiskTests` coverage: every finding `code` is asserted by
 * name (they are the ids the UI and a future "mute this check" setting use),
 * and — just as important — the two clean codes the feature lives or dies on
 * must produce **zero** non-note findings. The cross-platform corpus lives in
 * [QrRiskCorpusTest]; the checks below are the iOS `SafetyParityTests` ones.
 */
@RunWith(RobolectricTestRunner::class)
class QrRiskAssessorTest {

    private fun codes(raw: String, codesInFrame: Int = 1): List<String> {
        val payload = ScanPayloadParser.parse(raw)
        return QrRiskAssessor.assess(raw, payload, codesInFrame).findings.map { it.code }
    }

    private fun report(raw: String, codesInFrame: Int = 1): QrRiskReport {
        val payload = ScanPayloadParser.parse(raw)
        return QrRiskAssessor.assess(raw, payload, codesInFrame)
    }

    // ---- Host-level checks -------------------------------------------------

    @Test fun punycodeHostIsDanger() {
        val r = QrRiskAssessor.assess("https://xn--80ak6aa92e.com/", ScanPayload.Url("https://xn--80ak6aa92e.com/"))
        assertThat(r.level).isEqualTo(QrRiskLevel.DANGER)
        assertThat(r.findings.map { it.code }).contains("punycode-host")
        assertThat(r.headline).isEqualTo("Internationalised domain")
    }

    @Test fun lookalikeDomainIsDangerAndNamesTheBrandProperly() {
        val raw = "https://paypaI.com/checkout"
        val r = report(raw)
        assertThat(r.level).isEqualTo(QrRiskLevel.DANGER)
        val finding = r.findings.first { it.code == "lookalike-domain" }
        // "PayPal", not "Paypal" — the display table is part of the contract.
        assertThat(finding.title).isEqualTo("Impersonates PayPal")
        assertThat(finding.detail).contains("PayPal does not operate on this domain")
    }

    @Test fun caseMixingHomoglyphSurvivesLowercasing() {
        // `paypaI` lowercases to `paypai`, which plain Levenshtein already
        // reads as a typo — the uppercase-confusable fold is what catches it.
        assertThat(QrRiskAssessor.assess("https://paypaI.com/", null).findings.map { it.code })
            .contains("lookalike-domain")
    }

    @Test fun mixedScriptHostIsCaughtOnTheWrittenHost() {
        // Cyrillic "а" in аpple.com. `Uri`/`URI` would hand back punycode and
        // erase the very character the check needs.
        val raw = "https://\u0430pple.com/shop"
        val r = QrRiskAssessor.assess(raw, ScanPayload.Url(raw))
        assertThat(r.findings.map { it.code }).contains("mixed-script-host")
        assertThat(r.level).isEqualTo(QrRiskLevel.DANGER)
    }

    @Test fun urlShortenerIsCaution() {
        val r = report("https://bit.ly/3xYzAb")
        assertThat(r.findings.map { it.code }).contains("url-shortener")
        assertThat(r.level).isEqualTo(QrRiskLevel.CAUTION)
    }

    @Test fun credentialsInUrlAreDanger() {
        val r = report("https://paypal.com@evil.example/checkout")
        assertThat(r.findings.map { it.code }).contains("credentials-in-url")
        assertThat(r.level).isEqualTo(QrRiskLevel.DANGER)
    }

    @Test fun plainHttpIsCaution() {
        val r = report("http://shop.example/cart")
        assertThat(r.findings.map { it.code }).contains("plain-http")
        assertThat(r.level).isEqualTo(QrRiskLevel.CAUTION)
    }

    @Test fun openRedirectIsCaution() {
        val r = report("https://trusted.example/go?url=https://evil.example/login")
        assertThat(r.findings.map { it.code }).contains("open-redirect")
    }

    @Test fun ipLiteralAndOddPortAreFlagged() {
        // 80 / 443 / 8080 / 8443 are treated as ordinary; anything else is odd.
        assertThat(codes("http://203.0.113.9:9000/pay")).contains("odd-port")
        assertThat(codes("https://example.com:8443/pay")).doesNotContain("odd-port")
        assertThat(codes("http://203.0.113.9/pay")).contains("ip-literal-host")
        // A private address is only worth a note — it is usually a printer.
        val local = QrRiskAssessor.assess("http://192.168.1.10/admin", null)
        assertThat(local.findings.first { it.code == "ip-literal-host" }.severity)
            .isEqualTo(QrRiskFinding.Severity.NOTE)
    }

    @Test fun abuseTldAndDeepSubdomainAreFlagged() {
        assertThat(codes("https://giveaway.top/claim")).contains("abuse-tld")
        assertThat(codes("https://a.b.c.paypaI.example/")).contains("deep-subdomain")
    }

    @Test fun dangerousSchemeIsDanger() {
        val r = QrRiskAssessor.assess("javascript:alert(1)", null)
        assertThat(r.findings.map { it.code }).contains("dangerous-scheme")
        assertThat(r.level).isEqualTo(QrRiskLevel.DANGER)
    }

    // ---- Frame-level checks ------------------------------------------------

    @Test fun twoCodesInOneFrameIsCaution() {
        val raw = "https://example.com/menu"
        val r = QrRiskAssessor.assess(raw, ScanPayload.Url(raw), codesInFrame = 2)
        assertThat(r.findings.map { it.code }).contains("multi-code-frame")
        assertThat(r.findings.first { it.code == "multi-code-frame" }.title)
            .isEqualTo("2 codes in this frame")
        assertThat(r.level).isEqualTo(QrRiskLevel.CAUTION)
    }

    // ---- Secrets -----------------------------------------------------------

    @Test fun privateKeyTextIsDanger() {
        val raw = """
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEAy7Kd3nZ0m1VhQ3YrT0pLbCjFgHsN2wXkLdJnBq9RtUvWxYz
            AbCdEfGhIjKlMnOpQrStUvWxYz0123456789+/abcdefghijklmnOPQRSTUVWXYZ
            -----END RSA PRIVATE KEY-----
        """.trimIndent()
        val r = QrRiskAssessor.assess(raw, ScanPayloadParser.parse(raw))
        assertThat(r.level).isEqualTo(QrRiskLevel.DANGER)
        assertThat(r.findings.map { it.code }).contains("secret-exposed")
        assertThat(r.findings.first { it.code == "secret-exposed" }.detail).contains("private key")
    }

    @Test fun nostrSecretKeyIsCautionNotDanger() {
        val r = QrRiskAssessor.assess("nsec1qvz8cq6d8t0f0y3gqk0q0m3v9z2xk", null)
        assertThat(r.findings.map { it.code }).contains("secret-exposed")
        assertThat(r.level).isEqualTo(QrRiskLevel.CAUTION)
    }

    // ---- Payload-shaped findings -------------------------------------------

    @Test fun emvWithoutMerchantNameFlagsTheStaticPosterTrio() {
        // 01 = 11 (static), 54 present (fixed amount), no 59 (merchant name).
        val raw = "000201" + "010211" + "52045812" + "5303986" + "540525.00" +
            "5802BR" + "6008SAOPAULO" + "6304ABCD"
        assertThat(ScanPayloadParser.parse(raw)).isInstanceOf(ScanPayload.EmvPayment::class.java)
        val found = codes(raw)
        assertThat(found).contains("payment-no-merchant")
        assertThat(found).contains("static-amount-poster")
        assertThat(found).contains("static-payment-code")
    }

    @Test fun emvWithMerchantNameStaysQuietAboutTheMerchant() {
        val raw = "000201" + "010212" + "52045812" + "5303986" + "540512.34" +
            "5802BR" + "5909ACME LTDA" + "6008SAOPAULO" + "6304ABCD"
        assertThat(codes(raw)).doesNotContain("payment-no-merchant")
    }

    @Test fun otpAuthCarriesTheShareWarning() {
        val raw = "otpauth://totp/ACME:alice@acme.com?secret=JBSWY3DPEHPK3PXP&issuer=ACME"
        assertThat(ScanPayloadParser.parse(raw)).isInstanceOf(ScanPayload.Otp::class.java)
        assertThat(codes(raw)).contains("otp-share-warning")
    }

    @Test fun openWifiNetworkIsCaution() {
        val raw = "WIFI:T:NOPASS;S:Cafe Guest;H:false;;"
        assertThat(codes(raw)).contains("open-wifi")
    }

    @Test fun telegramPaymentLureIsFlaggedAsLureText() {
        val raw = "Scan this code to pay \u00a31.00 \u2014 contact us on Telegram @pay2me"
        assertThat(ScanPayloadParser.parse(raw)).isInstanceOf(ScanPayload.Text::class.java)
        assertThat(codes(raw)).contains("lure-text")
    }

    @Test fun ordinaryFreeTextIsNotALure() {
        val raw = "Meeting point: main entrance, third floor, room 12"
        assertThat(QrRiskAssessor.assess(raw, ScanPayloadParser.parse(raw)).findings).isEmpty()
    }

    // ---- Parity with iOS (SafetyParityTests) --------------------------------

    @Test fun onlyWebLinksAreGradedAsWebAddresses() {
        // App-scheme rich links fall through to the scheme check, as on iOS:
        // grading them as web addresses invented findings on ordinary codes.
        for (raw in listOf(
            "weixin://wxpay/bizpayurl?pr=abc123",
            "alipays://platformapi/startapp?saId=10000007",
            "matrix:u/alice:example.org",
        )) {
            assertThat(QrRiskAssessor.assess(raw, ScanPayloadParser.parse(raw)).findings).isEmpty()
        }
        val ftp = "ftp://files.example/x"
        assertThat(QrRiskAssessor.assess(ftp, ScanPayload.Url(ftp)).findings).isEmpty()
    }

    @Test fun openWifiFiresForAMissingOrEmptyPasswordWithNoSecurity() {
        assertThat(codes("WIFI:S:Cafe;P:;;")).contains("open-wifi")
        assertThat(codes("WIFI:T:;S:Cafe;;")).contains("open-wifi")
        assertThat(codes("WIFI:T:NONE;S:Cafe;;")).contains("open-wifi")
        // Declared WPA with the password left out is a broken code, not an open network.
        assertThat(codes("WIFI:T:WPA;S:Cafe;P:;;")).doesNotContain("open-wifi")
        assertThat(QrRiskAssessor.isOpenNetwork(null, null)).isTrue()
        assertThat(QrRiskAssessor.isOpenNetwork("", "nopass")).isTrue()
        assertThat(QrRiskAssessor.isOpenNetwork("x", null)).isFalse()
        assertThat(QrRiskAssessor.isOpenNetwork(null, "WPA")).isFalse()
    }

    @Test fun lookalikeHelperReadsLookAlikesNotRealWords() {
        for (host in listOf(
            "paypa1.com", "paypaI.com", "gooogle.com", "paypla.com", "rnicrosoft.com", "paypa1.co.uk",
            "revolut-pay.example", "ups-delivery.com", "xn--80ak6aa92e.com", "\u0430pple.com",
        )) {
            assertWithMessage(host).that(QrRiskAssessor.looksLookalike(host)).isTrue()
        }
        for (host in listOf(
            "Google.com", "ubereats.com", "telegraph.co.uk", "wish.com", "login.microsoftonline.com",
            "s3.amazonaws.com", "groups.io", "startups.com", "revolt.chat", "example.com", "revolut.com",
        )) {
            assertWithMessage(host).that(QrRiskAssessor.looksLookalike(host)).isFalse()
        }
    }

    @Test fun registrableDomainOfAThreeLabelTwoPartSuffixHost() {
        assertThat(QrRiskAssessor.registrableDomain("example.co.uk")).isEqualTo("example.co.uk")
        assertThat(QrRiskAssessor.registrableDomain("bbc.co.uk")).isEqualTo("bbc.co.uk")
        assertThat(QrRiskAssessor.registrableDomain("example.com")).isEqualTo("example.com")
    }

    @Test fun punycodeDecodesRfc3492Labels() {
        assertThat(Punycode.decode("mnchen-3ya")).isEqualTo("m\u00FCnchen")
        assertThat(Punycode.decode("bcher-kva")).isEqualTo("b\u00FCcher")
        assertThat(Punycode.decode("80ak6aa92e")).isEqualTo("\u0430\u0440\u0440\u04CF\u0435")
        assertThat(Punycode.decode("d1abbgf6aiiy")).isEqualTo("\u043F\u0440\u0435\u0437\u0438\u0434\u0435\u043D\u0442")
        assertThat(Punycode.decode("wgv71a119e")).isEqualTo("\u65E5\u672C\u8A9E")
        assertThat(Punycode.decode("zz9999999999")).isNull()
    }

    @Test fun theHostIsTheOneAfterTheLogin() {
        // The look-alike sits in the login part; the real destination is evil.example.
        assertThat(codes("https://\u0430pple.com@evil.example/")).containsExactly("credentials-in-url")
        assertThat(codes("https://user@paypa1.com:8443/"))
            .containsExactly("lookalike-domain", "credentials-in-url").inOrder()
    }

    @Test fun anIpv6HostIsNamedWithoutItsBrackets() {
        val finding = QrRiskAssessor.assess("https://[2001:db8::1]/admin").findings.single()
        assertThat(finding.code).isEqualTo("ip-literal-host")
        assertThat(finding.detail).contains("straight to 2001:db8::1 with no domain")
    }

    @Test fun aPercentEncodedHostIsReadTheWayABrowserReadsIt() {
        assertThat(codes("https://%70aypa1.com/")).containsExactly("lookalike-domain")
    }

    @Test fun pgpPrivateKeyBlockIsDanger() {
        val r = report("-----BEGIN PGP PRIVATE KEY BLOCK-----\nlQOYBF\n-----END PGP PRIVATE KEY BLOCK-----")
        assertThat(r.level).isEqualTo(QrRiskLevel.DANGER)
        assertThat(r.findings.map { it.code }).containsExactly("secret-exposed")
    }

    @Test fun baitWordsMatchWholeWordsOnly() {
        assertThat(codes("https://example.com/claim-now")).containsExactly("bait-keyword")
        assertThat(codes("https://example.com/unlocked")).isEmpty()
        assertThat(codes("https://example.com/disclaimer")).isEmpty()
    }

    // ---- The clean-code floor ---------------------------------------------

    @Test fun cleanUrlHasNoNonNoteFindings() {
        val raw = "https://example.com/menu"
        val r = QrRiskAssessor.assess(raw, ScanPayload.Url(raw))
        assertThat(r.level).isEqualTo(QrRiskLevel.SAFE)
        assertThat(r.findings.filter { it.severity != QrRiskFinding.Severity.NOTE }).isEmpty()
    }

    @Test fun cleanGs1DigitalLinkHasNoNonNoteFindings() {
        val raw = "https://example.net/01/04006000200007"
        val r = report(raw)
        assertThat(r.level).isEqualTo(QrRiskLevel.SAFE)
        assertThat(r.findings.filter { it.severity != QrRiskFinding.Severity.NOTE }).isEmpty()
    }

    @Test fun cleanWifiJoinIsSafe() {
        val raw = "WIFI:T:WPA;S:CafeNet;P:coffee2026;H:false;;"
        val r = report(raw)
        assertThat(r.level).isEqualTo(QrRiskLevel.SAFE)
        assertThat(r.findings.filter { it.severity != QrRiskFinding.Severity.NOTE }).isEmpty()
    }

    // ---- Helpers exposed to the identity sheets ----------------------------

    @Test fun looksLookalikeMatchesTyposAndRejectsRealDomains() {
        assertThat(QrRiskAssessor.looksLookalike("paypa1.com")).isTrue()
        assertThat(QrRiskAssessor.looksLookalike("paypaI.com")).isTrue()
        assertThat(QrRiskAssessor.looksLookalike("example.com")).isFalse()
        assertThat(QrRiskAssessor.looksLookalike("nettrash.me")).isFalse()
    }

    @Test fun registrableDomainKeepsTwoPartSuffixes() {
        assertThat(QrRiskAssessor.registrableDomain("shop.example.co.uk")).isEqualTo("example.co.uk")
        assertThat(QrRiskAssessor.registrableDomain("a.b.example.com")).isEqualTo("example.com")
    }

    // ---- Links a browser repairs (iOS SafetyParityTests) --------------------

    @Test fun aContactCardLinkWithoutSlashesIsStillGated() {
        val card = "BEGIN:VCARD\nVERSION:3.0\nFN:Ana\nURL:https:paypa1.com/login\nEND:VCARD"
        val contact = ScanPayloadParser.parse(card) as ScanPayload.Contact
        assertThat(contact.payload.urls).containsExactly("https:paypa1.com/login")
        // What `openNestedLink` grades.
        assertThat(QrRiskAssessor.assess(contact.payload.urls[0]).isBlocking).isTrue()
    }

    @Test fun aWebLinkWhoseHostCannotBeReadIsDanger() {
        val r = QrRiskAssessor.assess("https://pay pal.com/", ScanPayload.Url("https://pay pal.com/"))
        assertThat(r.findings.map { it.code }).containsExactly("unreadable-address")
        assertThat(r.isBlocking).isTrue()
        // Text has no Open button: nothing to warn about.
        assertThat(QrRiskAssessor.assess("https://pay pal.com/", ScanPayload.Text("https://pay pal.com/")).findings)
            .isEmpty()
    }

    @Test fun theGatedLinkDialogNamesTheHostLowerCased() {
        val message = QrRiskAssessor.gatedLinkMessage("https://paypaI.com/x")
        assertThat(message).startsWith("Impersonates PayPal\n\n")
        assertThat(message).contains("paypai.com")
        assertThat(message).doesNotContain("paypaI")
    }

    // ---- IPv4 hosts ----------------------------------------------------------

    @Test fun ipv4HostsAreReadTheWayABrowserReadsThem() {
        val cases = listOf<Pair<String, String?>>(
            "3627732046" to "216.58.204.78", "0xd83ad60e" to "216.58.214.14",
            "0330.072.0326.016" to "216.58.214.14", "216.58.0xd6.14" to "216.58.214.14",
            "0x7f.1" to "127.0.0.1", "127.1" to "127.0.0.1", "192.168.1.10" to "192.168.1.10",
            "1.2.3.4." to "1.2.3.4", "0x" to "0.0.0.0", "4294967295" to "255.255.255.255",
            "4294967296" to null, "256.1.1.1" to null, "1.2.3.4.5" to null, "08.1.1.1" to null,
            "1..2" to null, "0x1g" to null, "example.com" to null, "" to null,
        )
        for ((host, address) in cases) {
            assertWithMessage(host).that(QrRiskAssessor.ipv4Address(host)).isEqualTo(address)
        }
        val finding = QrRiskAssessor.assess("https://0x7f.1/").findings.first()
        assertThat(finding.severity).isEqualTo(QrRiskFinding.Severity.NOTE)
        assertThat(finding.detail).contains("straight to 127.0.0.1 (written as 0x7f.1) with no domain")
    }

    // ---- Seed phrases and wallet keys ----------------------------------------

    @Test fun theEmbeddedWordlistIsBip39English() {
        val words = SeedPhrase.englishWordlist
        assertThat(words).hasSize(2048)
        assertThat(listOf(words.first(), words[1024], words.last())).containsExactly("abandon", "length", "zoo").inOrder()
        // bip-0039/english.txt from github.com/bitcoin/bips, byte for byte.
        val file = (words.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) }
        assertThat(sha256).isEqualTo("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda")
        // The FNV-1a (64-bit) fingerprint the iOS test checks.
        var hash = 0xcbf29ce484222325uL
        for (byte in file) hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
        assertThat(hash.toString(16)).isEqualTo("82b47707efb51238")
    }

    @Test fun seedPhrasesAreRecognisedHoweverTheyAreWrittenDown() {
        val words = SeedPhrase.englishWordlist
        for (count in listOf(12, 15, 18, 21, 24)) {
            assertWithMessage("$count words").that(SeedPhrase.isMnemonic(words.take(count).joinToString(" "))).isTrue()
        }
        for (count in listOf(11, 13, 14, 25)) {
            assertWithMessage("$count words").that(SeedPhrase.isMnemonic(words.take(count).joinToString(" "))).isFalse()
        }
        val twelve = words.take(12)
        val numbered = twelve.indices.joinToString(" ") { "${it + 1}) ${twelve[it]}" }
        val padded = twelve.indices.joinToString("\n") { (if (it < 9) "0" else "") + "${it + 1}:${twelve[it]}" }
        for (written in listOf(
            twelve.joinToString("\r\n"), numbered, padded, twelve.joinToString(", ").uppercase(),
            "Recovery phrase:\n" + twelve.joinToString(" "), "SECRET RECOVERY PHRASE: " + twelve.joinToString(" "),
        )) {
            assertWithMessage(written).that(SeedPhrase.isMnemonic(written)).isTrue()
        }
        for (written in listOf(
            "Notes: " + twelve.joinToString(" "), twelve.joinToString("-"),
            twelve.dropLast(1).joinToString(" ") + " accidents",
        )) {
            assertWithMessage(written).that(SeedPhrase.isMnemonic(written)).isFalse()
        }
    }

    @Test fun aWalletImportKeyIsAPrivateKeyOnlyWhenItsChecksumHolds() {
        assertThat(QrRiskAssessor.containsWifKey("5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ")).isTrue()
        assertThat(
            QrRiskAssessor.containsWifKey("Paper wallet: KwdMAjGmerYanjeui5SHS7JkmpZvVipYvB2LJGU1ZxJwYvP98617 (keep it safe)"),
        ).isTrue()
        assertThat(QrRiskAssessor.containsWifKey("5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTK")).isFalse()
        assertThat(QrRiskAssessor.containsWifKey("1BoatSLRHtKNngkdXEeobR76b53LETtpyT")).isFalse()
        assertThat(codes("KwdMAjGmerYanjeui5SHS7JkmpZvVipYvB2LJGU1ZxJwYvP98617")).containsExactly("secret-exposed")
    }
}
