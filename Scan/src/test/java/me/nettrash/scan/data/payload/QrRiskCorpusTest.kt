package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One corpus, character for character the iOS `SafetyParityTests.riskCorpus`.
 * Ordinary codes — popular sites, internationalised domains, Wi-Fi, payment
 * and contact codes — must produce nothing at all, so the badge keeps its
 * meaning; the classic tricks must produce the same finding codes at the same
 * levels on both platforms. Each entry is `level | code:severity, …` with the
 * findings in report order.
 */
@RunWith(RobolectricTestRunner::class)
class QrRiskCorpusTest {

    @Test fun everyInputGetsTheSameVerdictAsOnIos() {
        val mismatches = corpus.mapNotNull { (raw, expected) ->
            val actual = verdict(raw)
            if (actual == expected) null else "$raw\n  expected: $expected\n  actual:   $actual"
        }
        assertThat(mismatches).isEmpty()
    }

    @Test fun theCorpusCoversOrdinaryCodesAndTricks() {
        assertThat(corpus.size).isAtLeast(60)
        assertThat(corpus.count { it.second == "safe | " }).isAtLeast(40)
        assertThat(corpus.count { it.second.startsWith("danger") }).isAtLeast(10)
    }

    /** One detector: what the banner calls a seed phrase is what an engine never sees. */
    @Test fun theRedactionBlanksEverySeedPhraseTheSafetyCheckFlags() {
        for ((raw, verdict) in corpus) {
            if (!verdict.contains("secret-exposed:danger") || !SeedPhrase.isMnemonic(raw)) continue
            assertWithMessage(raw).that(SecretRedaction.redactForModel(raw)).isEqualTo(SecretRedaction.SEED_PHRASE_PLACEHOLDER)
        }
    }

    private fun verdict(raw: String): String {
        val report = QrRiskAssessor.assess(raw, ScanPayloadParser.parse(raw))
        return report.level.name.lowercase() + " | " +
            report.findings.joinToString(", ") { "${it.code}:${it.severity.name.lowercase()}" }
    }

    private companion object {
        val corpus: List<Pair<String, String>> = listOf(
        // Ordinary links
        "https://www.google.com/search?q=weather+belgrade" to
            "safe | ",
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ" to
            "safe | ",
        "https://youtu.be/dQw4w9WgXcQ" to
            "safe | ",
        "https://en.wikipedia.org/wiki/QR_code" to
            "safe | ",
        "https://www.amazon.co.uk/gp/help/customer/display.html" to
            "safe | ",
        "https://github.com/apple/swift" to
            "safe | ",
        "https://apps.apple.com/us/app/whatsapp-messenger/id310633997" to
            "safe | ",
        "https://play.google.com/store/apps/details?id=com.whatsapp" to
            "safe | ",
        "https://maps.google.com/?q=44.8125,20.4612" to
            "safe | ",
        "https://www.instagram.com/natgeo/" to
            "safe | ",
        "https://www.linkedin.com/company/microsoft/" to
            "safe | ",
        "https://www.booking.com/hotel/rs/example.html" to
            "safe | ",
        "https://wa.me/381641234567" to
            "safe | ",
        "https://t.me/durov" to
            "safe | ",
        "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC" to
            "safe | ",
        "https://paypal.me/example" to
            "safe | ",
        "https://www.bbc.co.uk/news" to
            "safe | ",
        "https://www.ubereats.com/" to
            "safe | ",
        "https://www.telegraph.co.uk/news/" to
            "safe | ",
        "https://login.microsoftonline.com/" to
            "safe | ",
        "https://www.wish.com/" to
            "safe | ",
        "https://Google.com/" to
            "safe | ",
        "https://eBay.com/" to
            "safe | ",
        "https://www.dhl.com/de-de/home/tracking.html?tracking-id=00340434161094042557" to
            "safe | ",
        "https://example.com/disclaimer" to
            "safe | ",
        "https://example.net/01/04006000200007" to
            "safe | ",
        // Internationalised domains
        "https://m\u00FCnchen.de/" to
            "safe | ",
        "https://www.b\u00FCcher.de/" to
            "safe | ",
        "https://xn--mnchen-3ya.de/" to
            "safe | ",
        "https://www.xn--bcher-kva.de/" to
            "safe | ",
        "https://\u043F\u0440\u0435\u0437\u0438\u0434\u0435\u043D\u0442.\u0440\u0444/" to
            "safe | ",
        "https://\u65E5\u672C\u8A9E.jp/" to
            "safe | ",
        // Ordinary non-links
        "WIFI:T:WPA;S:CafeNet;P:coffee2026;H:false;;" to
            "safe | ",
        "WIFI:T:WPA;S:HiddenNet;P:secret123;H:true;;" to
            "safe | ",
        "BCD\n002\n1\nSCT\nBPOTBEB1\nRed Cross Belgium\nBE72000000001616\nEUR10.00\n\n\nDonation" to
            "safe | ",
        "00020101021226580014br.gov.bcb.pix0136123e4567-e89b-12d3-a456-426614174000520458125303986540512.505802BR5909ACME LTDA6009SAO PAULO62070503***6304BF76" to
            "safe | ",
        "BEGIN:VCARD\nVERSION:3.0\nFN:Ana Ivi\u0107\nTEL:+381641234567\nEMAIL:ana@example.com\nEND:VCARD" to
            "safe | ",
        "bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh" to
            "safe | ",
        "mailto:hello@example.com?subject=Hi" to
            "safe | ",
        "tel:+381641234567" to
            "safe | ",
        "geo:44.8125,20.4612" to
            "safe | ",
        "weixin://wxpay/bizpayurl?pr=abc123" to
            "safe | ",
        "Meeting point: main entrance, third floor, room 12" to
            "safe | ",
        // Cautions by design
        "WIFI:T:nopass;S:Cafe Guest;;" to
            "caution | open-wifi:caution",
        "WIFI:S:Cafe Guest;P:;;" to
            "caution | open-wifi:caution",
        "00020101021126580014br.gov.bcb.pix0136123e4567-e89b-12d3-a456-4266141740005204581253039865802BR5909ACME LTDA6009SAO PAULO62070503***63046BF3" to
            "caution | static-payment-code:caution",
        "000201010211520458125303986540525.005802BR6009SAO PAULO630474A9" to
            "caution | payment-no-merchant:caution, static-amount-poster:caution, static-payment-code:caution",
        "bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh?amount=0.01" to
            "caution | crypto-amount-preset:caution",
        "otpauth://totp/ACME:alice@acme.com?secret=JBSWY3DPEHPK3PXP&issuer=ACME" to
            "caution | otp-share-warning:caution",
        // Tricks
        "https://paypaI.com/signin" to
            "danger | lookalike-domain:danger",
        "https://paypa1.com/" to
            "danger | lookalike-domain:danger",
        "https://www.amaz0n.com/" to
            "danger | lookalike-domain:danger",
        "https://gooogle.com/" to
            "danger | lookalike-domain:danger",
        "https://paypal-secure-login.com/" to
            "danger | lookalike-domain:danger",
        "https://xn--pple-43d.com/" to
            "danger | mixed-script-host:danger, lookalike-domain:danger",
        "https://\u0430pple.com/" to
            "danger | mixed-script-host:danger, lookalike-domain:danger",
        "https://xn--80ak6aa92e.com/" to
            "danger | punycode-host:danger, lookalike-domain:danger",
        "https://p\u00E1ypal.com/" to
            "danger | lookalike-domain:danger",
        "https://xn--zz9999999999.com/" to
            "danger | punycode-host:danger",
        "https://user@evil.example/" to
            "danger | credentials-in-url:danger",
        "https://paypal.com@evil.example/checkout" to
            "danger | credentials-in-url:danger",
        "http://203.0.113.9/login" to
            "caution | ip-literal-host:caution, plain-http:caution",
        "http://192.168.1.10/admin" to
            "caution | ip-literal-host:note, plain-http:caution",
        "https://example.com:8888/" to
            "caution | odd-port:caution",
        "https://trusted.example/go?url=https://evil.example/login" to
            "caution | open-redirect:caution",
        "https://bit.ly/3xYzAbC" to
            "caution | url-shortener:caution",
        "https://giveaway.top/claim" to
            "caution | abuse-tld:caution, bait-keyword:caution",
        "https://a.b.c.d.example.com/" to
            "caution | deep-subdomain:caution",
        "https://example.com/p?data=QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=" to
            "caution | base64-payload:caution",
        "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==" to
            "danger | dangerous-scheme:danger",
        "javascript:alert(1)" to
            "danger | dangerous-scheme:danger",
        "Scan this code to pay 39 EUR now. Questions? Contact us on Telegram t.me/support_pay" to
            "caution | lure-text:caution",
        "nostr:nsec1qvz8cq6d8t0f0y3gqk0q0m3v9z2xk" to
            "caution | secret-exposed:caution",
        "abandon ability able about above absent absorb abstract absurd abuse access accident" to
            "danger | secret-exposed:danger",
        // Links a browser repairs: no slashes or extra ones, backslashes,
        // line breaks — graded for where they really go
        "https:paypa1.com/login" to
            "danger | lookalike-domain:danger",
        "https:/paypa1.com/login" to
            "danger | lookalike-domain:danger",
        "HTTPS:PAYPA1.COM" to
            "danger | lookalike-domain:danger",
        "https:////paypa1.com/login" to
            "danger | lookalike-domain:danger",
        "https:\\\\paypa1.com\\login" to
            "danger | lookalike-domain:danger",
        "https://paypa1.com\n/login" to
            "danger | lookalike-domain:danger",
        "http:accounts.google.com@evil.example/" to
            "danger | credentials-in-url:danger, plain-http:caution",
        "https:///paypal.com@evil.example/" to
            "danger | credentials-in-url:danger",
        "https://paypal.com @evil.example/login" to
            "danger | credentials-in-url:danger",
        "https://paypal.com@\nevil.example/" to
            "danger | credentials-in-url:danger",
        "http:203.0.113.9/login" to
            "caution | ip-literal-host:caution, plain-http:caution",
        // A backslash before the login's @ is graded the way iOS opens it
        "https://paypal.com\\@evil.example/login" to
            "danger | credentials-in-url:danger",
        "https://www.paypal.com\\@paypa1.com/signin" to
            "danger | lookalike-domain:danger, credentials-in-url:danger",
        "https://google.com\\@203.0.113.9/" to
            "danger | ip-literal-host:caution, credentials-in-url:danger",
        // Brands that are everyday words
        "https://www.big-apple-pizza.com/menu" to
            "safe | ",
        "https://www.ups-and-downs-cafe.com/menu" to
            "safe | ",
        "https://www.wise-owl-books.co.uk/" to
            "safe | ",
        "https://www.amazon-river-tours.com.br/" to
            "safe | ",
        "https://www.daily-telegram.com/" to
            "safe | ",
        "https://pop-ups-shop.com/" to
            "safe | ",
        "https://grown-ups-club.com/" to
            "safe | ",
        "https://apple-support-login.com/" to
            "danger | lookalike-domain:danger",
        "https://ups-delivery.com/" to
            "danger | lookalike-domain:danger",
        // A brand's address in front of somebody else's domain, and the
        // brands added in 1.10
        "https://paypal.com.secure-login.info/" to
            "danger | lookalike-domain:danger",
        "https://apple.com-support.help/" to
            "danger | lookalike-domain:danger",
        "https://www.paypal.com.account-security-check.net/signin" to
            "danger | lookalike-domain:danger, deep-subdomain:caution",
        "https://microsoft.github.io/" to
            "safe | ",
        "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ" to
            "safe | ",
        "https://youtube-premium-offer.com/" to
            "danger | lookalike-domain:danger",
        "https://www.youtubee.com/watch?v=abc" to
            "danger | lookalike-domain:danger",
        "https://linkedin-jobs-verify.com/" to
            "danger | lookalike-domain:danger",
        "https://usps-redelivery-center.com/track" to
            "danger | lookalike-domain:danger",
        "https://adobe-docsign-review.com/" to
            "danger | lookalike-domain:danger",
        // IPv4 hosts in the spellings browsers accept
        "https://3627732046/login" to
            "caution | ip-literal-host:caution",
        "https://0xd83ad60e/login" to
            "caution | ip-literal-host:caution",
        "https://0330.072.0326.016/login" to
            "caution | ip-literal-host:caution",
        "https://216.58.0xd6.14/" to
            "caution | ip-literal-host:caution",
        "https://0xc0000201/login" to
            "caution | ip-literal-host:caution",
        "http://3627732046/" to
            "caution | ip-literal-host:caution, plain-http:caution",
        "https://0x7f.1/" to
            "safe | ip-literal-host:note",
        // Redirectors to another site
        "https://www.google.com/url?q=https://evil.example/login&sa=D&source=editors" to
            "caution | open-redirect:caution",
        "https://www.youtube.com/redirect?event=video_description&redir_token=QUFFLUhqa&q=https%3A%2F%2Fevil.example%2F" to
            "caution | open-redirect:caution",
        "https://www.bing.com/ck/a?!&&p=abc123&u=a1aHR0cHM6Ly9ldmlsLmV4YW1wbGUvbG9naW4&ntb=1" to
            "caution | open-redirect:caution",
        "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fevil.example%2F" to
            "caution | open-redirect:caution",
        "https://r.search.yahoo.com/_ylt=abc/RV=2/RE=1700000000/RO=10/RU=https%3a%2f%2fevil.example%2f/RK=2/RS=abc" to
            "caution | open-redirect:caution",
        "https://www.google.com/amp/s/evil.example/login" to
            "caution | open-redirect:caution",
        "https://accounts.google.com/ServiceLogin?continue=https://evil.example/" to
            "caution | open-redirect:caution",
        // Ordinary sign-in returns, deep links and government sites
        "https://accounts.google.com/ServiceLogin?service=mail&continue=https://mail.google.com/mail/u/0/" to
            "safe | ",
        "https://www.facebook.com/login/?next=https%3A%2F%2Fwww.facebook.com%2Fgroups%2F123%2F" to
            "safe | ",
        "https://www.instagram.com/accounts/login/?next=https%3A%2F%2Fwww.instagram.com%2Fnatgeo%2F" to
            "safe | ",
        "https://www.linkedin.com/login?session_redirect=https%3A%2F%2Fwww.linkedin.com%2Ffeed%2F" to
            "safe | ",
        "https://www.reddit.com/login/?dest=https%3A%2F%2Fwww.reddit.com%2Fr%2Fpics%2F" to
            "safe | ",
        "https://www.amazon.com/ap/signin?openid.return_to=https%3A%2F%2Fwww.amazon.com%2F&openid.mode=checkid_setup" to
            "safe | ",
        "https://signin.ebay.com/ws/eBayISAPI.dll?SignIn&ru=https%3A%2F%2Fwww.ebay.com%2F" to
            "safe | ",
        "https://github.com/login?return_to=https%3A%2F%2Fgithub.com%2Fsettings%2Fprofile" to
            "safe | ",
        "https://example.com/?menu=lunch&img=https://cdn.example.com/a.png" to
            "safe | ",
        "https://www.amazon.com/progress-tracker/package/ref=ppx_yo_dt_b_track_package" to
            "safe | ",
        "https://www.paypal.com/invoice/p/#INV2-ABCD-1234" to
            "safe | ",
        "https://www.tax.service.gov.uk/" to
            "safe | ",
        "https://www.service.nsw.gov.au/" to
            "safe | ",
        "https://www.health.nsw.gov.au/" to
            "safe | ",
        "https://www.cl.cam.ac.uk/" to
            "safe | ",
        "https://www.city.shibuya.tokyo.jp/" to
            "safe | ",
        "https://www.receita.fazenda.gov.br/" to
            "safe | ",
        "https://my-bucket.s3.us-east-1.amazonaws.com/menu.pdf" to
            "safe | ",
        "https://maps.app.goo.gl/X8yZ3kq9WmLpT6Ra7" to
            "safe | ",
        "https://linktr.ee/selenagomez" to
            "caution | url-shortener:caution",
        "market://details?id=com.whatsapp" to
            "safe | store-link:note",
        "Questions about your booking? WhatsApp us on +34 600 123 456. Your card will not be blocked or charged until arrival." to
            "safe | ",
        // Seed phrases are BIP-39 words, however they are written down
        "Welcome guests please enjoy your stay and visit our rooftop bar tonight" to
            "safe | ",
        "Love you forever happy anniversary from your family with all our hearts" to
            "safe | ",
        "Scan with your phone camera follow the steps shown below thank you" to
            "safe | ",
        "abandon\nability\nable\nabout\nabove\nabsent\nabsorb\nabstract\nabsurd\nabuse\naccess\naccident" to
            "danger | secret-exposed:danger",
        "1. abandon 2. ability 3. able 4. about 5. above 6. absent 7. absorb 8. abstract 9. absurd 10. abuse 11. access 12. accident" to
            "danger | secret-exposed:danger",
        "Seed: abandon ability able about above absent absorb abstract absurd abuse access accident" to
            "danger | secret-exposed:danger",
        "abandon,ability,able,about,above,absent,absorb,abstract,absurd,abuse,access,accident" to
            "danger | secret-exposed:danger",
        "5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ" to
            "danger | secret-exposed:danger",
        "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318" to
            "safe | ",
        )
    }
}
