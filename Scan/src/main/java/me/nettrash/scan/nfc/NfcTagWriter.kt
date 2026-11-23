package me.nettrash.scan.nfc

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.nettrash.scan.data.payload.ScanPayload
import me.nettrash.scan.data.payload.ScanPayloadParser
import me.nettrash.scan.scanner.Symbology
import me.nettrash.scan.util.Haptics
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * What can go on a tag. Mirrors `NFCTagPayloadKind` in
 * `Scan/NFCTagWriter.swift` — same four cases, same order, same labels.
 *
 * iOS's `symbol` (an SF Symbol name) has no Android counterpart; the UI
 * picks a Material icon instead.
 */
enum class NfcTagPayloadKind(val label: String) {
    Text("Text"),
    Url("URL"),
    Wifi("Wi-Fi"),
    Contact("Contact");

    companion object {
        /**
         * What content starts with, in any case, to go on a tag as an RTD URI
         * record — iOS `linkPrefixes`: the links [ScanPayloadParser] reads
         * (web, phone, email, SMS and map), so a tap can open, dial, compose
         * or show the place on a phone that acts on that kind of link (an
         * iPhone's background tag reading takes web, `tel:`, `mailto:` and
         * `sms:` ones, not `smsto:` or `geo:`). Only `tel:` and `mailto:` have
         * an NFC Forum prefix code besides the web ones; the rest are written
         * whole.
         */
        val LINK_PREFIXES: List<String> = listOf("http://", "https://", "tel:", "mailto:", "sms:", "smsto:", "geo:")

        /**
         * The kind a code's content implies — iOS `inferred(from:)`. The
         * generator hands over the same string the code encodes, and
         * `CodeComposer` output announces itself (`WIFI:` / `BEGIN:VCARD`), so
         * the content alone picks the record: a Wi-Fi string pasted into the
         * Text form becomes a Wi-Fi tag, exactly as its QR is a Wi-Fi code,
         * and a `tel:` one a link tag.
         */
        fun inferred(value: String): NfcTagPayloadKind {
            val lower = NfcPayloadCodec.trimWhitespace(value).lowercase(Locale.ROOT)
            return when {
                lower.startsWith("wifi:") -> Wifi
                lower.startsWith("begin:vcard") -> Contact
                LINK_PREFIXES.any { lower.startsWith(it) } -> Url
                else -> Text
            }
        }
    }
}

/**
 * The content to write: the same string the generated code carries. The
 * reader side parses whatever comes back with the same [ScanPayloadParser] as
 * a camera scan.
 */
data class NfcTagWriteRequest(
    val kind: NfcTagPayloadKind,
    val value: String,
    /** RTD Text language. "en" on both ports: the generator has no idea what
     *  language a free-form string is in, and readers ignore it. */
    val languageTag: String = "en",
) {
    /**
     * Same contract as iOS: non-empty after trimming, and a URL additionally
     * has to carry a scheme (`example.com` is text, not a link).
     */
    val isValid: Boolean
        get() {
            val trimmed = NfcPayloadCodec.trimWhitespace(value)
            if (trimmed.isEmpty()) return false
            if (kind == NfcTagPayloadKind.Url) return NfcPayloadCodec.hasScheme(trimmed)
            return true
        }
}

/**
 * One-shot NFC read or write session — the Android counterpart of
 * `Scan/NFCTagWriter.swift`.
 *
 * Core NFC's session becomes `NfcAdapter.enableReaderMode`: reader mode runs
 * only after the user pressed the control, only while the owning screen is
 * resumed (the composable that owns the writer cancels on `ON_PAUSE` and on
 * dispose — see `rememberNfcTagWriter`), and it is switched off again as soon
 * as the session's one tag has been dealt with. Nothing is read without that
 * gesture — hence no `TECH_DISCOVERED` intent filter, which would wake the app
 * on every tag the phone walks past — and nothing read or written leaves the
 * device. Nor does the tag go anywhere else afterwards: before reader mode is
 * switched off, the NFC service is told to leave that tag alone while it stays
 * in range ([NfcAdapter.ignore]), because the service polls again the moment
 * reader mode ends and would hand a tag still held to the phone to normal
 * dispatch — a link tag would open straight in the browser, past the result
 * sheet and the safety check the read was for.
 *
 * Writes target *writable* tags only: [Ndef.isWritable] and [Ndef.getMaxSize]
 * are answered before a single byte is written, so a read-only tag (most of
 * the cheap stickers sold as "NFC business cards") or one too small for the
 * content fails with a clear message instead of a half-written tag. A blank
 * tag that has never been NDEF-formatted is formatted with the content in one
 * step ([NdefFormatable]) — something Core NFC cannot do, so iOS reports
 * those as "doesn't speak NDEF".
 *
 * Threading: reader-mode callbacks arrive on a binder thread and tag I/O is
 * blocking, so the I/O runs on [Dispatchers.IO]; status is a [StateFlow]
 * (safe from any thread), and the read result and reader-mode teardown are
 * posted to the main thread, where `disableReaderMode` must be called.
 *
 * Not a ViewModel: it is tied to an [Activity]'s reader mode, so the
 * composable that owns the NFC control creates it via `rememberNfcTagWriter`
 * and disposes it with itself.
 */
class NfcTagWriter {

    /** Session state, mirroring iOS's `Status` enum one-for-one. */
    sealed interface Status {
        data object Idle : Status

        /** Reader mode is on, waiting for a tag. */
        data object Scanning : Status

        /** A writable tag is connected and the write is in flight. */
        data object Writing : Status

        /** Done: a tag was written or read; the message says which. */
        data class Wrote(val message: String) : Status

        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What the running session is for. */
    private sealed interface Job {
        class Write(val message: NdefMessage) : Job
        class Read(val deliver: (ScanPayload, String) -> Unit) : Job
    }

    /**
     * One reader-mode session. [id] comes from [generation] and is never
     * reused, so a late callback from a replaced or cancelled session is
     * recognised and dropped; [claimed] makes sure only the first tag of a
     * session is acted on.
     */
    private class Session(val id: Int, val activity: Activity, val job: Job) {
        val claimed = AtomicBoolean(false)
    }

    private val generation = AtomicInteger(0)
    private val lock = Any()
    private var session: Session? = null

    // MARK: - Write

    /**
     * Write [request] to the next writable tag held to the phone. [activity]
     * hosts reader mode, so this must be called from a resumed activity — in
     * practice, from the button's click handler.
     */
    fun write(activity: Activity, request: NfcTagWriteRequest) {
        if (!request.isValid) {
            fail("Nothing to write yet — fill in the content first.")
            return
        }
        NfcSupport.unavailableReason(activity, writing = true)?.let {
            fail(it)
            return
        }
        val records = NfcPayloadCodec.recordsFor(request)
        val message = records?.let { list ->
            runCatching { NdefMessage(list.map { it.toNdefRecord() }.toTypedArray()) }.getOrNull()
        }
        if (message == null) {
            fail("Could not encode the tag content.")
            return
        }
        start(activity, Job.Write(message), writing = true)
    }

    // MARK: - Read

    /**
     * Read the next tag and hand back the decoded payload and the raw string
     * — the same pair a camera scan produces, so callers reuse every existing
     * affordance (smart actions, risk inspector, save-to-history). [onResult]
     * runs on the main thread.
     */
    fun read(activity: Activity, onResult: (ScanPayload, String) -> Unit) {
        NfcSupport.unavailableReason(activity, writing = false)?.let {
            fail(it)
            return
        }
        start(activity, Job.Read(onResult), writing = false)
    }

    /** Tear the session down. Safe to call from `onDispose` / `ON_PAUSE`, any number of times. */
    fun cancel() {
        closeSession()
        _status.value = Status.Idle
    }

    /**
     * The content the control writes changed — iOS `contentDidChange()`. An
     * outcome reported for the old content ("Tag written", or why it wasn't)
     * says nothing about what a tap writes now, so it goes, and a session
     * still waiting for a tag is stopped rather than left to write the old
     * content.
     */
    fun contentChanged() {
        if (_status.value != Status.Idle) cancel()
    }

    /** Release the I/O scope when the owning composable goes away for good. */
    fun dispose() {
        cancel()
        scope.cancel()
    }

    // MARK: - Session

    /** A discovered `Tag` is single-use, so every attempt arms reader mode afresh. */
    private fun start(activity: Activity, job: Job, writing: Boolean) {
        closeSession()
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(activity) }.getOrNull()
        if (adapter == null) {
            fail(unavailableMessage(writing))
            return
        }
        val next = Session(generation.incrementAndGet(), activity, job)
        synchronized(lock) { session = next }
        _status.value = Status.Scanning
        runCatching {
            adapter.enableReaderMode(activity, { tag -> onTag(next, tag) }, READER_FLAGS, null)
        }.onFailure {
            synchronized(lock) { if (session === next) session = null }
            fail(unavailableMessage(writing))
        }
    }

    private fun isCurrent(candidate: Session): Boolean = synchronized(lock) { session === candidate }

    /**
     * Reader-mode callback (binder thread): hand the first tag to the I/O
     * scope, and release it — then reader mode — once it has been dealt with,
     * however that went.
     */
    private fun onTag(candidate: Session, tag: Tag) {
        if (!isCurrent(candidate) || !candidate.claimed.compareAndSet(false, true)) return
        scope.launch {
            try {
                when (val job = candidate.job) {
                    is Job.Write -> writeTag(candidate, tag, job.message)
                    is Job.Read -> readTag(candidate, tag, job.deliver)
                }
            } finally {
                release(candidate, tag)
            }
        }
    }

    private fun writeTag(candidate: Session, tag: Tag, message: NdefMessage) {
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            formatTag(candidate, tag, message)
            return
        }
        try {
            ndef.connect()
            if (!ndef.isWritable) {
                conclude(candidate, failure = "That tag is read-only. Writable NDEF tags are sold as such.")
                return
            }
            // A tag that reports no capacity is left to `writeNdefMessage`,
            // which has its own too-small error — the iOS rule.
            val capacity = ndef.maxSize
            val needed = message.byteArrayLength
            if (capacity in 1 until needed) {
                conclude(candidate, failure = "That tag holds $capacity bytes; this content needs $needed.")
                return
            }
            if (!markWriting(candidate)) return
            ndef.writeNdefMessage(message)
            if (conclude(candidate, success = "Tag written")) Haptics.success(candidate.activity)
        } catch (error: Exception) {
            conclude(candidate, failure = message(error, writing = true))
        } finally {
            runCatching { ndef.close() }
        }
    }

    /** A blank tag: format it with the content in one go, or say it can't take NDEF. */
    private fun formatTag(candidate: Session, tag: Tag, message: NdefMessage) {
        val formatable = NdefFormatable.get(tag)
        if (formatable == null) {
            conclude(candidate, failure = "That tag doesn't speak NDEF.")
            return
        }
        try {
            formatable.connect()
            if (!markWriting(candidate)) return
            formatable.format(message)
            if (conclude(candidate, success = "Tag written")) Haptics.success(candidate.activity)
        } catch (error: Exception) {
            conclude(candidate, failure = message(error, writing = true))
        } finally {
            runCatching { formatable.close() }
        }
    }

    private fun readTag(candidate: Session, tag: Tag, deliver: (ScanPayload, String) -> Unit) {
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            // Formattable but unformatted means "nothing on it yet".
            val failure = if (NdefFormatable.get(tag) != null) "That tag is empty." else "That tag doesn't speak NDEF."
            conclude(candidate, failure = failure)
            return
        }
        try {
            ndef.connect()
            val records = ndef.ndefMessage?.records.orEmpty().map { it.toCodecRecord() }
            if (records.isEmpty()) {
                conclude(candidate, failure = "That tag is empty.")
                return
            }
            val text = NfcPayloadCodec.readableString(records)
            if (text == null) {
                conclude(candidate, failure = "That tag holds no readable text record.")
                return
            }
            val payload = runCatching { ScanPayloadParser.parse(text, Symbology.UNKNOWN) }
                .getOrElse { ScanPayload.Text(text) }
            if (conclude(candidate, success = "Read “${payload.kindLabel}” from the tag")) {
                mainHandler.post { deliver(payload, text) }
            }
        } catch (error: Exception) {
            conclude(candidate, failure = message(error, writing = false))
        } finally {
            runCatching { ndef.close() }
        }
    }

    /**
     * End [candidate] with an outcome, unless it was replaced or cancelled in
     * the meantime. Returns whether the outcome counted. The check and the
     * status change happen under one lock, so a cancel can't slip between
     * them and be overwritten. Reader mode stays on until [release], which
     * runs after the tag has been closed.
     */
    private fun conclude(candidate: Session, success: String? = null, failure: String? = null): Boolean =
        synchronized(lock) {
            if (session !== candidate) return false
            session = null
            _status.value = if (failure != null) Status.Failed(failure) else Status.Wrote(success.orEmpty())
            true
        }

    /** [Status.Writing], if [candidate] is still the running session — checked and set under one lock. */
    private fun markWriting(candidate: Session): Boolean = synchronized(lock) {
        if (session !== candidate) return false
        _status.value = Status.Writing
        true
    }

    private fun fail(message: String) {
        closeSession()
        _status.value = Status.Failed(message)
    }

    /** Stop the running session, if there is one, and switch its reader mode off. */
    private fun closeSession() {
        val closing = synchronized(lock) { session.also { session = null } } ?: return
        disableReaderMode(closing.activity)
    }

    /**
     * Done with [candidate]'s tag: ask the NFC service to ignore it until it
     * has been out of range for [TAG_DEBOUNCE_MILLIS], *then* switch reader
     * mode off. In that order — `ignore` only works while the service still
     * holds the tag, and once reader mode is off it polls again and would
     * dispatch a tag still held to the phone (a link tag opening straight in
     * the browser, the tag just written launching whatever it holds).
     */
    private fun release(candidate: Session, tag: Tag) {
        runCatching { NfcAdapter.getDefaultAdapter(candidate.activity)?.ignore(tag, TAG_DEBOUNCE_MILLIS, null, null) }
        disableReaderMode(candidate.activity)
    }

    /**
     * Switch reader mode off for [activity]. `disableReaderMode` is documented
     * against the main thread, so the call is posted there even from the I/O
     * worker — and skipped if, by the time it runs, a new session has armed
     * reader mode on the same activity again: `start` arms synchronously, so a
     * late "off" would otherwise leave that session waiting on a reader that
     * is no longer there.
     */
    private fun disableReaderMode(activity: Activity) {
        mainHandler.post {
            val rearmed = synchronized(lock) { session?.activity === activity }
            if (!rearmed) runCatching { NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity) }
        }
    }

    private fun unavailableMessage(writing: Boolean): String =
        if (writing) NfcSupport.NO_ADAPTER_MESSAGE else NfcSupport.READ_UNAVAILABLE_MESSAGE

    /** iOS `message(for:)`, mapped onto the framework's exceptions. */
    private fun message(error: Exception, writing: Boolean): String = when (error) {
        is TagLostException -> "The tag moved away. Hold it still and try again."
        is FormatException ->
            if (writing) "The tag refused the write." else "That tag's content isn't valid NDEF."
        is IOException -> "The tag moved away. Hold it still and try again."
        else -> if (writing) "The tag refused the write." else "Couldn't read that tag."
    }

    companion object {
        /**
         * Whether this device has an NFC controller at all — gates the UI, so
         * a device without one never shows a control that can only fail,
         * exactly like `NFCTagWriter.isSupported` on iOS. NFC that is merely
         * switched off still shows the control: Android, unlike iOS, lets the
         * user turn the radio off, and tapping explains how to turn it on.
         */
        fun isSupported(context: Context): Boolean = NfcSupport.hasHardware(context)

        /** The prompts iOS puts in the system session sheet. */
        const val HOLD_TO_WRITE_PROMPT: String = "Hold your phone near a writable tag…"
        const val HOLD_TO_READ_PROMPT: String = "Hold near an NFC tag"

        /**
         * How long a tag Scan has just dealt with must stay out of range
         * before the NFC service will dispatch it again — long enough to ride
         * out a tag flickering at the edge of the field (the controller polls
         * every 100–500 ms), short enough that lifting the phone and tapping
         * again works at once.
         */
        private const val TAG_DEBOUNCE_MILLIS: Int = 1_000

        /**
         * Every tag technology that can carry NDEF. `enableReaderMode` gates
         * on *technologies* (there is no NDEF flag), so asking for all four is
         * what makes a Type 2 sticker, a FeliCa card and an NFC-B tag all show
         * up in the same session.
         */
        private const val READER_FLAGS: Int =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V
    }
}

/** A codec record in the framework's shape. Throws for a record `NdefRecord` rejects. */
private fun NfcPayloadCodec.Record.toNdefRecord(): NdefRecord = NdefRecord(tnf, type, null, payload)

/** A framework record in the pure codec's shape — the raw TNF, TYPE and payload. */
private fun NdefRecord.toCodecRecord(): NfcPayloadCodec.Record = NfcPayloadCodec.Record(
    tnf = tnf,
    type = type ?: ByteArray(0),
    payload = payload ?: ByteArray(0),
)

/**
 * The availability gate, split out so the decision itself is testable
 * without an [android.nfc.NfcAdapter].
 *
 * Three states matter to the UI:
 *  - no NFC hardware at all → hide the control (iOS hides it the same way);
 *  - hardware present but switched off → show the control, and explain how
 *    to turn NFC on when it is tapped (Android, unlike iOS, lets the user
 *    toggle the radio);
 *  - ready.
 */
object NfcSupport {

    const val NO_ADAPTER_MESSAGE: String = "This device can't write NFC tags."
    const val READ_UNAVAILABLE_MESSAGE: String = "This device can't read NFC tags."
    const val NFC_OFF_MESSAGE: String = "NFC is turned off. Turn it on in Settings to use tags."

    /**
     * Pure decision: the device advertises the NFC feature *and* the adapter
     * reports itself enabled. A missing adapter (`null` — no hardware, or a
     * device with no NFC service at all) is treated as "not supported".
     */
    fun isAvailable(hasNfcFeature: Boolean, adapterEnabled: Boolean?): Boolean =
        hasNfcFeature && adapterEnabled == true

    /** Whether the device has an NFC controller, switched on or not. */
    fun hasHardware(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) &&
            runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull() != null

    /**
     * Why a session can't start right now, phrased for the direction asked
     * for — or null when it can.
     */
    fun unavailableReason(context: Context, writing: Boolean): String? {
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()
        val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
        if (adapter == null || !hasFeature) {
            return if (writing) NO_ADAPTER_MESSAGE else READ_UNAVAILABLE_MESSAGE
        }
        val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)
        return if (isAvailable(hasNfcFeature = true, adapterEnabled = enabled)) null else NFC_OFF_MESSAGE
    }
}
