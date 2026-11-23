package me.nettrash.scan.data.payload

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

//
//  The "explain this code" helper, run entirely on the device.
//
//  WHICH ENGINE ANSWERS TODAY
//  --------------------------
//  [HeuristicExplainer] does — every single time. It is the deterministic,
//  rule-based explainer ported from the iOS sibling, assembled from the
//  parser's own metadata plus the [QrRiskAssessor] findings.
//
//  Scan's whole product promise is that nothing you scan leaves the phone, so
//  the assistant has to honour that. Android has no shipped equivalent of
//  Apple's on-device `FoundationModels` that this app may depend on, so there
//  is deliberately **no** network code path here at all: no HTTP client, no
//  Retrofit / OkHttp call, no cloud LLM SDK. The `okhttp` dependency that is
//  already in the build is not used by this file and must stay unused.
//
//  WHERE THE ON-DEVICE GenAI BACKEND PLUGS IN
//  ------------------------------------------
//  [ScanAssistant.registerBackend]. A future release that ships an on-device
//  generative model (e.g. a `text-generation` / ML Kit GenAI artefact bundled
//  with the app) implements [ScanAssistant.OnDeviceAssistantBackend] and
//  registers it at application start. Nothing in the UI changes: the assistant
//  asks the backend for readiness, feeds it the [SecretRedaction]ed payload
//  only, and falls back to [HeuristicExplainer] whenever the backend is
//  absent, not ready, slower than [ScanAssistant.MODEL_DEADLINE_MILLIS], or
//  answers with something unusable. Its cautions are never shown: the card's
//  cautions are the built-in, tested ones.
//  [AssistantSource.ON_DEVICE_MODEL] exists for exactly that future backend —
//  no code path can produce it today.
//

/** What the assistant says about a code. Plain value type so the heuristic
 *  engine and a future model-produced answer share one UI. */
data class AssistantExplanation(
    val headline: String,
    val summary: String,
    val keyPoints: List<String>,
    val cautions: List<String>,
    /** Which engine produced this — surfaced in the UI so the user knows
     *  whether they got the model or the built-in explainer. */
    val source: AssistantSource,
)

enum class AssistantSource(val label: String) {
    /**
     * Reserved for the future on-device GenAI backend — never produced today.
     * The label names the engine honestly: unlike iOS, which can say "Apple
     * Intelligence", an Android build must name whatever actually ran
     * (today's on-device engines are ML Kit / Play-services backed).
     */
    ON_DEVICE_MODEL("On-device model (ML Kit)"),

    /** The deterministic rule-based engine: the only one that answers today. */
    BUILT_IN("Built-in explainer"),
}

/**
 * What the assistant says about this device, in the same three shapes as iOS
 * `AssistantAvailability.current()`.
 *
 * [UNAVAILABLE] is deliberately **not** an error state: the deterministic
 * [HeuristicExplainer] answers instead, so the feature is never hidden
 * entirely and the user is never told "the assistant is broken".
 */
sealed class AssistantAvailability {
    /** The on-device model can answer now. */
    data object READY : AssistantAvailability()

    /**
     * The model exists on this device but the user has to act first — the
     * [reason] is shown verbatim in Settings, exactly like iOS's
     * `.needsSetup(reason:)` ("Apple Intelligence is still downloading" and
     * friends). On Android that means things like a one-time model download
     * or the on-device component being switched off system-wide.
     */
    data class NEEDS_SETUP(val reason: String) : AssistantAvailability()

    /** No on-device model on this hardware / build. The built-in explainer answers. */
    data object UNAVAILABLE : AssistantAvailability()
}

/**
 * The on-device explain/compose helper. See the file header: today every
 * answer comes from the deterministic [HeuristicExplainer].
 */
object ScanAssistant {

    /**
     * The optional on-device generative backend. `null` in every build so
     * far, which is what makes "the built-in explainer answered" the whole
     * story rather than a fallback that might secretly be a server call.
     *
     * Each call gets [MODEL_DEADLINE_MILLIS] and must give up when its
     * coroutine is cancelled; it should also cap its own output (iOS asks for
     * at most 400 tokens for an explanation and 300 for a form).
     */
    interface OnDeviceAssistantBackend {
        /** Whether the model is installed, downloaded and allowed to run. */
        fun readiness(): AssistantAvailability

        /**
         * Explain the (already redacted) payload, or null to fall back. Its
         * headline, summary and key points are used; its cautions are not —
         * the card's cautions are the built-in, tested ones.
         */
        suspend fun explain(redactedRaw: String): AssistantExplanation?

        /** Fill the Generate form from a sentence, or null to fall back. */
        suspend fun compose(request: String): ComposeResult?
    }

    /**
     * How long a model backend may take before the built-in answer is shown
     * instead — iOS `ScanAssistant.modelDeadline`. A runaway generation once
     * kept iOS "thinking on the device" for four minutes.
     */
    const val MODEL_DEADLINE_MILLIS: Long = 10_000

    private var backend: OnDeviceAssistantBackend? = null

    /**
     * Plug-in point for the future on-device GenAI backend. Call it once at
     * application start with the shipped implementation, or with `null` to
     * take it back out. Passing anything that talks to a network is a bug.
     */
    fun registerBackend(backend: OnDeviceAssistantBackend?) {
        this.backend = backend
    }

    /**
     * What the assistant will do right now, in the iOS three-case shape.
     *
     * [assistantEnabled] is `SettingsState.assistantEnabled`; the built-in
     * explainer answers either way, so a missing or disabled model is
     * [AssistantAvailability.UNAVAILABLE] rather than an error — which is
     * also what the Settings status line prints.
     */
    fun availability(assistantEnabled: Boolean): AssistantAvailability {
        if (!assistantEnabled) return AssistantAvailability.UNAVAILABLE
        val model = backend ?: return AssistantAvailability.UNAVAILABLE
        return model.readiness()
    }

    /**
     * One honest sentence about which engine *this* device will use, for the
     * Settings screen. Mirrors the iOS `assistantStatusLine`, minus the Apple
     * branding: no on-device model means the built-in explainer, not an error,
     * and a switched-off assistant says so rather than naming an engine.
     */
    fun statusLine(assistantEnabled: Boolean): String {
        if (!assistantEnabled) {
            return "The assistant is off, so results show no \u201CWhat is this code?\u201D button."
        }
        return when (val status = availability(assistantEnabled = true)) {
            AssistantAvailability.READY ->
                "Explain uses the on-device model on this device."
            is AssistantAvailability.NEEDS_SETUP ->
                "Explain uses the on-device model once it is ready (${status.reason}); " +
                    "until then the built-in explainer answers."
            AssistantAvailability.UNAVAILABLE ->
                "Explain uses the built-in explainer on this device."
        }
    }

    /**
     * Explain a decoded code in plain language. Never fails — a cancelled
     * caller is simply cancelled — and the worst case is the built-in
     * explainer, at the latest after [deadlineMillis].
     *
     * Exactly the iOS split: the built-in explainer reads the code itself —
     * it never repeats a password, key or secret, and its safety cautions
     * need the real payload (a redacted seed phrase is no longer recognisable
     * as one) — while a model backend only ever sees the [SecretRedaction]ed
     * text.
     */
    suspend fun explain(
        raw: String,
        payload: ScanPayload?,
        deadlineMillis: Long = MODEL_DEADLINE_MILLIS,
    ): AssistantExplanation {
        val fallback = HeuristicExplainer.explain(raw, payload ?: ScanPayloadParser.parse(raw))
        val model = backend ?: return fallback
        val prompt = SecretRedaction.redactForModel(raw)
        if (model.readiness() != AssistantAvailability.READY) return fallback
        val explanation = withinDeadline(deadlineMillis) { model.explain(prompt) } ?: return fallback
        if (explanation.summary.isBlank()) return fallback
        // The model's words, the rules' cautions: the cautions on the card are
        // the tested safety rules only, never something a model imagined
        // about an ordinary code (iOS does the same).
        return explanation.copy(cautions = fallback.cautions)
    }

    /**
     * Turn a sentence typed into the Generate tab into a payload spec.
     * Returns null when the request is ambiguous, so the form stays as the
     * user left it rather than guessing.
     */
    suspend fun compose(request: String, deadlineMillis: Long = MODEL_DEADLINE_MILLIS): ComposeResult? {
        val trimmed = request.trim()
        if (trimmed.isEmpty()) return null
        val model = backend
        if (model == null || model.readiness() != AssistantAvailability.READY) {
            return HeuristicExplainer.compose(trimmed)
        }
        val composed = withinDeadline(deadlineMillis) { model.compose(trimmed) }
        return if (composed == null || composed.isBlank) HeuristicExplainer.compose(trimmed) else composed
    }

    /**
     * [block]'s answer within [deadlineMillis], or null when it fails or runs
     * out of time; a cancellation of the caller still cancels.
     */
    private suspend fun <T> withinDeadline(deadlineMillis: Long, block: suspend () -> T?): T? =
        withTimeoutOrNull(deadlineMillis) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    /** Longest headline the UI will lay out on one line, shared by both engines. */
    internal const val HEADLINE_LIMIT = 80
}
