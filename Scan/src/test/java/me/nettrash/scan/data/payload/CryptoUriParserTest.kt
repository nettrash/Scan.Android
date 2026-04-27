package me.nettrash.scan.data.payload

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Mirrors the iOS Bitcoin / Ethereum-with-chainID / Lightning-invoice tests. */
@RunWith(RobolectricTestRunner::class)
class CryptoUriParserTest {

    @Test fun parsesBitcoinURI() {
        val raw = "bitcoin:1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2" +
            "?amount=0.0001&label=Donation&message=Thanks"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Crypto::class.java)
        val c = (p as ScanPayload.Crypto).payload
        assertThat(c.chain).isEqualTo(CryptoPayload.Chain.BITCOIN)
        assertThat(c.address).isEqualTo("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2")
        assertThat(c.amount).isEqualTo("0.0001")
        assertThat(c.label).isEqualTo("Donation")
        assertThat(c.message).isEqualTo("Thanks")
    }

    @Test fun parsesEthereumURIWithChainID() {
        val raw = "ethereum:0x89205A3A3b2A69De6Dbf7f01ED13B2108B2c43e7@137?value=1e18"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Crypto::class.java)
        val c = (p as ScanPayload.Crypto).payload
        assertThat(c.chain).isEqualTo(CryptoPayload.Chain.ETHEREUM)
        assertThat(c.address).isEqualTo("0x89205A3A3b2A69De6Dbf7f01ED13B2108B2c43e7")
        assertThat(c.chainId).isEqualTo("137")
        assertThat(c.amount).isEqualTo("1e18")
    }

    @Test fun parsesLightningInvoice() {
        val raw = "lightning:lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl"
        val p = ScanPayloadParser.parse(raw)
        assertThat(p).isInstanceOf(ScanPayload.Crypto::class.java)
        val c = (p as ScanPayload.Crypto).payload
        assertThat(c.chain).isEqualTo(CryptoPayload.Chain.LIGHTNING)
        assertThat(c.address).startsWith("lnbc")
        assertThat(c.amount).isNull()
    }
}
