package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The decoding helpers every 1.10 identity and invoice parser stands on.
 * Each JSON expectation below was read off Foundation's
 * `JSONSerialization`, because that — not RFC 8259 and not `org.json` —
 * is what decides what the iOS app accepts.
 */
@RunWith(RobolectricTestRunner::class)
class PayloadCodecTest {

    private fun json(s: String) = PayloadCodec.jsonObjectFromString(s)

    // ---- base64 ----------------------------------------------------------

    @Test fun base64URLPadsButRefusesForeignCharacters() {
        assertThat(PayloadCodec.base64URLString("aGk")).isEqualTo("hi")
        assertThat(PayloadCodec.base64URLString(" aGk\n")).isEqualTo("hi")
        assertThat(PayloadCodec.base64URLData("-_8")!!.toList())
            .containsExactly(0xFB.toByte(), 0xFF.toByte()).inOrder()
        // Inside the blob, a space or `!` is damage, not something to skip.
        assertThat(PayloadCodec.base64URLData("aG k")).isNull()
        assertThat(PayloadCodec.base64URLData("aG!k")).isNull()
        assertThat(PayloadCodec.base64URLData("a")).isNull()
    }

    @Test fun invoiceBase64SkipsForeignCharactersButNeedsPadding() {
        assertThat(PayloadCodec.base64Data("aGk=")?.toString(Charsets.UTF_8)).isEqualTo("hi")
        assertThat(PayloadCodec.base64Data("aG\r\nk=")?.toString(Charsets.UTF_8)).isEqualTo("hi")
        assertThat(PayloadCodec.base64Data("aGk")).isNull()
    }

    @Test fun utf8IsStrict() {
        assertThat(PayloadCodec.utf8(byteArrayOf(0x68, 0x69))).isEqualTo("hi")
        assertThat(PayloadCodec.utf8(byteArrayOf(0xC3.toByte()))).isNull()
        assertThat(PayloadCodec.utf8(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))).isNull()
    }

    // ---- JSON: what JSONSerialization accepts ------------------------------

    @Test fun acceptsWhatFoundationAccepts() {
        for (doc in listOf(
            "{\"a\":1}", " {\"a\":1} ", "{}", "{\"\":1}", "{\"a\" :1}", "{\"a\": 1 }",
            "{\"a\":1}\n", "{\"a\":1}  \t\r\n", "\uFEFF{\"a\":1}",
            "{\"a\":1,}", "{\"a\":1 , }", "{\"a\":{},}", "{\"a\":1,\"b\":[1,2,],}",
            "{\"a\":-0}", "{\"a\":1e5}", "{\"a\":1E+5}", "{\"a\":-1.5E-3}", "{\"a\":1.0e-0}",
            "{\"a\":\"\\/\"}", "{\"a\":\"\\u0000\"}", "{\"a\":\"\u007F\"}",
        )) {
            assertThat(json(doc)).isNotNull()
        }
    }

    @Test fun refusesWhatFoundationRefuses() {
        for (doc in listOf(
            // org.json would take every one of these first six.
            "{a:1}", "{'a':1}", "{\"a\"=1}", "{\"a\":1;\"b\":2}", "{\"a\":1} // c", "{/*c*/\"a\":1}",
            "{\"a\":1} x", "{\"a\":1}\u0000", "{\"a\":1}\uFEFF", "\uFEFF\uFEFF{}", "{\u00A0\"a\":1}",
            "{\"a\":1,,}", "{,}", "{\"a\":[1,,2]}", "{\"a\":[,]}", "{\"a\":[1 2]}", "{\"a\":\"x\"\"b\":1}",
            "{\"a\":[1],\"b\":}", "{\"a\":01}", "{\"a\":-01}", "{\"a\":0x1F}", "{\"a\":1.}", "{\"a\":2.}",
            "{\"a\":.5}", "{\"a\":-}", "{\"a\":1.5e}", "{\"a\":NaN}", "{\"a\":1e400}", "{\"a\":1.8e308}",
            "{\"a\":tru}", "{\"a\":True}", "{\"a\":\"\\x41\"}", "{\"a\":\"\\U00e9\"}", "{\"a\":\"\\u00\"}",
            "{\"a\":\"\\uD800\"}", "{\"a\":\"\\uDC00\"}", "{\"a\":\"\\uD83Dx\"}",
            "{\"a\":\"tab\there\"}", "{\"a\":\"nl\nhere\"}", "{\"a\":\"\u0001\"}",
            "{\"a\":1", "{\"a\":1}}", "",
        )) {
            assertThat(json(doc)).isNull()
        }
    }

    @Test fun onlyAnObjectIsADocument() {
        for (doc in listOf("[1,2]", "[{\"a\":1}]", "\"x\"", "1", "null", " ")) {
            assertThat(json(doc)).isNull()
        }
    }

    @Test fun valuesComeBackAsPlainKotlin() {
        val o = json(
            "{\"s\":\"\\u00e9\\uD83D\\uDE00\\b\\f\\n\\r\\t\\\"\\\\\",\"i\":-42,\"big\":9223372036854775807," +
                "\"huge\":100000000000000000000,\"d\":0.1,\"tiny\":1e-400,\"t\":true,\"f\":false,\"n\":null," +
                "\"arr\":[1,\"x\",[],{}],\"obj\":{\"k\":[true]}}"
        )!!
        assertThat(o["s"]).isEqualTo("\u00E9\uD83D\uDE00\b\u000C\n\r\t\"\\")
        assertThat(o["i"]).isEqualTo(-42L)
        assertThat(o["big"]).isEqualTo(Long.MAX_VALUE)
        assertThat(o["huge"]).isEqualTo(1e20)
        assertThat(o["d"]).isEqualTo(0.1)
        assertThat(o["tiny"]).isEqualTo(0.0)
        assertThat(o["t"]).isEqualTo(true)
        assertThat(o["f"]).isEqualTo(false)
        assertThat(o.containsKey("n")).isTrue()
        assertThat(o["n"]).isNull()
        assertThat(o["arr"]).isEqualTo(listOf(1L, "x", emptyList<Any?>(), emptyMap<String, Any?>()))
        assertThat(o["obj"]).isEqualTo(mapOf("k" to listOf(true)))
    }

    @Test fun aTrailingCommaAddsNothing() {
        // org.json reads `[1,]` as `[1, null]`.
        assertThat(json("{\"a\":[1,]}")!!["a"]).isEqualTo(listOf(1L))
        assertThat(json("{\"_sd\":[\"a\",\"b\",]}")!!["_sd"]).isEqualTo(listOf("a", "b"))
    }

    @Test fun theFirstOfTwoDuplicateKeysWins() {
        assertThat(json("{\"a\":1,\"a\":2}")!!["a"]).isEqualTo(1L)
        assertThat(json("{\"a\":true,\"a\":false}")!!["a"]).isEqualTo(true)
    }

    @Test fun oneLeadingByteOrderMarkLeavesEveryString() {
        val o = json("{\"a\":\"\\uFEFF\",\"\\uFEFFk\":1,\"b\":\"\\uFEFF\\uFEFFx\",\"c\":[\"\\uFEFFz\"],\"d\":\"x\\uFEFFy\"}")!!
        assertThat(o["a"]).isEqualTo("")
        assertThat(o["k"]).isEqualTo(1L)
        assertThat(o["b"]).isEqualTo("\uFEFFx")
        assertThat(o["c"]).isEqualTo(listOf("z"))
        assertThat(o["d"]).isEqualTo("x\uFEFFy")
    }

    @Test fun nestingStopsAtTheSharedCap() {
        // iOS refuses these before `JSONSerialization` sees them (its
        // `testJSONNestingStopsAtTheSharedCap`): the same 64 containers.
        fun objects(n: Int) = "{\"a\":".repeat(n) + "1" + "}".repeat(n)
        fun arrays(n: Int) = "{\"a\":" + "[".repeat(n) + "]".repeat(n) + "}"
        assertThat(PayloadCodec.MAX_JSON_DEPTH).isEqualTo(64)
        assertThat(json(objects(64))).isNotNull()
        assertThat(json(objects(65))).isNull()
        assertThat(json(arrays(63))).isNotNull()     // 64 containers, counting the object
        assertThat(json(arrays(64))).isNull()
        // Far deeper input is refused, not a stack overflow.
        assertThat(json(arrays(100_000))).isNull()
        // Brackets inside a string are text, escaped quotes and all.
        val text = "{\"s\":\"" + "[{".repeat(200) + "\\\"]}\",\"n\":"
        assertThat(json(text + objects(63) + "}")).isNotNull()
        assertThat(json(text + objects(64) + "}")).isNull()
    }

    @Test fun jsonIsUtf8WithoutNuls() {
        fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        assertThat(PayloadCodec.jsonObject("{\"a\":1}".toByteArray())).isNotNull()
        assertThat(PayloadCodec.jsonObject(bytes(0xEF, 0xBB, 0xBF) + "{\"a\":1}".toByteArray())).isNotNull()
        assertThat(json("{\"a\":\"\\u0000\"}")).isNotNull()          // an escaped NUL is text
        assertThat(PayloadCodec.jsonObject(bytes(0x7B, 0x00, 0x7D, 0x00))).isNull()              // UTF-16LE
        assertThat(PayloadCodec.jsonObject(bytes(0xFF, 0xFE, 0x7B, 0x00, 0x7D, 0x00))).isNull()  // with a BOM
        assertThat(PayloadCodec.jsonObject(bytes(0x00, 0x7B, 0x00, 0x7D))).isNull()              // UTF-16BE
        assertThat(PayloadCodec.jsonObject(bytes(0x7B, 0x7D, 0x00))).isNull()
        assertThat(PayloadCodec.jsonObject(bytes(0x7B, 0x22, 0x61, 0x22, 0x3A, 0x22, 0xFF, 0x22, 0x7D))).isNull()
    }

    @Test fun base64URLTrimsSwiftsWhitespaceOnly() {
        // Kotlin's `trim()` would strip U+001C–U+001F and keep U+0085.
        assertThat(PayloadCodec.base64URLString(" aGk\n")).isEqualTo("hi")
        assertThat(PayloadCodec.base64URLString("\u0085aGk\u2028")).isEqualTo("hi")
        assertThat(PayloadCodec.base64URLString("\u200BaGk")).isEqualTo("hi")
        assertThat(PayloadCodec.base64URLString("\u001FaGk")).isNull()
        assertThat(PayloadCodec.base64URLString("aGk\u001C")).isNull()
    }

    @Test fun credentialTokenGateUsesTheStrictReader() {
        fun b64(s: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        val header = b64("{\"alg\":\"ES256\",\"typ\":\"JWT\"}")
        // `{vct:'x'}` is not JSON, so this is not a credential — on iOS either.
        assertThat(VerifiableCredentialParser.looksLikeCredentialToken("$header.${b64("{vct:'x'}")}.c2ln")).isFalse()
        // A present-but-null claim still counts, as `!= nil` on NSNull does.
        val nullVct = "$header.${b64("{\"iss\":\"https://i.example\",\"vct\":null,\"cnf\":null}")}.c2ln"
        assertThat(VerifiableCredentialParser.looksLikeCredentialToken(nullVct)).isTrue()
        assertThat(VerifiableCredentialParser.parse(nullVct)?.keyBinding).isTrue()
    }
}
