package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mirrors the Pass-1 + Pass-2 iOS test additions one-for-one. Covers:
 *   • Magnet URIs
 *   • Rich URL flavours (WhatsApp / Telegram / pkpass / AppStore /
 *     PlayStore / YouTube / Spotify) and Maps URLs that re-classify to .Geo
 *   • New crypto chains (XRP / Stellar / Cosmos / LNURL / bare addresses)
 *   • vCard 4.0
 *   • GS1 (parens / Digital Link / FNC1)
 *   • IATA boarding pass
 *   • AAMVA driver's licence
 */
@RunWith(RobolectricTestRunner::class)
class Pass1Pass2PortTest {

    // ---- Magnet ---------------------------------------------------------

    @Test fun parsesMagnetURI() {
        val raw = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a" +
            "&dn=ubuntu-22.04.iso&xl=4294967296" +
            "&tr=udp%3A%2F%2Ftracker.openbittorrent.com%3A80"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Magnet::class.java)
        val m = (p as ScanPayload.Magnet).payload
        assertThat(m.infoHash).isEqualTo("c12fe1c06bba254a9dc9f519b335aa7c1367a88a")
        assertThat(m.displayName).isEqualTo("ubuntu-22.04.iso")
        assertThat(m.exactLength).isEqualTo(4_294_967_296L)
        assertThat(m.trackers).containsExactly("udp://tracker.openbittorrent.com:80")
    }

    @Test fun rejectsMagnetWithoutHashOrName() {
        val raw = "magnet:?tr=http://example.com/announce"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isNotInstanceOf(ScanPayload.Magnet::class.java)
    }

    // ---- Rich URLs ------------------------------------------------------

    @Test fun recognisesWhatsAppClickToChat() {
        val raw = "https://wa.me/12025551212?text=Hello%20there"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.RichUrl::class.java)
        val r = (p as ScanPayload.RichUrl).payload
        assertThat(r.kind).isEqualTo(RichURLPayload.Kind.WHATS_APP)
        val dict = r.fields.associate { it.label to it.value }
        assertThat(dict["Phone"]).isEqualTo("12025551212")
        assertThat(dict["Message"]).isEqualTo("Hello there")
    }

    @Test fun recognisesTelegramLink() {
        val raw = "https://t.me/nettrash"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.RichUrl::class.java)
        val r = (p as ScanPayload.RichUrl).payload
        assertThat(r.kind).isEqualTo(RichURLPayload.Kind.TELEGRAM)
        val dict = r.fields.associate { it.label to it.value }
        assertThat(dict["Target"]).isEqualTo("@nettrash")
    }

    @Test fun recognisesPkpassURL() {
        val raw = "https://example.com/passes/boarding.pkpass"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.RichUrl::class.java)
        val r = (p as ScanPayload.RichUrl).payload
        assertThat(r.kind).isEqualTo(RichURLPayload.Kind.APPLE_WALLET)
        val dict = r.fields.associate { it.label to it.value }
        assertThat(dict["Pass file"]).isEqualTo("boarding.pkpass")
    }

    @Test fun recognisesAppStoreLink() {
        val raw = "https://apps.apple.com/us/app/nettrash-scan/id6763932723"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.RichUrl::class.java)
        val r = (p as ScanPayload.RichUrl).payload
        assertThat(r.kind).isEqualTo(RichURLPayload.Kind.APP_STORE)
        val dict = r.fields.associate { it.label to it.value }
        assertThat(dict["App ID"]).isEqualTo("6763932723")
    }

    @Test fun recognisesPlayStoreLink() {
        val raw = "https://play.google.com/store/apps/details?id=me.nettrash.scan"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.RichUrl::class.java)
        val r = (p as ScanPayload.RichUrl).payload
        assertThat(r.kind).isEqualTo(RichURLPayload.Kind.PLAY_STORE)
        val dict = r.fields.associate { it.label to it.value }
        assertThat(dict["Package"]).isEqualTo("me.nettrash.scan")
    }

    @Test fun recognisesYouTubeShortLink() {
        val raw = "https://youtu.be/dQw4w9WgXcQ"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.RichUrl::class.java)
        val r = (p as ScanPayload.RichUrl).payload
        assertThat(r.kind).isEqualTo(RichURLPayload.Kind.YOUTUBE)
        val dict = r.fields.associate { it.label to it.value }
        assertThat(dict["Video"]).isEqualTo("dQw4w9WgXcQ")
    }

    @Test fun recognisesYouTubeWatchLink() {
        val raw = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.RichUrl::class.java)
        val r = (p as ScanPayload.RichUrl).payload
        assertThat(r.kind).isEqualTo(RichURLPayload.Kind.YOUTUBE)
        val dict = r.fields.associate { it.label to it.value }
        assertThat(dict["Video"]).isEqualTo("dQw4w9WgXcQ")
    }

    @Test fun recognisesSpotifyTrackLink() {
        val raw = "https://open.spotify.com/track/6rqhFgbbKwnb9MLmUQDhG6"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.RichUrl::class.java)
        val r = (p as ScanPayload.RichUrl).payload
        assertThat(r.kind).isEqualTo(RichURLPayload.Kind.SPOTIFY)
        val dict = r.fields.associate { it.label to it.value }
        assertThat(dict["Kind"]).isEqualTo("Track")
        assertThat(dict["ID"]).isEqualTo("6rqhFgbbKwnb9MLmUQDhG6")
    }

    @Test fun googleMapsURLRoundsToGeo() {
        val raw = "https://www.google.com/maps/place/Apple+Park/@37.3349,-122.009,17z"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Geo::class.java)
        val g = p as ScanPayload.Geo
        assertThat(g.latitude).isWithin(0.001).of(37.3349)
        assertThat(g.longitude).isWithin(0.001).of(-122.009)
    }

    @Test fun appleMapsURLRoundsToGeo() {
        val raw = "https://maps.apple.com/?ll=37.3349,-122.009&q=Apple+Park"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Geo::class.java)
        val g = p as ScanPayload.Geo
        assertThat(g.latitude).isWithin(0.001).of(37.3349)
        assertThat(g.longitude).isWithin(0.001).of(-122.009)
        // Note: Android's URLDecoder converts `+` to space (matches Java's
        // application/x-www-form-urlencoded behaviour), so "Apple Park"
        // is what we get out — same as the parsesGeo test above.
        assertThat(g.query).isEqualTo("Apple Park")
    }

    // ---- New crypto chains ---------------------------------------------

    @Test fun parsesXRPURI() {
        val raw = "xrpl:r9cZA1mLK5R5Am25ArfXFmqgNwjZgnfk59"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Crypto::class.java)
        assertThat((p as ScanPayload.Crypto).payload.chain)
            .isEqualTo(CryptoPayload.Chain.RIPPLE)
    }

    @Test fun parsesStellarURI() {
        val raw = "web+stellar:tx?xdr=AAAAAA"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Crypto::class.java)
        assertThat((p as ScanPayload.Crypto).payload.chain)
            .isEqualTo(CryptoPayload.Chain.STELLAR)
    }

    @Test fun parsesCosmosURI() {
        val raw = "cosmos:cosmos1abc?amount=1000000uatom"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Crypto::class.java)
        assertThat((p as ScanPayload.Crypto).payload.chain)
            .isEqualTo(CryptoPayload.Chain.COSMOS)
    }

    // ---- Bare addresses ------------------------------------------------

    @Test fun recognisesBareBitcoinAddress() {
        val raw = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Crypto::class.java)
        val c = (p as ScanPayload.Crypto).payload
        assertThat(c.chain).isEqualTo(CryptoPayload.Chain.BITCOIN)
        assertThat(c.address).isEqualTo(raw)
    }

    @Test fun recognisesBareEthereumAddress() {
        val raw = "0x89205A3A3b2A69De6Dbf7f01ED13B2108B2c43e7"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Crypto::class.java)
        assertThat((p as ScanPayload.Crypto).payload.chain)
            .isEqualTo(CryptoPayload.Chain.ETHEREUM)
    }

    @Test fun recognisesLNURL() {
        val raw = "LNURL1DP68GURN8GHJ7AMPD3KX2AR0VEEKZAR0WD5XJTNRDAKJ7TNHV4KX2EPCV4ENXAR"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Crypto::class.java)
        assertThat((p as ScanPayload.Crypto).payload.chain)
            .isEqualTo(CryptoPayload.Chain.LNURL)
    }

    @Test fun recognisesBareLightningInvoice() {
        val raw = "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Crypto::class.java)
        assertThat((p as ScanPayload.Crypto).payload.chain)
            .isEqualTo(CryptoPayload.Chain.LIGHTNING)
    }

    // ---- vCard 4.0 -----------------------------------------------------

    @Test fun parsesVCard4() {
        val v = """
            BEGIN:VCARD
            VERSION:4.0
            FN:Jane Doe
            N:Doe;Jane;;;
            TEL;TYPE=cell:+15551234567
            EMAIL;TYPE=work:jane@example.com
            END:VCARD
        """.trimIndent()
        val p = ScanPayloadParser.parse(v)
        assertThat(p).isInstanceOf(ScanPayload.Contact::class.java)
        val c = (p as ScanPayload.Contact).payload
        assertThat(c.fullName).isEqualTo("Jane Doe")
        assertThat(c.phones).containsExactly("+15551234567")
        assertThat(c.emails).containsExactly("jane@example.com")
    }

    // ---- GS1 -----------------------------------------------------------

    @Test fun parsesGS1ParensForm() {
        val raw = "(01)09506000134352(17)201225(10)ABC123(21)SN-001"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.GS1::class.java)
        val g = (p as ScanPayload.GS1).payload
        assertThat(g.form).isEqualTo(GS1Payload.Form.PARENS)
        assertThat(g.gtin).isEqualTo("09506000134352")
        assertThat(g.expiry).isEqualTo("201225")
        assertThat(g.batchLot).isEqualTo("ABC123")
        assertThat(g.serial).isEqualTo("SN-001")
        val labels = g.labelledFields().associate { it.label to it.value }
        assertThat(labels["Expiry (17)"]).isEqualTo("2020-12-25")
    }

    @Test fun parsesGS1DigitalLink() {
        val raw = "https://id.gs1.org/01/09506000134352/10/ABC123"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.GS1::class.java)
        val g = (p as ScanPayload.GS1).payload
        assertThat(g.form).isEqualTo(GS1Payload.Form.DIGITAL_LINK)
        assertThat(g.gtin).isEqualTo("09506000134352")
        assertThat(g.batchLot).isEqualTo("ABC123")
    }

    @Test fun parsesGS1FNC1Form() {
        // FNC1 is ASCII GS (0x1D).
        val gs = ''
        val raw = "0109506000134352${gs}10ABC123${gs}21SN-001"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.GS1::class.java)
        val g = (p as ScanPayload.GS1).payload
        assertThat(g.gtin).isEqualTo("09506000134352")
        assertThat(g.batchLot).isEqualTo("ABC123")
        assertThat(g.serial).isEqualTo("SN-001")
    }

    // ---- IATA Boarding Pass --------------------------------------------

    @Test fun parsesIATABoardingPass() {
        val raw = "M1NETTRASH/IVAN       EABC123 LHRJFKBA  0175020M013D0028 100"
        assertThat(raw.length).isEqualTo(60)
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.BoardingPass::class.java)
        val bp = (p as ScanPayload.BoardingPass).payload
        assertThat(bp.formatCode).isEqualTo('M')
        assertThat(bp.numberOfLegs).isEqualTo(1)
        assertThat(bp.passengerName).isEqualTo("NETTRASH/IVAN")
        assertThat(bp.electronicTicket).isTrue()
        assertThat(bp.legs).hasSize(1)
        val leg = bp.legs[0]
        assertThat(leg.pnr).isEqualTo("ABC123")
        assertThat(leg.from).isEqualTo("LHR")
        assertThat(leg.to).isEqualTo("JFK")
        assertThat(leg.carrier).isEqualTo("BA")
        assertThat(leg.dateJulian).isEqualTo(20)
    }

    @Test fun rejectsNonBoardingPassPayload() {
        val raw = "X1NETTRASH/IVAN       EABC123 LHRJFKBA  0175020M013D0028 100"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isNotInstanceOf(ScanPayload.BoardingPass::class.java)
    }

    // ---- AAMVA driver's licence ----------------------------------------

    @Test fun parsesAAMVADriverLicense() {
        val raw = """
            @
            ANSI 636026100002DL00410288ZV03190008DLDAQABC1234567
            DCSDOE
            DACJOHN
            DADM
            DBA12312030
            DBB04151985
            DBC1
            DAG123 MAIN ST
            DAICOLUMBIA
            DAJSC
            DAK29201
        """.trimIndent()
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.DrivingLicense::class.java)
        val dl = (p as ScanPayload.DrivingLicense).payload
        assertThat(dl.issuerIIN).isEqualTo("636026")
        assertThat(dl.issuerName).isEqualTo("South Carolina")
        assertThat(dl.licenseNumber).isEqualTo("ABC1234567")
        assertThat(dl.firstName).isEqualTo("JOHN")
        assertThat(dl.middleName).isEqualTo("M")
        assertThat(dl.lastName).isEqualTo("DOE")
        assertThat(dl.sex).isEqualTo("Male")
        assertThat(dl.city).isEqualTo("COLUMBIA")
        assertThat(dl.state).isEqualTo("SC")
        assertThat(dl.postalCode).isEqualTo("29201")
        assertThat(dl.dateOfBirth).isNotNull()
        assertThat(dl.expiry).isNotNull()
    }

    @Test fun rejectsNonAAMVAPayload() {
        val raw = "@\nNotAAMVAStuff"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isNotInstanceOf(ScanPayload.DrivingLicense::class.java)
    }
}
