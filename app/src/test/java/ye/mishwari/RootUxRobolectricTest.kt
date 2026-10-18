package ye.mishwari

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

    private val errandIds = setOf("root-ux-active", "root-ux-second", "root-ux-done", "root-ux-trip")
    private val placeIds = setOf("root-ux-governorate", "root-ux-district", "root-ux-trip-place")
    private val tempTitles = setOf("مهمة واجهة اختبار", "طلب بحث أول", "طلب بحث ثان", "طلب بحث مكتمل", "طلب رحلة اختبار", "مهمة واجهة صغيرة")
    private val tempPlaceNames = setOf("محافظة واجهة اختبار", "مديرية واجهة اختبار", "وجهة رحلة اختبار", "سوق واجهة مخصص")

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

    @Test
    fun completeHideFilterAndReopenErrandFromItsAccessibleCheckbox() {
        runBlocking {
            MishwariDatabase.get(application).dao().upsertErrand(
                Errand(id = "root-ux-active", title = "مهمة واجهة اختبار")
            )
        }
        showRoot(MishwariViewModel(application))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("مهمة واجهة اختبار").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("إكمال الطلب: مهمة واجهة اختبار").performClick()
        compose.waitUntil(10_000) {
            runBlocking { MishwariDatabase.get(application).dao().allErrands().single().status == "مكتمل" } &&
                compose.onAllNodesWithText("مهمة واجهة اختبار").fetchSemanticsNodes().isEmpty()
        }

        compose.onNodeWithText("الكل").performClick()
        compose.onNodeWithText("مهمة واجهة اختبار").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("إعادة فتح الطلب: مهمة واجهة اختبار").performClick()
        compose.waitUntil(10_000) {
            runBlocking { MishwariDatabase.get(application).dao().allErrands().single().status == "جديد" } &&
                compose.onAllNodesWithText("مهمة واجهة اختبار").fetchSemanticsNodes().isNotEmpty()
        }
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
            ))
        }
        showRoot(MishwariViewModel(application))
        compose.onNodeWithText("الأماكن").performClick()
        compose.onNodeWithText("دليل المحافظات").performClick()
        compose.onNodeWithText("محافظة واجهة اختبار").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("تصفح").performClick()
        compose.onNodeWithText("مديرية واجهة اختبار").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("رجوع · محافظة واجهة اختبار").performClick()
        compose.onNodeWithText("محافظة واجهة اختبار").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("أماكني").performClick()
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
    fun travelDestinationReviewActivationAndFinishWorkWithoutCoordinates() {
        runBlocking {
            val dao = MishwariDatabase.get(application).dao()
            dao.upsertPlace(Place(id = "root-ux-trip-place", name = "وجهة رحلة اختبار"))
            dao.upsertErrand(Errand(id = "root-ux-trip", title = "طلب رحلة اختبار", placeId = "root-ux-trip-place"))
        }
        showRoot(MishwariViewModel(application))
        compose.onNodeWithText("السفر").performClick()
        compose.onNodeWithText("وجهة المشوار: دون وجهة").performScrollTo().performClick()
        compose.onNodeWithText("وجهة رحلة اختبار").performScrollTo().performClick()
        compose.onNodeWithText("طلب رحلة اختبار").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("تجهيز المشوار وتفعيل السفر").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            runBlocking { MishwariDatabase.get(application).dao().getSetting("travel_place") == "root-ux-trip-place" }
        }
        compose.onNodeWithText("إنهاء وضع السفر").performScrollTo().performClick()
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
