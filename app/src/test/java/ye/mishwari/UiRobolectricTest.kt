package ye.mishwari

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
        clearPlacesChildrenFirst(dao)
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

        compose.onNodeWithTag("navigation_1").performClick()
        compose.onNodeWithText("لا توجد طلبات هنا").performScrollTo().assertIsDisplayed()
        assertEquals(LayoutDirection.Rtl, compose.onNodeWithTag("navigation_1").fetchSemanticsNode().layoutInfo.layoutDirection)

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

    @Test
    fun sharedTextRequiresReviewAndExplicitSave() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(application)
        clearAppDatabase()
        val model = MishwariViewModel(application)
        val incoming = mutableStateOf<String?>("شراء دواء\nمن صيدلية موثوقة")
        compose.activity.setContent {
            MishwariRoot(model, 0, null, {}, {}, {}, {}, {}, {}, {}, {}, {},
                incomingDraft = incoming.value, consumeIncomingDraft = { incoming.value = null })
        }
        compose.onNodeWithText("اسم الغرض أو المهمة *").assertIsDisplayed()
        assertTrueDatabaseIsEmpty()
        compose.onNodeWithText("حفظ").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("حفظ").fetchSemanticsNodes().isEmpty() }
        runBlocking {
            val saved = MishwariDatabase.get(application).dao().allErrands().single()
            assertEquals("شراء دواء", saved.title)
            assertEquals("شراء دواء\nمن صيدلية موثوقة", saved.description)
            assertEquals(null, saved.placeId)
        }
    }

    @Test
    fun pendingIncomingTextDoesNotOverwriteEditorAndDraftSurvivesRecreation() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(application)
        clearAppDatabase()
        val model = MishwariViewModel(application)
        val incoming = mutableStateOf<String?>(null)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MishwariRoot(model, 0, null, {}, {}, {}, {}, {}, {}, {}, {}, {},
                incomingDraft = incoming.value, consumeIncomingDraft = { incoming.value = null })
        }
        compose.onNodeWithTag("navigation_1").performClick()
        compose.onNodeWithText("+ إضافة طلب", useUnmergedTree = true).performClick()
        compose.onNodeWithText("اسم الغرض أو المهمة *").performTextInput("مسودة حالية")
        compose.runOnIdle { incoming.value = "نص وارد جديد" }
        compose.onNodeWithText("مسودة حالية").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("مسودة حالية").assertIsDisplayed()
        assertTrueDatabaseIsEmpty()
        compose.onNodeWithText("إلغاء").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("نص وارد جديد").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("نص وارد جديد").assertIsDisplayed()
        compose.onNodeWithText("إلغاء").performClick()
        assertTrueDatabaseIsEmpty()
    }

    @Test
    fun voiceExplainsExternalProviderBeforeLaunching() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(application)
        clearAppDatabase()
        var launches = 0
        val model = MishwariViewModel(application)
        compose.activity.setContent {
            MishwariRoot(model, 0, null, {}, {}, {}, {}, {}, {}, {}, {}, {}, requestVoice = { launches++ })
        }
        compose.onNodeWithText("إضافة بالصوت").performClick()
        compose.onNodeWithText("إملاء طلب بالعربية").assertIsDisplayed()
        assertEquals(0, launches)
        compose.onNodeWithText("متابعة إلى الإملاء").performClick()
        assertEquals(1, launches)
        assertTrueDatabaseIsEmpty()
    }

    @Test
    fun notificationDeepLinkFiltersToItsDestinationPendingErrands() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(application)
        clearAppDatabase()
        runBlocking {
            val dao = MishwariDatabase.get(application).dao()
            dao.upsertPlaces(listOf(ye.mishwari.data.Place(id = "linked", name = "سوق التنبيه"), ye.mishwari.data.Place(id = "other", name = "سوق آخر")))
            dao.upsertErrands(listOf(
                ye.mishwari.data.Errand(id = "linked-task", title = "طلب الوجهة المقصودة", placeId = "linked"),
                ye.mishwari.data.Errand(id = "other-task", title = "طلب وجهة أخرى", placeId = "other"),
                ye.mishwari.data.Errand(id = "done-task", title = "طلب مكتمل مخفي", placeId = "linked", status = "مكتمل"),
            ))
        }
        val model = MishwariViewModel(application)
        compose.activity.setContent {
            MishwariRoot(model, 0, "linked", {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("الوجهة: سوق التنبيه — عرض الجميع ×").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("طلب الوجهة المقصودة").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("طلب وجهة أخرى").assertDoesNotExist()
        compose.onNodeWithText("طلب مكتمل مخفي").assertDoesNotExist()
    }

    @Test
    fun endingTravelFromUiClearsTripWithoutDeletingErrandsOrMonitoredPlaces() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(application)
        clearAppDatabase()
        runBlocking {
            val dao = MishwariDatabase.get(application).dao()
            dao.upsertPlace(ye.mishwari.data.Place(id = "trip-place", name = "وجهة الرحلة", monitored = true, latitude = 15.0, longitude = 44.0))
            dao.upsertErrand(ye.mishwari.data.Errand(id = "trip-task", title = "طلب الرحلة", placeId = "trip-place"))
            dao.putSetting(ye.mishwari.data.AppSetting("travel_place", "trip-place"))
        }
        val model = MishwariViewModel(application)
        compose.activity.setContent { MishwariRoot(model, 0, null, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithTag("navigation_2").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("إنهاء المشوار").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("إنهاء المشوار").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { MishwariDatabase.get(application).dao().getSetting("travel_place") == "" } }
        runBlocking {
            val dao = MishwariDatabase.get(application).dao()
            assertEquals("طلب الرحلة", dao.allErrands().single().title)
            assertEquals(true, dao.allPlaces().single { it.id == "trip-place" }.monitored)
        }
        // Persisting the setting happens on IO before the ViewModel resumes on main to enqueue work.
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            org.junit.Assert.assertNull(model.error.value)
            androidx.work.WorkManager.getInstance(application)
                .getWorkInfosForUniqueWork("mishwari-geo-reconcile").get().isNotEmpty()
        }
    }

    private fun assertTrueDatabaseIsEmpty() = runBlocking {
        val dao = MishwariDatabase.get(application).dao()
        assertEquals(emptyList<ye.mishwari.data.Errand>(), dao.allErrands())
    }
}
