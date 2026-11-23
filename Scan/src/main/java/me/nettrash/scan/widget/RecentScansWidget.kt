package me.nettrash.scan.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import me.nettrash.scan.MainActivity
import me.nettrash.scan.data.db.ScanRecord
import me.nettrash.scan.data.db.ScanRecordDao
import me.nettrash.scan.data.payload.ScanPayload
import me.nettrash.scan.data.payload.ScanPayloadParser
import me.nettrash.scan.scanner.Symbology
import me.nettrash.scan.ui.deeplink.DeepLink

/**
 * Home-screen widget: the last few scans, one tap deep into the app.
 *
 * The Android counterpart to the iOS App Intents surface in
 * `Scan/ScanIntents.swift`. That file's shortcuts are "open the camera, build
 * a code, hand me the last thing I scanned"; a glanceable panel can express
 * two of those directly:
 *
 *  - **Scan a code** opens the camera, mirroring `StartScanningIntent`.
 *  - **A row** re-opens that exact code through the App-Links pipeline
 *    (`https://nettrash.me/scan/<base64url>?t=<symbology>`), so the existing
 *    result sheet, smart actions and risk inspector are reused verbatim — and
 *    it keeps `CopyRecentScanIntent`'s newest-first semantics.
 *  - **Refresh** brings the rows up to date, because the platform clamps
 *    `updatePeriodMillis` to a 30-minute floor.
 *
 * Keeping up with History: a running Glance session follows the DAO's Flow
 * inside its composition, so a code saved while it runs shows up at once —
 * `update()` / `updateAll()` on a live session only recompose it and never
 * re-run [provideGlance], which is why a list read once up front went stale.
 * Refresh and [RecentScansWidgetSync] (which calls `updateAll()` whenever
 * the newest scans change, for as long as the app process runs) start a
 * session when none is running, and that re-reads History — so a code saved
 * a moment ago is already there when the user goes back to the home screen.
 *
 * **What a row shows — never the scanned value.** History's newest rows hold
 * Wi-Fi passwords, 2FA seeds, private keys, passkey codes, licences and
 * boarding passes, and a widget sits in plain view: on the home screen, in
 * screenshots and screen shares, and inside the RemoteViews handed to the
 * launcher, which may be another app. So a row names the kind of code
 * ([widgetRowTitle]), the rule iOS's `CopyRecentScanIntent` follows ("names
 * the kind, never the contents"), adding only details that are not secrets:
 * a link's host and a Wi-Fi network's name, never its password. Tapping a
 * row still opens the full value, inside the app.
 *
 * Data comes straight from the on-device Room database and the click intents
 * only ever carry values the user already scanned, so nothing leaves the
 * phone — the same privacy line the rest of the app holds.
 */
class RecentScansWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = ScanWidgetEntryPoint.resolve(context)
        val initial = newest(withContext(Dispatchers.IO) { dao.observeAll().first() })
        provideContent {
            // Follow History for as long as this session runs (see the
            // class comment): the first frame shows `initial`, and every
            // change to the newest rows recomposes the widget.
            val rows by remember(dao) {
                dao.observeAll().map { newest(it) }.distinctUntilChanged()
            }.collectAsState(initial)
            GlanceTheme {
                RecentScansContent(rows)
            }
        }
    }

    companion object {

        /** Rows the widget can show before the home-screen cell runs out of
         *  room. Longer lists belong in the History tab. At most 10: the rows
         *  share one Glance Column, and a Glance container keeps only its
         *  first 10 children. */
        internal const val MAX_ITEMS = 5

        /**
         * The rows the widget shows, out of everything History holds: newest
         * first, matching `CopyRecentScanIntent`. History's favourites-first
         * ordering would pin years-old codes at the top of a widget whose
         * whole job is "what did I just scan".
         */
        internal fun newest(records: List<ScanRecord>): List<ScanRecord> =
            records.sortedByDescending(ScanRecord::timestamp).take(MAX_ITEMS)
    }
}

/** Receiver the manifest declares — Glance owns the lifecycle plumbing. */
class RecentScansWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentScansWidget()

    /** The first widget was just placed: start following History. */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RecentScansWidgetSync.start(context)
    }
}

/**
 * Redraws placed Recent-scans widgets when the newest scans change, for as
 * long as the app process runs — a widget is a snapshot otherwise, and the
 * platform only refreshes it every 30 minutes.
 *
 * Started from `ScanApplication.onCreate` when a widget is already on the
 * home screen, and from [RecentScansWidgetReceiver.onEnabled] when the first
 * one is added; a phone without the widget never runs the observer. It reads
 * the same on-device Room table History does and asks Glance to redraw —
 * nothing leaves the device.
 */
object RecentScansWidgetSync {

    private val started = AtomicBoolean(false)

    /** Start only if at least one widget instance exists. */
    fun startIfPlaced(context: Context) {
        val app = context.applicationContext
        val ids = runCatching {
            AppWidgetManager.getInstance(app)
                ?.getAppWidgetIds(ComponentName(app, RecentScansWidgetReceiver::class.java))
        }.getOrNull()
        if (ids != null && ids.isNotEmpty()) start(app)
    }

    /** Idempotent: one observer per process. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val dao = runCatching { ScanWidgetEntryPoint.resolve(app) }.getOrNull() ?: run {
            started.set(false)
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            dao.observeAll()
                .map { RecentScansWidget.newest(it) }
                .distinctUntilChanged()
                // The first emission is what the widget already shows.
                .drop(1)
                .collect { runCatching { RecentScansWidget().updateAll(app) } }
        }
    }
}

/**
 * Refresh: `updateAll()` for every placed instance. A running session is
 * already following History and simply recomposes; an instance with no
 * session gets one, whose [RecentScansWidget.provideGlance] re-reads it.
 *
 * Glance instantiates callbacks reflectively
 * (`getDeclaredConstructor().newInstance()`), so this stays a top-level
 * no-arg class — and `proguard-rules.pro` keeps that constructor: Glance's
 * consumer rule keeps only the class under R8 full mode, and without its
 * `<init>()` every Refresh tap in a release build failed silently.
 */
class RecentScansRefreshCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        RecentScansWidget().updateAll(context)
    }
}

@Composable
private fun RecentScansContent(newest: List<ScanRecord>) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(Color(0xE6000000))
            .padding(12.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                text = "Recent scans",
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = "Refresh",
                style = TextStyle(
                    color = ColorProvider(Color(0xFF2196F3)),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = GlanceModifier.padding(start = 8.dp).clickable(
                    actionRunCallback<RecentScansRefreshCallback>()
                ),
            )
        }
        Spacer(modifier = GlanceModifier.height(6.dp))
        if (newest.isEmpty()) {
            Text(
                text = "Nothing scanned yet. Scan a code and it shows up here.",
                style = TextStyle(
                    color = ColorProvider(Color(0xB3FFFFFF)),
                    fontSize = 12.sp,
                ),
            )
        } else {
            // The rows get a Column of their own, spaced by their padding: a
            // Glance container keeps only its first 10 children, and five
            // rows plus a spacer each, beside the header and the button, cut
            // the last row and "Scan a code" off.
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                newest.forEach { record -> ScanRow(context, record) }
            }
        }
        Button(
            text = "Scan a code",
            onClick = actionStartActivity(ScanIntents.launchIntent(context, ScanEntry.Scanner)),
        )
    }
}

@Composable
private fun ScanRow(context: Context, record: ScanRecord) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clickable(actionStartActivity(openScanIntent(context, record))),
    ) {
        // The kind (plus a non-secret detail), never `record.value` — see
        // the class comment. One line each, like a History row.
        Text(
            text = widgetRowTitle(record),
            style = TextStyle(
                color = ColorProvider(Color.White),
                fontSize = 13.sp,
            ),
            maxLines = 1,
        )
        Text(
            text = metaFor(record),
            style = TextStyle(
                color = ColorProvider(Color(0x99FFFFFF)),
                fontSize = 11.sp,
            ),
            maxLines = 1,
        )
    }
}

/**
 * A widget row's title: the payload's kind ("Wi-Fi", "OTP", "Passkey",
 * "Nostr", …) — the same word the result sheet's badge and iOS's
 * `CopyRecentScanIntent` dialog use — never the scanned value. Two kinds add
 * a detail that is not a secret:
 *
 *  - a web link (URL, or a recognised service link such as WhatsApp) adds
 *    its host only — no path or query, where tokens and phone numbers live,
 *    and no user-info;
 *  - Wi-Fi adds its network name (SSID), never the password.
 *
 * Everything else — OTP and 2FA exports, keys and seed phrases, passkeys,
 * licences and IDs, boarding passes, payments, contacts, free text — shows
 * the kind alone. A value the parser can't read is "Text".
 */
internal fun widgetRowTitle(record: ScanRecord): String {
    val payload = runCatching {
        ScanPayloadParser.parse(record.value, Symbology.fromDisplayName(record.symbology))
    }.getOrNull() ?: return "Text"
    val detail = when (payload) {
        is ScanPayload.Url -> webHost(payload.url)
        is ScanPayload.RichUrl -> webHost(payload.payload.url)
        is ScanPayload.Wifi -> payload.ssid
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }
    return if (detail == null) payload.kindLabel else "${payload.kindLabel} · $detail"
}

/** The host of an http(s) link — never its user-info, port, path or query. */
private fun webHost(url: String): String? {
    val uri = runCatching { url.toUri() }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    return uri.host
}

/**
 * `QR · 11/22/26, 10:15 AM` — the History row's secondary line, with the
 * same absolute short date and time in the device's format. A relative
 * "5 minutes ago" would freeze: a widget is only redrawn when History
 * changes or every 30 minutes.
 */
private fun metaFor(record: ScanRecord): String {
    val whenText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
        .format(Date(record.timestamp))
    val symbology = Symbology.fromDisplayName(record.symbology).displayName
    return "$symbology · $whenText"
}

/**
 * Re-open a saved scan through the path MainActivity already routes, so the
 * row lands on the same payload sheet a shared link gets. Falls back to the
 * History tab when the value can't be encoded.
 *
 * The launch flags are [ScanIntents.LAUNCH_FLAGS] for the same reason the
 * entry intents carry them: a warm app must get the link through
 * `onNewIntent` on its one `MainActivity`, not stack a second activity on
 * top of it.
 */
private fun openScanIntent(context: Context, record: ScanRecord): Intent {
    val uri = DeepLink.encode(record.value, Symbology.fromDisplayName(record.symbology))
        ?: return ScanIntents.launchIntent(context, ScanEntry.RecentScans)
    return Intent(Intent.ACTION_VIEW, uri)
        .setClass(context, MainActivity::class.java)
        .addFlags(ScanIntents.LAUNCH_FLAGS)
}

/**
 * Hilt access to the singleton DAO from a widget, which the DI graph does not
 * construct. Same bindings as `di/AppModule.kt`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScanWidgetEntryPoint {
    fun scanRecordDao(): ScanRecordDao

    companion object {
        fun resolve(context: Context): ScanRecordDao = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ScanWidgetEntryPoint::class.java,
        ).scanRecordDao()
    }
}
