package me.nettrash.scan.testutil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Just enough of a Compose test harness for Robolectric without the
 * `ui-test` artifacts: find the composition root under a view, read its
 * semantics tree, and send real touches through the window, so hit-testing
 * and z-order are what the app gets. Plus a way to wait for DataStore work
 * without stalling the main looper it needs.
 */

/** The Compose root under this view (the activity's decor view, a popup). */
internal fun View.composeRoot(): ViewRootForTest? {
    if (this is ViewRootForTest) return this
    if (this is ViewGroup) {
        for (i in 0 until childCount) getChildAt(i).composeRoot()?.let { return it }
    }
    return null
}

/** Every node of the merged (what TalkBack sees) or unmerged tree. */
internal fun ViewRootForTest.nodes(merged: Boolean = true): List<SemanticsNode> {
    val out = mutableListOf<SemanticsNode>()
    fun walk(n: SemanticsNode) {
        out += n
        n.children.forEach(::walk)
    }
    walk(if (merged) semanticsOwner.rootSemanticsNode else semanticsOwner.unmergedRootSemanticsNode)
    return out
}

internal fun SemanticsNode.texts(): List<String> =
    config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

internal fun SemanticsNode.descriptions(): List<String> =
    config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

/** The first node whose text or content description is exactly [label]. */
internal fun ViewRootForTest.node(label: String, merged: Boolean = true): SemanticsNode =
    nodes(merged).firstOrNull { label in it.texts() || label in it.descriptions() }
        ?: error("No node labelled \"$label\"")

/** Runs the main looper for [ms] of Robolectric's clock. */
internal fun idleFor(ms: Long) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
}

/**
 * Runs [block] to completion on a helper thread while this (main) thread
 * keeps the main looper turning — for DataStore work from a test. DataStore
 * runs an edit's transform in its caller's context, so an edit a ViewModel
 * started on `viewModelScope` waits for the main looper; a `runBlocking` on
 * the main thread queued behind it would deadlock.
 */
internal fun <T> awaitWhilePumpingMainLooper(timeoutMs: Long = 10_000, block: suspend () -> T): T {
    val result = AtomicReference<Result<T>?>(null)
    Thread { result.set(runCatching { runBlocking { block() } }) }.start()
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (result.get() == null) {
        check(System.nanoTime() < deadline) { "Timed out after $timeoutMs ms" }
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(5)
    }
    return result.get()!!.getOrThrow()
}

/**
 * A finger down and up at ([x], [y]) in [root]'s coordinates, then enough
 * clock for a menu's enter animation to finish.
 */
internal fun tap(root: View, x: Float, y: Float) {
    val t = SystemClock.uptimeMillis()
    root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
    idleFor(60)
    root.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
    idleFor(600)
}

/** [tap] at the centre of [node]. */
internal fun tap(root: View, node: SemanticsNode) {
    val b = node.boundsInWindow
    tap(root, b.center.x, b.center.y)
}

/**
 * A finger down and up at the centre of [node] with no frame run after the
 * release: what the press hit has handled it, and nothing it set off has
 * been drawn yet. For a control that opens a DropdownMenu — Robolectric
 * takes minutes to lay out the menu's popup window.
 */
internal fun pressAndRelease(root: View, node: SemanticsNode) {
    val b = node.boundsInWindow
    val t = SystemClock.uptimeMillis()
    root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, b.center.x, b.center.y, 0))
    idleFor(16)
    root.dispatchTouchEvent(MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_UP, b.center.x, b.center.y, 0))
}

/** Draws [view] into a software bitmap (Robolectric NATIVE graphics). */
internal fun renderToBitmap(view: View): Bitmap =
    Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        view.draw(Canvas(it))
    }
