package ye.mishwari

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ye.mishwari.data.AppSetting
import ye.mishwari.data.Errand
import ye.mishwari.data.MishwariDatabase
import ye.mishwari.data.Place
import ye.mishwari.ui.MishwariRoot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp")
class RootUxRobolectricTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    private val errandIds = setOf("root-ux-active", "root-ux-second", "root-ux-done", "root-ux-trip", "root-ux-reopen-target", "root-ux-reopen-unlinked")
    private val placeIds = setOf("root-ux-governorate", "root-ux-district", "root-ux-coordinate-directory", "root-ux-trip-place", "root-ux-reopen-place")
    private val tempTitles = setOf("مهمة واجهة اختبار", "طلب بحث أول", "طلب بحث ثان", "طلب بحث مكتمل", "طلب رحلة اختبار", "مهمة واجهة صغيرة", "حليب دفعة اختبار", "خبز دفعة اختبار", "طلب إعادة فتح اختبار", "طلب غير مرتبط اختبار")
    private val tempPlaceNames = setOf("محافظة واجهة اختبار", "مديرية واجهة اختبار", "مديرية ذات إحداثيات", "وجهة رحلة اختبار", "سوق واجهة مخصص", "سوق إعادة اختبار")

    @Before
    fun initializeTestDatabaseAndWorkManager() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(application)
        cleanTestRows()
    }

    @After
    fun cleanTestRowsAfterTest() = cleanTestRows()

    private fun cleanTestRows() = runBlocking {
        val dao = MishwariDatabase.get(application).dao()
        dao.allErrands().filter { it.id in errandIds || it.title in tempTitles }.forEach { dao.deleteErrand(it.id) }
        dao.allPlaces().filter { it.id in placeIds || it.name in tempPlaceNames }
            .sortedByDescending { it.parentId != null }.forEach { dao.deletePlace(it.id) }
        if (dao.getSetting("travel_place") == "root-ux-trip-place") dao.putSetting(AppSetting("travel_place", ""))
    }

    private fun showRoot(model: MishwariViewModel) {
        compose.activity.setContent {
            MishwariRoot(model, 0, null, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }

    private fun openRequests() = compose.onNodeWithTag("navigation_1").performClick()

    @Test
    fun homeAndAssistantUseTheCentralAiSettingsSection() {
        showRoot(MishwariViewModel(application))
        compose.onNodeWithText("أهلاً بك في مشواري").assertIsDisplayed()
        compose.onNodeWithTag("navigation_4").performClick()
        compose.onNodeWithTag("assistantSettingsSection").assertIsDisplayed()
        compose.onNodeWithTag("assistantSettingsKey").assertIsDisplayed()

        compose.onNodeWithTag("navigation_3").performClick()
        compose.onNodeWithTag("assistantScreen").assertIsDisplayed()
        compose.onNodeWithTag("assistantSettingsKey").assertDoesNotExist()
        compose.onNodeWithText("إعدادات الذكاء الاصطناعي").performClick()
        compose.onNodeWithTag("assistantSettingsSection").assertIsDisplayed()
        compose.onNodeWithText("نسخة احتياطية محلية").performScrollTo().performClick()
        compose.onNodeWithTag("backupDisclosure").assertTextContains("إحداثيات GPS", substring = true)
        compose.onNodeWithTag("backupDisclosure").assertTextContains("صور الطلبات", substring = true)
    }

    @Test
    fun multiLineShoppingListIsReviewedBeforeOneBatchSave() {
        showRoot(MishwariViewModel(application))
        openRequests()
        compose.onNodeWithText("إدخال قائمة متعددة الأسطر").performClick()
        compose.onNodeWithText("أدخل عنوانًا في كل سطر").performTextInput("حليب دفعة اختبار\nخبز دفعة اختبار")
        compose.onNodeWithText("حفظ الدفعة").assertIsDisplayed()
        compose.onNodeWithText("حفظ الدفعة").performClick()
        compose.waitUntil(10_000) {
            runBlocking {
                MishwariDatabase.get(application).dao().allErrands().map { it.title }.toSet()
                    .containsAll(setOf("حليب دفعة اختبار", "خبز دفعة اختبار"))
            }
        }
        assertEquals(2, runBlocking {
            MishwariDatabase.get(application).dao().allErrands().count { it.title in setOf("حليب دفعة اختبار", "خبز دفعة اختبار") }
        })
    }

    @Test
    fun completeHideFilterAndReopenErrandFromItsAccessibleCheckbox() {
        runBlocking {
            MishwariDatabase.get(application).dao().upsertErrand(
                Errand(id = "root-ux-active", title = "مهمة واجهة اختبار")
            )
        }
        val model = MishwariViewModel(application)
        showRoot(model)
        openRequests()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("مهمة واجهة اختبار").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("errandCheckbox_root-ux-active").performClick()
        compose.waitUntil(10_000) {
            runBlocking { MishwariDatabase.get(application).dao().allErrands().single().status == "مكتمل" } &&
                compose.onAllNodesWithText("مهمة واجهة اختبار").fetchSemanticsNodes().isEmpty()
        }
        compose.waitUntil(6_000) { compose.onAllNodesWithText("تم تحديث حالة الطلب").fetchSemanticsNodes().isEmpty() }

        compose.onNodeWithText("الكل").performClick()
        compose.onNodeWithText("مهمة واجهة اختبار").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("errandCheckbox_root-ux-active").assertIsOn()
        compose.onNodeWithTag("errandCheckbox_root-ux-active").performClick()
        compose.waitUntil(10_000) {
            val checkboxIsUnchecked = runCatching { compose.onNodeWithTag("errandCheckbox_root-ux-active").assertIsOff() }.isSuccess
            checkboxIsUnchecked || model.error.value != null
        }
        assertTrue("Reopening failed: ${model.error.value}", model.error.value == null)
        assertEquals("جديد", runBlocking { MishwariDatabase.get(application).dao().allErrands().single().status })
        compose.onNodeWithText("لم تُكتمل العملية").assertDoesNotExist()
        compose.onNodeWithTag("errandList").performScrollToNode(hasText("مهمة واجهة اختبار"))
        compose.onNodeWithText("مهمة واجهة اختبار").assertIsDisplayed()
        assertEquals("جديد", runBlocking { MishwariDatabase.get(application).dao().allErrands().single().status })
    }

    @Test
    fun searchClearAndEmptyStateActionResetSearchAndStatusFilters() {
        runBlocking {
            MishwariDatabase.get(application).dao().upsertErrands(listOf(
                Errand(id = "root-ux-active", title = "طلب بحث أول"),
                Errand(id = "root-ux-second", title = "طلب بحث ثان"),
                Errand(id = "root-ux-done", title = "طلب بحث مكتمل", status = "مكتمل"),
            ))
        }
        showRoot(MishwariViewModel(application))
        openRequests()
        compose.onNodeWithText("ابحث عن طلب أو وجهة").performTextInput("ثان")
        compose.onNodeWithText("طلب بحث ثان").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("طلب بحث أول").assertDoesNotExist()

        compose.onNodeWithText("مسح").performClick()
        compose.onNodeWithText("طلب بحث أول").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("الكل").performClick()
        compose.onNodeWithTag("errandList").performScrollToNode(hasText("طلب بحث مكتمل"))
        compose.onNodeWithText("طلب بحث مكتمل").assertIsDisplayed()
        compose.onNodeWithTag("errandList").performScrollToIndex(0)
        compose.onNodeWithText("ابحث عن طلب أو وجهة").performTextInput("غير موجود")
        compose.onNodeWithText("مسح البحث والمرشّحات").performScrollTo().performClick()
        compose.onNodeWithText("طلب بحث أول").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("errandList").performScrollToNode(hasText("طلب بحث مكتمل"))
        compose.onNodeWithText("طلب بحث مكتمل").assertIsDisplayed()
    }

    @Test
    fun governorateDirectoryBrowseBackAndCustomPlaceCanBeAdded() {
        runBlocking {
            MishwariDatabase.get(application).dao().upsertPlaces(listOf(
                Place(id = "root-ux-governorate", name = "محافظة واجهة اختبار", kind = "governorate"),
                Place(id = "root-ux-district", name = "مديرية واجهة اختبار", parentId = "root-ux-governorate", kind = "district", governorate = "محافظة واجهة اختبار"),
                Place(id = "root-ux-coordinate-directory", name = "مديرية ذات إحداثيات", parentId = "root-ux-governorate", kind = "district", governorate = "محافظة واجهة اختبار", district = "مديرية ذات إحداثيات", latitude = 15.5, longitude = 44.2, monitored = true),
            ))
        }
        showRoot(MishwariViewModel(application))
        compose.onNodeWithTag("navigation_4").performClick()
        compose.onNodeWithText("إدارة الأماكن والدليل").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("ابحث في الأماكن").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("دليل المحافظات").performClick()
        compose.onNodeWithTag("placesList").performScrollToNode(hasText("محافظة واجهة اختبار"))
        compose.onNodeWithText("محافظة واجهة اختبار").assertIsDisplayed()
        compose.onNodeWithTag("browsePlace_root-ux-governorate").performClick()
        compose.onNodeWithTag("placesList").performScrollToNode(hasText("مديرية واجهة اختبار"))
        compose.onNodeWithText("مديرية واجهة اختبار").assertIsDisplayed()
        compose.onNodeWithText("رجوع · محافظة واجهة اختبار").performClick()
        compose.onNodeWithTag("placesList").performScrollToNode(hasText("محافظة واجهة اختبار"))
        compose.onNodeWithText("محافظة واجهة اختبار").assertIsDisplayed()

        compose.onNodeWithTag("placesList").performScrollToIndex(0)
        compose.onNodeWithText("أماكني").performClick()
        compose.onNodeWithText("مديرية ذات إحداثيات").assertDoesNotExist()
        compose.onNodeWithText("+ إضافة مكان", useUnmergedTree = true).performClick()
        compose.onNodeWithText("اسم السوق / المحل / المكان *").performTextInput("سوق واجهة مخصص")
        compose.onNodeWithText("حفظ").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("حفظ").fetchSemanticsNodes().isEmpty() &&
                runBlocking { MishwariDatabase.get(application).dao().allPlaces().any { it.name == "سوق واجهة مخصص" } }
        }
        compose.onNodeWithText("سوق واجهة مخصص").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun reopeningPlaceRequestsRequiresConfirmationAndOnlyUpdatesLinkedRows() {
        runBlocking {
            val dao = MishwariDatabase.get(application).dao()
            dao.upsertPlace(Place(id = "root-ux-reopen-place", name = "سوق إعادة اختبار", kind = "custom"))
            dao.upsertErrands(listOf(
                Errand(id = "root-ux-reopen-target", title = "طلب إعادة فتح اختبار", placeId = "root-ux-reopen-place", status = "مكتمل"),
                Errand(id = "root-ux-reopen-unlinked", title = "طلب غير مرتبط اختبار", status = "مكتمل"),
            ))
        }
        showRoot(MishwariViewModel(application))
        compose.onNodeWithTag("navigation_4").performClick()
        compose.onNodeWithText("إدارة الأماكن والدليل").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("ابحث في الأماكن").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("placesList").performScrollToNode(hasText("سوق إعادة اختبار"))
        compose.onNodeWithText("التفاصيل").performClick()
        compose.onNodeWithTag("reopenPlace_root-ux-reopen-place").performScrollTo().performClick()
        compose.onNodeWithText("إعادة فتح الطلبات المكتملة؟").assertIsDisplayed()
        compose.onNodeWithText("سيُعاد فتح 1 طلبًا مرتبطًا بـ«سوق إعادة اختبار» بتحديث سجلاته الأصلية فقط، دون إنشاء نسخ مكررة.").assertIsDisplayed()
        compose.onNodeWithText("إلغاء").performClick()
        assertEquals("مكتمل", runBlocking {
            MishwariDatabase.get(application).dao().allErrands().single { it.id == "root-ux-reopen-target" }.status
        })

        compose.onNodeWithTag("reopenPlace_root-ux-reopen-place").performClick()
        compose.onNodeWithText("متابعة").performClick()
        compose.waitUntil(10_000) {
            runBlocking {
                val rows = MishwariDatabase.get(application).dao().allErrands()
                rows.single { it.id == "root-ux-reopen-target" }.status == "جديد" &&
                    rows.single { it.id == "root-ux-reopen-unlinked" }.status == "مكتمل"
            }
        }
    }

    @Test
    fun travelDestinationReviewActivationAndFinishWorkWithoutCoordinates() {
        runBlocking {
            val dao = MishwariDatabase.get(application).dao()
            dao.upsertPlace(Place(id = "root-ux-trip-place", name = "وجهة رحلة اختبار"))
            dao.upsertErrand(Errand(id = "root-ux-trip", title = "طلب رحلة اختبار", placeId = "root-ux-trip-place"))
        }
        showRoot(MishwariViewModel(application))
        compose.onNodeWithTag("navigation_2").performClick()
        compose.onNodeWithText("وجهة المشوار: دون وجهة").performScrollTo().performClick()
        compose.onNodeWithText("ابحث في جميع الأماكن").performTextInput("وجهة رحلة اختبار")
        compose.onAllNodesWithText("وجهة رحلة اختبار").onLast().performClick()
        compose.onNodeWithText("طلب رحلة اختبار").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("travelList").performScrollToNode(hasText("تجهيز المشوار والتنبيهات"))
        compose.onNodeWithText("تجهيز المشوار والتنبيهات").performClick()
        compose.waitUntil(10_000) {
            runBlocking { MishwariDatabase.get(application).dao().getSetting("travel_place") == "root-ux-trip-place" }
        }
        compose.onNodeWithTag("travelList").performScrollToNode(hasText("إنهاء المشوار"))
        compose.onNodeWithText("إنهاء المشوار").performClick()
        compose.waitUntil(10_000) {
            runBlocking { MishwariDatabase.get(application).dao().getSetting("travel_place") == "" }
        }
        assertEquals("طلب رحلة اختبار", runBlocking { MishwariDatabase.get(application).dao().allErrands().single().title })
    }

    @Test
    fun addErrandControlsStayWithinSmallRtlRootAtLargeFontScales() {
        val fontScale = mutableFloatStateOf(1.5f)
        val model = MishwariViewModel(application)
        compose.activity.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale.floatValue)) {
                MishwariRoot(model, 0, null, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("navigation_1").performClick()

        listOf(1.5f, 2f).forEach { scale ->
            compose.runOnIdle { fontScale.floatValue = scale }
            compose.onNodeWithText("+ إضافة طلب", useUnmergedTree = true).performClick()
            compose.onNodeWithText("اسم الغرض أو المهمة *").performTextInput("مهمة واجهة صغيرة")
            compose.onNodeWithText("إلغاء").assertIsDisplayed()
            compose.onNodeWithText("حفظ").assertIsDisplayed()

            val rootBounds = generateSequence(compose.onNodeWithText("حفظ").fetchSemanticsNode()) { it.parent }.last().boundsInRoot
            val cancelBounds = compose.onNodeWithText("إلغاء").fetchSemanticsNode().boundsInRoot
            val saveBounds = compose.onNodeWithText("حفظ").fetchSemanticsNode().boundsInRoot
            assertTrue("small root must have valid bounds", rootBounds.width > 0f && rootBounds.height > 0f)
            listOf(cancelBounds, saveBounds).forEach { bounds ->
                assertTrue("dialog action must not extend beyond root at fontScale=$scale: $bounds vs $rootBounds",
                    bounds.left >= rootBounds.left && bounds.top >= rootBounds.top &&
                        bounds.right <= rootBounds.right && bounds.bottom <= rootBounds.bottom)
            }
            compose.onNodeWithText("إلغاء").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("إلغاء").fetchSemanticsNodes().isEmpty() }
            assertTrue(runBlocking { MishwariDatabase.get(application).dao().allErrands().none { it.title == "مهمة واجهة صغيرة" } })
        }
    }
}
