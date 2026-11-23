package me.nettrash.scan.tile

import android.content.ComponentName
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.graphics.drawable.VectorDrawable
import android.view.View
import android.widget.ImageView
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * The Quick Settings tile has its own icon: a white-on-transparent vector,
 * as the tile guide asks. SystemUI tints tile icons (an ImageView tint list,
 * SRC_IN), so only the glyph's shape survives — the opaque adaptive app
 * icon the tile used to fall back to came out as a solid blob.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScanTileIconTest {

    @Test fun tileIconIsAGlyphThatSurvivesSystemUiTinting() {
        val app = RuntimeEnvironment.getApplication()
        val service = app.packageManager.getServiceInfo(ComponentName(app, ScanTileService::class.java), 0)

        assertThat(service.icon).isNotEqualTo(0)
        assertThat(app.resources.getResourceTypeName(service.icon)).isEqualTo("drawable")
        assertThat(app.resources.getResourceEntryName(service.icon)).isEqualTo("ic_tile_scan")

        // Drawn the way SystemUI's tile view draws it.
        val drawable = Icon.createWithResource(app.packageName, service.icon).loadDrawable(app)!!
        assertThat(drawable).isInstanceOf(VectorDrawable::class.java)
        val px = 96
        val view = ImageView(app).apply {
            setImageDrawable(drawable)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            measure(
                View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, px, px)
        }
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val pixels = IntArray(px * px).also { bitmap.getPixels(it, 0, px, 0, 0, px, px) }

        val transparent = pixels.count { Color.alpha(it) == 0 }
        val opaque = pixels.filter { Color.alpha(it) == 255 }
        // A glyph, not a filled shape: most of the square stays clear…
        assertThat(transparent.toDouble() / pixels.size).isAtLeast(0.30)
        // …and what is drawn is the tint colour.
        assertThat(opaque).isNotEmpty()
        assertThat(opaque.map { it and 0xFFFFFF }.toSet()).containsExactly(0xFFFFFF)
    }
}
