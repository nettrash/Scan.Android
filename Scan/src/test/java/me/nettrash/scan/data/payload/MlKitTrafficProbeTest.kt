package me.nettrash.scan.data.payload

import android.os.Looper
import com.google.common.truth.Truth.assertWithMessage
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Not a CI test: which Google endpoints merely building an ML Kit translator
 * contacts, so the Translate disclosure — `downloadPrompt`, the Settings
 * footer and the privacy note in `TranslationKit.kt` — is re-checked whenever
 * the ML Kit version changes. Every connection goes to a local proxy that
 * writes down the request line and hangs up, so nothing leaves the machine
 * (and the settings fetch, which needs the installation's token, never
 * happens). Run it with
 * `SCAN_MLKIT_PROBE=1 ./gradlew :Scan:testDebugUnitTest --tests '*MlKitTrafficProbeTest*'`.
 */
@RunWith(RobolectricTestRunner::class)
class MlKitTrafficProbeTest {

    @Test fun buildingATranslatorContactsOnlyTheDisclosedEndpoints() {
        assumeTrue("set SCAN_MLKIT_PROBE=1 to run the ML Kit traffic probe", System.getenv("SCAN_MLKIT_PROBE") == "1")
        val seen = CopyOnWriteArrayList<String>()
        val loopback = InetAddress.getLoopbackAddress()
        val server = ServerSocket(0, 50, loopback)
        thread(isDaemon = true) {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                socket.use { runCatching { it.getInputStream().bufferedReader().readLine()?.let(seen::add) } }
            }
        }
        val previous = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> =
                listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(loopback, server.localPort)))

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        })
        try {
            MlKitContext.initializeIfNeeded(RuntimeEnvironment.getApplication())
            val translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage("de").setTargetLanguage("en").build(),
            )
            val deadline = System.currentTimeMillis() + 20_000
            while (seen.isEmpty() && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(200)
            }
            Thread.sleep(1_500)
            translator.close()
        } finally {
            ProxySelector.setDefault(previous)
            server.close()
        }
        val hosts = seen.mapNotNull(::hostOf).toSet()
        println("ML Kit translator traffic: $seen")
        assertWithMessage("endpoints the disclosure does not cover: $hosts").that(hosts - disclosedHosts).isEmpty()
    }

    /** The host of a proxy request line: `CONNECT host:443 HTTP/1.1` or `GET http://host/… HTTP/1.1`. */
    private fun hostOf(requestLine: String): String? {
        val target = requestLine.split(' ').getOrNull(1) ?: return null
        return if ("://" in target) runCatching { URI(target).host }.getOrNull() else target.substringBefore(':')
    }

    private val disclosedHosts = setOf("firebaseinstallations.googleapis.com", "firebaseremoteconfig.googleapis.com")
}
