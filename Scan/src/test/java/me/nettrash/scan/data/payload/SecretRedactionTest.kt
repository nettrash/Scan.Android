package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The redaction in front of the explanation engine, on the iOS
 * `SafetyParityTests` fixtures with the exact same expected output: every
 * credential blanked with its field name kept, and ordinary prose — "Enter
 * your password below", "pin the poster" — left exactly as it was.
 */
class SecretRedactionTest {

    @Test fun everyFixtureRedactsExactlyAsOnIos() {
        val mismatches = fixtures.mapNotNull { (input, expected) ->
            val actual = SecretRedaction.redactForModel(input)
            if (actual == expected) null else "$input\n  expected: $expected\n  actual:   $actual"
        }
        assertThat(mismatches).isEmpty()
    }

    @Test fun theListedSecretsNeverReachTheEngine() {
        for ((input, _) in fixtures) {
            for (secret in listOf(
                "oaKazRN8I0IbtZ0C7JuMn5", "eyJhbGciOiJSU0Et", "SplxlOBeZQQYbYS6WxSbIA", "493536",
                "1234567890abcdefghijklmnopqrstuvwxyzAB", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
                "wJalrXUtnFEMI", "XXXXXXXXXXXXXXXXXXXXXXXX", "Sunshine99", "123456789&", "987654321",
            )) {
                assertWithMessage("$input leaks $secret").that(SecretRedaction.redactForModel(input)).doesNotContain(secret)
            }
        }
    }

    @Test fun placeholdersAreTheIosOnes() {
        assertThat(SecretRedaction.PLACEHOLDER).isEqualTo("<redacted>")
        assertThat(SecretRedaction.BLOB_PLACEHOLDER).isEqualTo("<encoded-data omitted>")
        assertThat(SecretRedaction.SEED_PHRASE_PLACEHOLDER).isEqualTo("<seed phrase redacted>")
    }

    @Test fun outputIsCappedAtTwoThousandCodePoints() {
        val prose = SecretRedaction.redactForModel("scan this ".repeat(600))
        assertThat(prose.length).isEqualTo(2000)
        // Code points, not UTF-16 units: an emoji is never cut in half.
        val emoji = SecretRedaction.redactForModel("\uD83D\uDC4D\uD83C\uDFFD".repeat(1500))
        assertThat(emoji.codePointCount(0, emoji.length)).isEqualTo(2000)
        assertThat(Character.isHighSurrogate(emoji.last())).isFalse()
        // A long opaque blob is dropped before the cap applies.
        assertThat(SecretRedaction.redactForModel("a".repeat(5000))).isEqualTo("<encoded-data omitted>")
    }

    private companion object {
        // Fake Slack credentials, assembled at runtime so that no token-shaped
        // literal sits in the source: GitHub push protection rejects those.
        val fakeSlackToken = "xox" + "b-123456789012-1234567890123-AbCdEfGhIjKlMnOpQrStUvWx"
        val fakeSlackWebhook = "https://hooks.slack.com/services/" + "T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX"

        val fixtures: List<Pair<String, String>> = listOf(
        "WIFI:T:WPA;S:BlueHouse;P:hunter2hidden;H:false;;" to
            "WIFI:T:WPA;S:BlueHouse;P:<redacted>;H:false;;",
        "WIFI:S:Cafe;T:WPA;P:pa\\;ss\\\\word;H:true;;" to
            "WIFI:S:Cafe;T:WPA;P:<redacted>;H:true;;",
        "WIFI:P:firstfield;S:x;;" to
            "WIFI:P:<redacted>;S:x;;",
        "wifi:s:Net;p:lowercase1;;" to
            "wifi:s:Net;p:<redacted>;;",
        "otpauth://totp/ACME:alice@acme.com?secret=JBSWY3DPEHPK3PXP&issuer=ACME" to
            "otpauth://totp/ACME:alice@acme.com?secret=<redacted>&issuer=ACME",
        "otpauth://totp/x?issuer=E&SECRET=jbswy3dpehpk3pxp%3D&digits=6" to
            "otpauth://totp/x?issuer=E&SECRET=<redacted>&digits=6",
        "otpauth-migration://offline?data=CjEKCkhlbGxvId6tvu8SBUFsaWNlGgdFeGFtcGxlIAEoATACEAEYASAA%3D" to
            "otpauth-migration://offline?data=<redacted>",
        "https://api.example.com/reset?token=abcdef123456&user=bob" to
            "https://api.example.com/reset?token=<redacted>&user=bob",
        "https://example.com/cb?access_token=ya29.a0AfH6SMB&auth_token=Zx81kQ&next=1" to
            "https://example.com/cb?access_token=<redacted>&auth_token=<redacted>&next=1",
        "x-api-key: AKIAIOSFODNN7EXAMPLE" to
            "x-api-key: <redacted>",
        "client_secret=s3cr3tValue&grant_type=x" to
            "client_secret=<redacted>&grant_type=x",
        "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl" to
            "Authorization: Bearer <redacted>",
        "password=correcthorsebattery" to
            "password=<redacted>",
        "password: sunshine2026" to
            "password: <redacted>",
        "pin = 4821" to
            "pin = <redacted>",
        "The Wi-Fi password is espresso42, enjoy!" to
            "The Wi-Fi password is <redacted> enjoy!",
        "Guest wifi password: espresso42 enjoy" to
            "Guest wifi password: <redacted> enjoy",
        "Password is: Tr0ub4dor&3" to
            "Password is: <redacted>",
        "PIN code: 1234" to
            "PIN code: <redacted>",
        "PIN 4321 for the locker" to
            "PIN <redacted> for the locker",
        "Locker passcode 0000" to
            "Locker passcode <redacted>",
        "https://zoom.us/j/1234567890?pwd=AbCdEf123456" to
            "https://zoom.us/j/1234567890?pwd=<redacted>",
        "user_password=hunter2&lang=en" to
            "user_password=<redacted>&lang=en",
        "wifi password \"correct horse\" at the bar" to
            "wifi password <redacted> at the bar",
        "NSEC1QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ and nsec1abcdefgh" to
            "NSEC1<redacted> and nsec1<redacted>",
        "nostr:nsec1qvz8cq6d8t0f0y3gqk0q0m3v9z2xk" to
            "nostr:nsec1<redacted>",
        "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----" to
            "-----BEGIN RSA PRIVATE KEY-----<redacted>",
        "-----BEGIN PGP PRIVATE KEY BLOCK-----\nlQOYBF\n-----END PGP PRIVATE KEY BLOCK-----" to
            "-----BEGIN PGP PRIVATE KEY BLOCK-----<redacted>",
        "5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ" to
            "<redacted>",
        "KwdMAjGmerYanjeui5SHS7JkmpZvVipYvB2LJGU1ZxJwYvP98617" to
            "<redacted>",
        "abandon ability able about above absent absorb abstract absurd abuse access accident" to
            "<seed phrase redacted>",
        "ABANDON ABILITY ABLE ABOUT ABOVE ABSENT ABSORB ABSTRACT ABSURD ABUSE ACCESS ACCIDENT" to
            "<seed phrase redacted>",
        "abandon ability able about\nabove absent absorb abstract\nabsurd abuse access accident" to
            "<seed phrase redacted>",
        "https://user:hunter2@evil.example/" to
            "https://user:<redacted>@evil.example/",
        "ftp://anonymous:me@ftp.example/pub" to
            "ftp://anonymous:<redacted>@ftp.example/pub",
        "https://paypal.com@evil.example/checkout" to
            "https://paypal.com@evil.example/checkout",
        "https://example.com:8443/x" to
            "https://example.com:8443/x",
        "Enter your password below to continue" to
            "Enter your password below to continue",
        "Forgot your password? Reset it here" to
            "Forgot your password? Reset it here",
        "Spin the wheel to win" to
            "Spin the wheel to win",
        "Please pin the poster to the wall" to
            "Please pin the poster to the wall",
        "PIN code required at checkout" to
            "PIN code required at checkout",
        "Your password was changed yesterday" to
            "Your password was changed yesterday",
        "Use a password manager" to
            "Use a password manager",
        "Password protected PDF inside" to
            "Password protected PDF inside",
        "The secret garden opens at 9" to
            "The secret garden opens at 9",
        "A token of appreciation" to
            "A token of appreciation",
        "Visit Pinterest for ideas" to
            "Visit Pinterest for ideas",
        "http://example.com/menu Help: call us" to
            "http://example.com/menu Help: call us",
        "-----BEGIN PUBLIC KEY-----\nMFkwEwYH\n-----END PUBLIC KEY-----" to
            "-----BEGIN PUBLIC KEY-----\nMFkwEwYH\n-----END PUBLIC KEY-----",
        "bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh?amount=0.01" to
            "bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh?amount=0.01",
        "1BoatSLRHtKNngkdXEeobR76b53LETtpyT" to
            "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
        "abandon ability able about above absent absorb abstract absurd abuse access" to
            "abandon ability able about above absent absorb abstract absurd abuse access",
        "BEGIN:VCARD\nVERSION:3.0\nFN:Ana Ivi\u0107\nTEL:+381641234567\nEND:VCARD" to
            "BEGIN:VCARD\nVERSION:3.0\nFN:Ana Ivi\u0107\nTEL:+381641234567\nEND:VCARD",
        "MECARD:N:Ivi\u0107,Ana;TEL:+381641234567;EMAIL:ana@example.com;;" to
            "MECARD:N:Ivi\u0107,Ana;TEL:+381641234567;EMAIL:ana@example.com;;",
        "Scan this code to pay 39 EUR now. Contact us on Telegram t.me/support_pay" to
            "Scan this code to pay 39 EUR now. Contact us on Telegram t.me/support_pay",
        "Meeting point: main entrance, third floor, room 12" to
            "Meeting point: main entrance, third floor, room 12",
        // Bare service tokens keep only the prefix that names them
        "ghp_1234567890abcdefghijklmnopqrstuvwxyzAB" to
            "ghp_<redacted>",
        "github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOP" to
            "github_pat_<redacted>",
        "sk-proj-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789" to
            "sk-proj-<redacted>",
        "sk_live_51HAbCdEfGhIjKlMnOpQrSt" to
            "sk_live_<redacted>",
        fakeSlackToken to
            "xoxb-<redacted>",
        "AKIAIOSFODNN7EXAMPLE wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY" to
            "AKIA<redacted> <redacted>",
        "aws_access_key_id=AKIAIOSFODNN7EXAMPLE" to
            "aws_access_key_id=AKIA<redacted>",
        fakeSlackWebhook to
            "https://hooks.slack.com/services/<redacted>",
        "https://discord.com/api/webhooks/123456789012345678/abcdefGHIJKL-mnopQRSTuvwx" to
            "https://discord.com/api/webhooks/<redacted>",
        "pass: Sunshine99" to
            "pass: <redacted>",
        "Wi-Fi pass: Sunshine99" to
            "Wi-Fi pass: <redacted>",
        "API key: sk-proj-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789" to
            "API key: <redacted>",
        "phonefactor://activate_account?code=123456789&url=https://mobileappcommunicator.auth.microsoft.com/activate/987654321/WUS" to
            "phonefactor://activate_account?code=<redacted>&url=<redacted>",
        "Boarding passport: X1234567 at gate 12" to
            "Boarding passport: X1234567 at gate 12",
        // Seed phrases however they are written down; sentences that only
        // look like one pass through
        "1. abandon 2. ability 3. able 4. about 5. above 6. absent 7. absorb 8. abstract 9. absurd 10. abuse 11. access 12. accident" to
            "<seed phrase redacted>",
        "Seed: abandon ability able about above absent absorb abstract absurd abuse access accident" to
            "<seed phrase redacted>",
        "abandon,ability,able,about,above,absent,absorb,abstract,absurd,abuse,access,accident" to
            "<seed phrase redacted>",
        "Welcome guests please enjoy your stay and visit our rooftop bar tonight" to
            "Welcome guests please enjoy your stay and visit our rooftop bar tonight",
        "Love you forever happy anniversary from your family with all our hearts" to
            "Love you forever happy anniversary from your family with all our hearts",
        "Scan with your phone camera follow the steps shown below thank you" to
            "Scan with your phone camera follow the steps shown below thank you",
        // OpenID4VCI 1.0's own examples: an offer by value, percent-encoded;
        // the same offer as JSON; the authorization-code offer; the token
        // request. The tx_code object only describes the code and stays.
        "openid-credential-offer://?credential_offer=%7B%22credential_issuer%22:%22https://credential-issuer.example.com%22,%22credential_configuration_ids%22:%5B%22org.iso.18013.5.1.mDL%22%5D,%22grants%22:%7B%22urn:ietf:params:oauth:grant-type:pre-authorized_code%22:%7B%22pre-authorized_code%22:%22oaKazRN8I0IbtZ0C7JuMn5%22,%22tx_code%22:%7B%22input_mode%22:%22text%22,%22description%22:%22Please%20enter%20the%20serial%20number%20of%20your%20physical%20drivers%20license%22%7D%7D%7D%7D" to
            "openid-credential-offer://?credential_offer=%7B%22credential_issuer%22:%22https://credential-issuer.example.com%22,%22credential_configuration_ids%22:%5B%22org.iso.18013.5.1.mDL%22%5D,%22grants%22:%7B%22urn:ietf:params:oauth:grant-type:pre-authorized_code%22:%7B%22pre-authorized_code%22:%22<redacted>%22,%22tx_code%22:%7B%22input_mode%22:%22text%22,%22description%22:%22Please%20enter%20the%20serial%20number%20of%20your%20physical%20drivers%20license%22%7D%7D%7D%7D",
        "{\n  \"credential_issuer\": \"https://credential-issuer.example.com\",\n  \"credential_configuration_ids\": [\n    \"UniversityDegreeCredential\",\n    \"org.iso.18013.5.1.mDL\"\n  ],\n  \"grants\": {\n    \"urn:ietf:params:oauth:grant-type:pre-authorized_code\": {\n      \"pre-authorized_code\": \"oaKazRN8I0IbtZ0C7JuMn5\",\n      \"tx_code\": {\n        \"length\": 4,\n        \"input_mode\": \"numeric\",\n        \"description\": \"Please provide the one-time code that was sent via e-mail\"\n      }\n    }\n  }\n}" to
            "{\n  \"credential_issuer\": \"https://credential-issuer.example.com\",\n  \"credential_configuration_ids\": [\n    \"UniversityDegreeCredential\",\n    \"org.iso.18013.5.1.mDL\"\n  ],\n  \"grants\": {\n    \"urn:ietf:params:oauth:grant-type:pre-authorized_code\": {\n      \"pre-authorized_code\": \"<redacted>\",\n      \"tx_code\": {\n        \"length\": 4,\n        \"input_mode\": \"numeric\",\n        \"description\": \"Please provide the one-time code that was sent via e-mail\"\n      }\n    }\n  }\n}",
        "{\"credential_issuer\": \"https://credential-issuer.example.com\", \"credential_configuration_ids\": [\"UniversityDegreeCredential\"], \"grants\": {\"authorization_code\": {\"issuer_state\": \"eyJhbGciOiJSU0Et...FYUaBy\"}}}" to
            "{\"credential_issuer\": \"https://credential-issuer.example.com\", \"credential_configuration_ids\": [\"UniversityDegreeCredential\"], \"grants\": {\"authorization_code\": {\"issuer_state\": \"<redacted>\"}}}",
        "grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=SplxlOBeZQQYbYS6WxSbIA&tx_code=493536" to
            "grant_type=urn:ietf:params:oauth:grant-type:pre-authorized_code&pre-authorized_code=<redacted>&tx_code=<redacted>",
        )
    }
}
