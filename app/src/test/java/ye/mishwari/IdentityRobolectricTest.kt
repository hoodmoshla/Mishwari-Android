package ye.mishwari

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IdentityRobolectricTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun launcherIdentityLoadsAdaptiveAndMonochromeResources() {
        val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(R.mipmap.ic_mishwari, appInfo.icon)
        assertEquals("طلباتي للمشوار", appInfo.loadLabel(context.packageManager).toString())

        val launcher = context.getDrawable(R.mipmap.ic_mishwari)
        assertTrue("API 33+ launcher resource must inflate as AdaptiveIconDrawable", launcher is AdaptiveIconDrawable)
        val adaptive = launcher as AdaptiveIconDrawable
        assertNotNull("adaptive background layer", adaptive.background)
        assertNotNull("adaptive foreground layer", adaptive.foreground)
        val monochrome = adaptive.monochrome
        assertNotNull("API 33+ monochrome layer", monochrome)

        val lightBackdrop = Color.rgb(246, 247, 244)
        val darkBackdrop = Color.rgb(20, 24, 22)
        val sizes = intArrayOf(16, 24, 32, 48, 96, 108)
        for (backdrop in intArrayOf(lightBackdrop, darkBackdrop)) {
            for (size in sizes) {
                for (circularMask in booleanArrayOf(false, true)) {
                    assertDrawableRenders(launcher, size, backdrop, circularMask)
                    assertDrawableRenders(monochrome!!, size, backdrop, circularMask)
                }
            }
        }
    }

    @Test
    @Config(sdk = [26, 32])
    fun olderAndroidLoadsAdaptiveIconWithoutMonochromeRequirement() {
        val launcher = context.getDrawable(R.mipmap.ic_mishwari)
        assertTrue(launcher is AdaptiveIconDrawable)
        assertEquals("طلباتي للمشوار", context.getString(R.string.app_name))
        assertDrawableRenders(launcher!!, 48, Color.WHITE, true)
        assertDrawableRenders(launcher, 48, Color.BLACK, false)
    }

    private fun assertDrawableRenders(
        drawable: Drawable,
        size: Int,
        backdropColor: Int,
        circularMask: Boolean
    ) {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backdropColor)

        val mask = Path()
        if (circularMask) {
            mask.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
        } else {
            val radius = size * 0.18f
            mask.addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(mask)
        val instance = drawable.constantState
            ?.newDrawable(context.resources, context.theme)
            ?.mutate() ?: drawable.mutate()
        instance.setBounds(0, 0, size, size)
        instance.draw(canvas)
        canvas.restore()

        val maskName = if (circularMask) "circle" else "rounded square"
        // At 16px a rounded clip partially covers the corner pixel through antialiasing.
        // The corner must stay predominantly backdrop, rather than requiring bit equality.
        val corner = bitmap.getPixel(0, 0)
        val cornerDifference = listOf(
            kotlin.math.abs(Color.red(corner) - Color.red(backdropColor)),
            kotlin.math.abs(Color.green(corner) - Color.green(backdropColor)),
            kotlin.math.abs(Color.blue(corner) - Color.blue(backdropColor)),
        ).max()
        assertTrue("$size px $maskName must preserve the clipped corner (allow edge antialiasing)", cornerDifference <= 32)
        var changedPixels = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (bitmap.getPixel(x, y) != backdropColor) changedPixels++
            }
        }
        assertTrue(
            "$size px $maskName icon should draw over backdrop #${Integer.toHexString(backdropColor)}",
            changedPixels > size
        )
        bitmap.recycle()
    }
}
