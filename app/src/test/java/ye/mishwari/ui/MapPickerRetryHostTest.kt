package ye.mishwari.ui

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MapPickerRetryHostTest {
    @Test
    fun replacementDetachesPreviousNativeViewAndAttachesRetryView() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val container = FrameLayout(context)
        val oldView = View(context)
        val retryView = View(context)

        replaceAndroidViewChild(container, oldView)
        assertEquals(1, container.childCount)
        assertSame(oldView, container.getChildAt(0))
        assertSame(container, oldView.parent)

        replaceAndroidViewChild(container, retryView)
        assertEquals(1, container.childCount)
        assertSame(retryView, container.getChildAt(0))
        assertNull("retry must detach the destroyed MapView", oldView.parent)
        assertSame(container, retryView.parent)

        replaceAndroidViewChild(container, retryView)
        assertEquals("updating the same attempt must not add a duplicate child", 1, container.childCount)
    }
}
