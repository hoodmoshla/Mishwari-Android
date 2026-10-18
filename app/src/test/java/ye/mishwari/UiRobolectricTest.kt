package ye.mishwari

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.activity.ComponentActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ye.mishwari.data.MishwariDatabase
import ye.mishwari.ui.MishwariRoot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp")
class UiRobolectricTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun clearAppDatabase() = runBlocking {
        val dao = MishwariDatabase.get(application).dao()
        dao.allErrands().forEach { dao.deleteErrand(it.id) }
        dao.allPlaces().forEach { dao.deletePlace(it.id) }
    }

    @After
    fun removeTestRows() = clearAppDatabase()

    @Test
    fun arabicRtlEmptyStateAndAddEditDeleteErrandFlow() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(application)
        clearAppDatabase()
        val model = MishwariViewModel(application)
        compose.activity.setContent {
            MishwariRoot(
                model = model,
                permissionRevision = 0,
                deepLinkPlace = null,
                consumeDeepLink = {},
                requestLocation = {},
                requestNotifications = {},
                openAppSettings = {},
                openLocationSettings = {},
                exportBackup = {},
                importBackup = {},
                importDirectory = {},
                selectPhoto = { _ -> }
            )
        }

        compose.onNodeWithText("لا توجد طلبات هنا").performScrollTo().assertIsDisplayed()
        assertEquals(LayoutDirection.Rtl, compose.onNodeWithText("مشواري").fetchSemanticsNode().layoutInfo.layoutDirection)

        compose.onNodeWithText("+ إضافة طلب", useUnmergedTree = true).performClick()
        compose.onNodeWithText("اسم الغرض أو المهمة *").performTextInput("شراء قهوة")
        compose.onNodeWithText("حفظ").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("حفظ").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("شراء قهوة").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("اسم الغرض أو المهمة *").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("لم تُكتمل العملية").assertDoesNotExist()
        compose.onNodeWithText("شراء قهوة").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("تعديل").performClick()
        compose.onNodeWithText("اسم الغرض أو المهمة *").performTextReplacement("شراء قهوة عربية")
        compose.onNodeWithText("حفظ").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("حفظ").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("شراء قهوة عربية").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("اسم الغرض أو المهمة *").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("شراء قهوة عربية").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("شراء قهوة").assertDoesNotExist()

        compose.onNodeWithText("حذف").performClick()
        compose.onNodeWithText("متابعة").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("لا توجد طلبات هنا").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("لا توجد طلبات هنا").performScrollTo().assertIsDisplayed()
        assertTrueDatabaseIsEmpty()
    }

    private fun assertTrueDatabaseIsEmpty() = runBlocking {
        val dao = MishwariDatabase.get(application).dao()
        assertEquals(emptyList<ye.mishwari.data.Errand>(), dao.allErrands())
    }
}
