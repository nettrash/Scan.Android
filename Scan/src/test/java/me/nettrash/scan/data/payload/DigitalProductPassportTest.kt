package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The GS1 Digital Product Passport carrier added in 1.10: a
 * passport-shaped bundle of AIs classifies as its own kind, a plain
 * product code does not, and the resolver host is shown rather than
 * contacted. The `ios…` tests use the iOS suite's carriers verbatim.
 */
@RunWith(RobolectricTestRunner::class)
class DigitalProductPassportTest {

    private val passportCarrier = "(01)04012345678901(17)261231(11)260101(10)LOT42(21)SN-0099(422)276"
    private val plainCarrier = "(01)04012345678901(17)261231(10)LOT42"

    @Test fun passportCarrierClassifiesAsItsOwnKind() {
        val p = ScanPayloadParser.parse(passportCarrier)
        assertThat(p).isInstanceOf(ScanPayload.DigitalProductPassport::class.java)
        assertThat(p.kindLabel).isEqualTo("Product Passport")
        val dpp = (p as ScanPayload.DigitalProductPassport).payload
        assertThat(dpp.gtin).isEqualTo("04012345678901")
        assertThat(dpp.batchLot).isEqualTo("LOT42")
        assertThat(dpp.serial).isEqualTo("SN-0099")
        assertThat(dpp.expiry).isEqualTo("2026-12-31")
        assertThat(dpp.production).isEqualTo("2026-01-01")
        assertThat(dpp.originNumeric).isEqualTo("276")
        assertThat(dpp.originDescription).isEqualTo("Germany (DEU)")
        assertThat(dpp.raw).isEqualTo(passportCarrier)
    }

    @Test fun passportRowsSayNothingWasFetched() {
        val dpp = DigitalProductPassportParser.parse(GS1Parser.parse(passportCarrier)!!)
        val rows = dpp.labelledFields().associate { it.label to it.value }
        assertThat(rows["Format"]).isEqualTo("GS1 Digital Product Passport carrier")
        assertThat(rows["Product (GTIN)"]).isEqualTo("04012345678901")
        assertThat(rows["Batch / lot"]).isEqualTo("LOT42")
        assertThat(rows["Serial"]).isEqualTo("SN-0099")
        assertThat(rows["Expiry"]).isEqualTo("2026-12-31")
        assertThat(rows["Produced"]).isEqualTo("2026-01-01")
        assertThat(rows["Country of origin"]).isEqualTo("Germany (DEU)")
        assertThat(rows["Read from"]).isEqualTo("the code itself — nothing was fetched")
    }

    @Test fun plainGS1CarrierStaysAProductCode() {
        val p = ScanPayloadParser.parse(plainCarrier)
        assertThat(p).isInstanceOf(ScanPayload.GS1::class.java)
        assertThat(p).isNotInstanceOf(ScanPayload.DigitalProductPassport::class.java)
    }

    @Test fun recognisesTheExplicitDPPMarker() {
        val raw = "https://id.example.eu/dpp/01/04012345678901"
        val payload = GS1Parser.parse(raw)!!
        assertThat(DigitalProductPassportParser.looksLikePassport(payload)).isTrue()
        val dpp = DigitalProductPassportParser.parse(payload)
        assertThat(dpp.resolverHost).isEqualTo("id.example.eu")
        assertThat(dpp.gtin).isEqualTo("04012345678901")
    }

    @Test fun shipToPostalCodeIsNotACountryOfOrigin() {
        // AI 421 is "ship to — deliver to postal code with ISO country
        // code": where the parcel goes, not where the product is from.
        val shipTo = "(01)04012345678901(17)261231(10)LOT42(421)27610115"
        assertThat(ScanPayloadParser.parse(shipTo)).isInstanceOf(ScanPayload.GS1::class.java)
        val payload = GS1Parser.parse(shipTo)!!
        assertThat(DigitalProductPassportParser.looksLikePassport(payload)).isFalse()
        val dpp = DigitalProductPassportParser.parse(payload)
        assertThat(dpp.originNumeric).isNull()
        assertThat(dpp.labelledFields().map { it.label }).doesNotContain("Country of origin")

        // With both, the origin is AI 422's, never the destination's.
        val both = DigitalProductPassportParser.parse(
            GS1Parser.parse("(01)04012345678901(17)261231(10)LOT42(421)25075001(422)276")!!
        )
        assertThat(both.originDescription).isEqualTo("Germany (DEU)")
    }

    @Test fun unknownCountryNumericDecodesToNothing() {
        val payload = GS1Parser.parse("(01)04012345678901(17)261231(10)LOT42(422)999")!!
        val dpp = DigitalProductPassportParser.parse(payload)
        assertThat(dpp.originNumeric).isEqualTo("999")
        assertThat(dpp.originDescription).isNull()
        assertThat(dpp.labelledFields().map { it.label }).doesNotContain("Country of origin")
    }

    @Test fun resolverHostIsShownButNeverAFetchTarget() {
        val payload = GS1Parser.parse("(01)04012345678901(17)261231(10)LOT42(422)276")!!
        assertThat(DigitalProductPassportParser.parse(payload).resolverHost).isNull()
    }

    @Test fun countryOfOriginIsTheIsoAssignment() {
        // 528 is the Netherlands; the table this mirrors once printed it as
        // Morocco, along with three other neighbouring-code mix-ups.
        fun origin(numeric: String) = DigitalProductPassportParser.parse(
            GS1Parser.parse("(01)04012345678901(17)261231(10)LOT42(422)$numeric")!!
        ).originDescription
        assertThat(origin("528")).isEqualTo("Netherlands (NLD)")
        assertThat(origin("504")).isEqualTo("Morocco (MAR)")
        assertThat(origin("470")).isEqualTo("Malta (MLT)")
        assertThat(origin("466")).isEqualTo("Mali (MLI)")
        assertThat(origin("600")).isEqualTo("Paraguay (PRY)")
        assertThat(origin("710")).isEqualTo("South Africa (ZAF)")
        assertThat(origin("324")).isEqualTo("Guinea (GIN)")
        assertThat(origin("624")).isEqualTo("Guinea-Bissau (GNB)")
        assertThat(origin("276")).isEqualTo("Germany (DEU)")
    }

    @Test fun digitalLinkCarrierNamesItsResolverHost() {
        val raw = "https://id.gs1.org/01/04012345678901/10/LOT42/21/SER1?17=261231&11=260101&422=276"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.DigitalProductPassport::class.java)
        val dpp = (p as ScanPayload.DigitalProductPassport).payload
        assertThat(dpp.resolverHost).isEqualTo("id.gs1.org")
        assertThat(dpp.serial).isEqualTo("SER1")
        assertThat(dpp.originDescription).isEqualTo("Germany (DEU)")
        assertThat(dpp.labelledFields().associate { it.label to it.value }["Passport hosted at"])
            .isEqualTo("id.gs1.org")
    }

    // ---- GS1 country AIs 422–426 (the iOS `CredentialAndPassportSpecTests`) --

    @Test fun processingCountriesAreLabelledByTheirAI() {
        val p = ScanPayloadParser.parse(
            "(01)04012345678901(10)LOT42(17)261231(422)724(423)724250(424)276(425)276"
        )
        assertThat(p).isInstanceOf(ScanPayload.DigitalProductPassport::class.java)
        val dpp = (p as ScanPayload.DigitalProductPassport).payload
        assertThat(dpp.labelledFields()).containsExactly(
            LabelledField("Format", "GS1 Digital Product Passport carrier"),
            LabelledField("Product (GTIN)", "04012345678901"),
            LabelledField("Batch / lot", "LOT42"),
            LabelledField("Expiry", "2026-12-31"),
            LabelledField("Country of origin", "Spain (ESP)"),
            LabelledField("Country of initial processing", "Spain (ESP), France (FRA)"),
            LabelledField("Country of processing", "Germany (DEU)"),
            LabelledField("Country of disassembly", "Germany (DEU)"),
            LabelledField("Read from", "the code itself — nothing was fetched"),
        ).inOrder()
    }

    @Test fun fullProcessChainCountryMakesAPassport() {
        // GS1 uses 426 *instead of* 422 when one country covers it all.
        val p = ScanPayloadParser.parse("(01)04012345678901(10)LOT42(17)261231(426)380")
        assertThat(p).isInstanceOf(ScanPayload.DigitalProductPassport::class.java)
        val rows = (p as ScanPayload.DigitalProductPassport).payload.labelledFields()
            .associate { it.label to it.value }
        assertThat(rows["Country covering full process chain"]).isEqualTo("Italy (ITA)")
        assertThat(rows).doesNotContainKey("Country of origin")
    }

    @Test fun countryAIsSurviveDigitalLinkAndFNC1() {
        val link = ScanPayloadParser.parse("https://id.gs1.org/01/04012345678901/10/LOT42?17=261231&426=380")
        assertThat(link).isInstanceOf(ScanPayload.DigitalProductPassport::class.java)
        assertThat(
            (link as ScanPayload.DigitalProductPassport).payload.labelledFields()
                .associate { it.label to it.value }["Country covering full process chain"]
        ).isEqualTo("Italy (ITA)")

        val fnc1 = ScanPayloadParser.parse("0104012345678901" + "10LOT42\u001D" + "17261231" + "426380")
        assertThat(fnc1).isInstanceOf(ScanPayload.DigitalProductPassport::class.java)
        val dpp = (fnc1 as ScanPayload.DigitalProductPassport).payload
        assertThat(dpp.batchLot).isEqualTo("LOT42")
        assertThat(dpp.fullProcessNumeric).isEqualTo("380")
        // The plain GS1 view names the AI too.
        assertThat(GS1Parser.parse("(01)04012345678901(426)380")!!.labelledFields().last())
            .isEqualTo(LabelledField("Country covering full process chain (426)", "380"))
    }

    @Test fun countryListsNeedEveryCountryNamed() {
        fun dpp(tail: String) = DigitalProductPassportParser.parse(
            GS1Parser.parse("(01)04012345678901(17)261231(10)LOT42$tail")!!
        )
        // One unknown code in a list would make the rest a false answer.
        assertThat(dpp("(423)276999").labelledFields().map { it.label })
            .doesNotContain("Country of initial processing")
        assertThat(dpp("(423)2762").initialProcessingNumeric).isNull()
        assertThat(dpp("(425)276276276276276276").disassemblyNumeric).isNull()
        assertThat(dpp("(422)27").originNumeric).isNull()
        assertThat(dpp("(422)27A").originNumeric).isNull()
        assertThat(ISOCountry.names("276250")).isEqualTo("Germany (DEU), France (FRA)")
        assertThat(ISOCountry.names("2762")).isNull()
    }

    // ---- The iOS suite's fixtures (ScanTests: Digital Product Passport) --

    @Test fun iosGS1PassportBecomesItsOwnKind() {
        // GTIN + origin + a date + a unit identifier is what separates a
        // passport from an ordinary product code.
        val passport = "(01)04012345678901(17)260101(11)250601(10)LOT42" + "(21)SER0001(422)276"
        val p = ScanPayloadParser.parse(passport)
        assertThat(p).isInstanceOf(ScanPayload.DigitalProductPassport::class.java)
        val dpp = (p as ScanPayload.DigitalProductPassport).payload
        assertThat(dpp.gtin).isEqualTo("04012345678901")
        assertThat(dpp.labelledFields()).isNotEmpty()
    }

    @Test fun iosPlainGS1StaysAProductCode() {
        assertThat(ScanPayloadParser.parse("(01)09506000134352(17)201225(10)ABC123"))
            .isInstanceOf(ScanPayload.GS1::class.java)
    }

    // ---- Review fixes: the iOS `CredentialAndPassportSpecTests` vectors --

    private fun rowsOf(dpp: DigitalProductPassportPayload) = dpp.labelledFields().associate { it.label to it.value }

    @Test fun dppInsideTheTextIsNoPassportMarker() {
        for (raw in listOf(
            "(01)09506000134352(10)ADPPX",
            "0109506000134352" + "10ABC\u001D" + "21DPP9",
            "https://developer.example.org/docs/01/12345678?unit=dppx",
            "https://www.dppnews.example/2024/01/20240115/story",
            "https://id.gs1.org/01/09506000134352/10/dpp",
        )) {
            assertWithMessage(raw).that(ScanPayloadParser.parse(raw)).isInstanceOf(ScanPayload.GS1::class.java)
        }
        for (raw in listOf(
            "https://id.example.eu/dpp/01/04012345678901",
            "https://id.example.eu/01/04012345678901?dpp=1",
            "https://id.example.eu/DPP/01/04012345678901",
        )) {
            val p = ScanPayloadParser.parse(raw)
            assertWithMessage(raw).that(p).isInstanceOf(ScanPayload.DigitalProductPassport::class.java)
            val dpp = (p as ScanPayload.DigitalProductPassport).payload
            assertThat(dpp.gtin).isEqualTo("04012345678901")
            assertThat(dpp.resolverHost).isEqualTo("id.example.eu")
        }
    }

    @Test fun passportKeepsEveryOtherElementOfTheCarrier() {
        // An EU beef-traceability label: net weight, packaging and
        // best-before dates and an approval number besides the passport AIs.
        val beef = "(01)09506000134352(3103)000750(13)261101(15)261210(17)261130(10)LOT42(422)276(7030)276DE-EZ-123"
        val p = ScanPayloadParser.parse(beef)
        assertThat(p).isInstanceOf(ScanPayload.DigitalProductPassport::class.java)
        assertThat((p as ScanPayload.DigitalProductPassport).payload.labelledFields()).containsExactly(
            LabelledField("Format", "GS1 Digital Product Passport carrier"),
            LabelledField("Product (GTIN)", "09506000134352"),
            LabelledField("Batch / lot", "LOT42"),
            LabelledField("Expiry", "2026-11-30"),
            LabelledField("Country of origin", "Germany (DEU)"),
            LabelledField("AI 3103 (3103)", "000750"),
            LabelledField("Packaging date (13)", "2026-11-01"),
            LabelledField("Best before (15)", "2026-12-10"),
            LabelledField("AI 7030 (7030)", "276DE-EZ-123"),
            LabelledField("Read from", "the code itself — nothing was fetched"),
        ).inOrder()
        val link = ScanPayloadParser.parse("https://id.gs1.org/01/09506000134352/10/ABC123?15=261210&422=276&17=261130")
        assertThat(link).isInstanceOf(ScanPayload.DigitalProductPassport::class.java)
        assertThat((link as ScanPayload.DigitalProductPassport).payload.labelledFields()).containsExactly(
            LabelledField("Format", "GS1 Digital Product Passport carrier"),
            LabelledField("Product (GTIN)", "09506000134352"),
            LabelledField("Batch / lot", "ABC123"),
            LabelledField("Expiry", "2026-11-30"),
            LabelledField("Country of origin", "Germany (DEU)"),
            LabelledField("Passport hosted at", "id.gs1.org"),
            LabelledField("Best before (15)", "2026-12-10"),
            LabelledField("Read from", "the code itself — nothing was fetched"),
        ).inOrder()
    }

    @Test fun uppercaseDigitalLinkIsReadAsALink() {
        val p = ScanPayloadParser.parse("HTTPS://ID.GS1.ORG/01/04012345678901/21/SN1?17=261231&422=276")
        assertThat(p).isInstanceOf(ScanPayload.DigitalProductPassport::class.java)
        val dpp = (p as ScanPayload.DigitalProductPassport).payload
        assertThat(dpp.gtin).isEqualTo("04012345678901")
        assertThat(dpp.serial).isEqualTo("SN1")
        assertThat(dpp.expiry).isEqualTo("2026-12-31")
        assertThat(dpp.originNumeric).isEqualTo("276")
        assertThat(dpp.resolverHost).isEqualTo("id.gs1.org")

        val mixed = ScanPayloadParser.parse("Https://id.gs1.org/01/09506000134352?17=261231")
        assertThat(mixed).isInstanceOf(ScanPayload.GS1::class.java)
        val gs1 = (mixed as ScanPayload.GS1).payload
        assertThat(gs1.form).isEqualTo(GS1Payload.Form.DIGITAL_LINK)
        assertThat(gs1.elements.map { it.ai }).containsExactly("01", "17").inOrder()

        // A `dpp` host is no marker, so without an origin this stays a
        // product code — read as a link, not as the AI "HT".
        val host = ScanPayloadParser.parse("HTTPS://DPP.EXAMPLE.COM/01/04012345678901/21/SN1")
        assertThat(host).isInstanceOf(ScanPayload.GS1::class.java)
        val link = (host as ScanPayload.GS1).payload
        assertThat(link.form).isEqualTo(GS1Payload.Form.DIGITAL_LINK)
        assertThat(link.gtin).isEqualTo("04012345678901")
        assertThat(link.serial).isEqualTo("SN1")
    }

    @Test fun passportHostFollowsTheCredentialRule() {
        val idn = ScanPayloadParser.parse("https://dpp.bücher.de/01/04012345678901/21/SN1?17=261231&422=276")
        assertThat(idn).isInstanceOf(ScanPayload.DigitalProductPassport::class.java)
        assertThat((idn as ScanPayload.DigitalProductPassport).payload.resolverHost).isEqualTo("dpp.bücher.de")
        // The resolver, both ways round.
        for (raw in listOf(
            "https://evil.example\\@id.gs1.org/01/09506000134352/10/L1?17=261130&422=276",
            "https://id.gs1.org\\@evil.example/01/09506000134352/10/L1?17=261130&422=276",
        )) {
            val p = ScanPayloadParser.parse(raw)
            assertWithMessage(raw).that(p).isInstanceOf(ScanPayload.DigitalProductPassport::class.java)
            assertWithMessage(raw).that((p as ScanPayload.DigitalProductPassport).payload.labelledFields()).containsExactly(
                LabelledField("Format", "GS1 Digital Product Passport carrier"),
                LabelledField("Product (GTIN)", "09506000134352"),
                LabelledField("Batch / lot", "L1"),
                LabelledField("Expiry", "2026-11-30"),
                LabelledField("Country of origin", "Germany (DEU)"),
                LabelledField("Passport hosted at", ShownHost.UNUSUAL),
                LabelledField("Read from", "the code itself — nothing was fetched"),
            ).inOrder()
        }
    }

    @Test fun countryTableIsTheWholeIsoList() {
        assertThat(ISOCountry.name("032")).isEqualTo("Argentina (ARG)")
        assertThat(ISOCountry.name("608")).isEqualTo("Philippines (PHL)")
        assertThat(ISOCountry.name("818")).isEqualTo("Egypt (EGY)")
        assertThat(ISOCountry.name("158")).isEqualTo("Taiwan (TWN)")
        // The codes the first table mixed up, each where ISO puts it.
        for ((code, name) in listOf(
            "528" to "Netherlands (NLD)", "504" to "Morocco (MAR)", "470" to "Malta (MLT)",
            "466" to "Mali (MLI)", "600" to "Paraguay (PRY)", "710" to "South Africa (ZAF)",
            "324" to "Guinea (GIN)", "624" to "Guinea-Bissau (GNB)",
        )) {
            assertWithMessage(code).that(ISOCountry.name(code)).isEqualTo(name)
        }
        assertThat(ISOCountry.name("384")).isEqualTo("Côte d’Ivoire (CIV)")
        assertThat(ISOCountry.name("156")).isEqualTo("China (CHN)")
        assertThat(ISOCountry.name("344")).isEqualTo("Hong Kong SAR China (HKG)")
        // All 249 current codes; withdrawn ones (East Germany, the two
        // Yemens, old Ethiopia and Sudan) and ICU's 983 for Kosovo are not.
        val named = (0..999).map { it.toString().padStart(3, '0') }.filter { ISOCountry.name(it) != null }
        assertThat(named).hasSize(249)
        for (withdrawn in listOf("230", "278", "280", "720", "736", "886", "983", "999")) {
            assertWithMessage(withdrawn).that(ISOCountry.name(withdrawn)).isNull()
        }
        val p = ScanPayloadParser.parse("(01)04012345678901(17)261231(10)LOT42(422)032(423)608818")
        assertThat(p).isInstanceOf(ScanPayload.DigitalProductPassport::class.java)
        val dpp = (p as ScanPayload.DigitalProductPassport).payload
        assertThat(dpp.originDescription).isEqualTo("Argentina (ARG)")
        assertThat(rowsOf(dpp)["Country of initial processing"]).isEqualTo("Philippines (PHL), Egypt (EGY)")
    }
}
