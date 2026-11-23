package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The Generate tab's rule-based "describe the code" engine on the iOS
 * `SafetyParityTests.composeCases`, with the same expected form for every
 * request: `kind | key=value;… | text` (values sorted by key, `nil` when the
 * engine declines).
 */
class ComposeRuleEngineTest {

    @Test fun everyRequestComposesExactlyAsOnIos() {
        val mismatches = cases.mapNotNull { (request, expected) ->
            val actual = describe(HeuristicExplainer.compose(request))
            if (actual == expected) null else "$request\n  expected: $expected\n  actual:   $actual"
        }
        assertThat(mismatches).isEmpty()
    }

    private fun describe(result: ComposeResult?): String {
        if (result == null) return "nil |  | "
        val values = result.values.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" }
        return "${result.kind.name.lowercase()} | $values | ${result.text}"
    }

    private companion object {
        val cases: List<Pair<String, String>> = listOf(
        "wifi network BlueHouse password hunter2 hidden" to
            "wifi | hidden=true;password=hunter2;ssid=BlueHouse | ",
        "url https://example.com/a" to
            "url |  | https://example.com/a",
        "just some words" to
            "nil |  | ",
        "wifi BlueHouse password hunter2 hidden" to
            "wifi | hidden=true;password=hunter2;ssid=BlueHouse | ",
        "wifi network named Casa Verde password verde2026 wpa3" to
            "wifi | password=verde2026;security=WPA3;ssid=Casa Verde | ",
        "url https://example.com/menu" to
            "url |  | https://example.com/menu",
        "make me a code for my coffee shop" to
            "nil |  | ",
        "wifi" to
            "nil |  | ",
        "something clever" to
            "nil |  | ",
        "Wi-Fi QR for guest network BlueHouse, password hunter2" to
            "wifi | password=hunter2;ssid=BlueHouse | ",
        "Guest wifi called Cafe Lumen with password espresso42" to
            "wifi | password=espresso42;ssid=Cafe Lumen | ",
        "make a wifi code for my network \"Home 5G\" with password abc12345" to
            "wifi | password=abc12345;ssid=Home 5G | ",
        "wifi Key West password sunset99" to
            "wifi | password=sunset99;ssid=Key West | ",
        "WiFi for BlueHouse pw hunter2" to
            "wifi | password=hunter2;ssid=BlueHouse | ",
        "Wi-Fi QR code for the guest network, name Guests, password abc12345" to
            "wifi | password=abc12345;ssid=Guests | ",
        "Create a QR code for Wi-Fi network Office-5G password Tr0ub4dor&3" to
            "wifi | password=Tr0ub4dor&3;ssid=Office-5G | ",
        "wifi network BlueHouse hidden wpa2 password hunter2" to
            "wifi | hidden=true;password=hunter2;security=WPA2;ssid=BlueHouse | ",
        "open wifi network named Garden" to
            "wifi | security=NOPASS;ssid=Garden | ",
        "wifi network Cafe.Net password is beans4all." to
            "wifi | password=beans4all;ssid=Cafe.Net | ",
        "wifi Ana's network password \"correct horse\"" to
            "wifi | password=correct horse;ssid=Ana's network | ",
        "wifi password hunter2" to
            "nil |  | ",
        "vcard for Ana Ivi\u0107 +381641234567 ana@example.com" to
            "contact | email=ana@example.com;name=Ana Ivi\u0107;phone=+381641234567 | ",
        "contact card for Marko Petrovi\u0107, phone 064 123 4567, company Acme" to
            "contact | name=Marko Petrovi\u0107;org=Acme;phone=064 123 4567 | ",
        "a QR for our website example.com/menu" to
            "url |  | https://example.com/menu",
        "link to https://example.com/contact." to
            "url |  | https://example.com/contact",
        "text anything at all" to
            "text |  | anything at all",
        "note: back in 5 minutes" to
            "text |  | back in 5 minutes",
        "wifi Garden no password hidden" to
            "wifi | hidden=true;security=NOPASS;ssid=Garden | ",
        "Wi-Fi network name: Office 5G, password: Tr0ub4dor&3" to
            "wifi | password=Tr0ub4dor&3;ssid=Office 5G | ",
        "wifi\u00A0BlueHouse password\u00A0hunter2" to
            "wifi | password=hunter2;ssid=BlueHouse | ",
        "contact card for \u017Dana \u0106iri\u0107, phone +381 64 123 4567, email zana@example.com" to
            "contact | email=zana@example.com;name=\u017Dana \u0106iri\u0107;phone=+381 64 123 4567 | ",
        "vcard for Jos\u00E9 \u00C1lvarez 0612345678" to
            "contact | name=Jos\u00E9 \u00C1lvarez;phone=0612345678 | ",
        "business card for Anna M\u00FCller, works at Siemens AG, tel 089 1234567" to
            "contact | name=Anna M\u00FCller;org=Siemens AG;phone=089 1234567 | ",
        "a QR for our website m\u00FCnchen.de" to
            "url |  | https://m\u00FCnchen.de",
        "website: www.example.com/menu." to
            "url |  | https://www.example.com/menu",
        "message Hello there" to
            "text |  | Hello there",
        "link for user@example.com" to
            "nil |  | ",
        "wifi Key West open network" to
            "nil |  | ",
        "WIFI password for Home: abc 123" to
            "nil |  | ",
        "contact for Ana" to
            "contact | name=Ana | ",
        "vcard" to
            "nil |  | ",
        "website example" to
            "nil |  | ",
        "url ftp://example.com" to
            "nil |  | ",
        "note:back soon" to
            "nil |  | ",
        "TEXT Hello" to
            "text |  | Hello",
        "wifi \u201CAna\u2019s Place\u201D pw \u2018s3cret\u2019" to
            "wifi | password=s3cret;ssid=Ana\u2019s Place | ",
        "ssid: Lab-5G, key: Lab\$ecret!" to
            "wifi | password=Lab\$ecret!;ssid=Lab-5G | ",
        )
    }
}
