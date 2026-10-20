package ye.mishwari

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ye.mishwari.data.MishwariDatabase
import ye.mishwari.ui.MishwariRoot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp")
class DirectorySettingsRobolectricTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun bundledDirectoryIsPresentAutomaticallyWithVerifiedCountsAndNoAlertCoordinates() = runBlocking {
        val bundled = MishwariDatabase.get(application).dao().allPlaces()
            .filter { it.source.contains("OCHA Yemen / HDX COD-AB") }

        assertEquals(22, bundled.count { it.kind == "governorate" })
        assertEquals(335, bundled.count { it.kind == "district" })
        assertEquals(1, bundled.count { it.kind == "country" })
        assertTrue(bundled.all { it.latitude == null && it.longitude == null && !it.monitored })
        assertTrue(bundled.filter { it.kind == "district" }.all { it.parentId != null })
    }

    @Test
    fun settingsHideAdvancedDirectoryImportAndBothAssistantEntriesHaveClearReturn() {
        val model = MishwariViewModel(application)
        compose.activity.setContent {
            MishwariRoot(model, 0, null, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("مساعد المشوار الذكي").fetchSemanticsNodes().isNotEmpty()
        }

        // The list entry opens the assistant overlay with an explicit way back.
        compose.onNodeWithText("مساعد المشوار الذكي").performClick()
        compose.onNodeWithText("رجوع إلى مشواري").assertIsDisplayed().performClick()

        // Settings remain short until the advanced section is explicitly opened.
        compose.onAllNodesWithText("الإعدادات").onLast().performClick()
        compose.onNodeWithText("مساعد المشوار الذكي").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("رجوع إلى مشواري").assertIsDisplayed().performClick()
        compose.onNodeWithText("استيراد تحديث دليل من JSON").assertDoesNotExist()
        compose.onNodeWithText("الأذونات والخصوصية").performScrollTo().performClick()
        compose.onNodeWithText("استيراد تحديث دليل من JSON").performScrollTo().assertIsDisplayed()
    }
}
