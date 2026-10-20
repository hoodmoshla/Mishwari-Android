package ye.mishwari

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ye.mishwari.data.Errand
import ye.mishwari.data.MishwariDatabase
import ye.mishwari.data.Place
import ye.mishwari.ui.MishwariRoot
import ye.mishwari.ui.errandDisplayOrder
import ye.mishwari.ui.nextAlertPermission

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp")
class PermissionUxRobolectricTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun initializeFixturesAndPermissions() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(application)
        cleanFixtures()
        shadowOf(application).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @After
    fun removeFixturesAndResetPermissions() {
        cleanFixtures()
        shadowOf(application).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    private fun cleanFixtures() = runBlocking {
        val dao = MishwariDatabase.get(application).dao()
        dao.allErrands().filter { it.id == ALERT_ERRAND_ID || it.title == ALERT_ERRAND_TITLE }
            .forEach { dao.deleteErrand(it.id) }
        dao.allPlaces().filter { it.id == ALERT_PLACE_ID || it.name == ALERT_PLACE_NAME }
            .forEach { dao.deletePlace(it.id) }
    }

    private fun addAlertCandidate() = runBlocking {
        val dao = MishwariDatabase.get(application).dao()
        dao.upsertPlace(
            Place(
                id = ALERT_PLACE_ID,
                name = ALERT_PLACE_NAME,
                latitude = 24.7,
                longitude = 46.7,
                monitored = true,
            ),
        )
        dao.upsertErrand(
            Errand(
                id = ALERT_ERRAND_ID,
                title = ALERT_ERRAND_TITLE,
                placeId = ALERT_PLACE_ID,
            ),
        )
    }

    private fun showRoot(
        revision: androidx.compose.runtime.MutableIntState,
        requestLocation: () -> Unit = {},
        requestNotifications: () -> Unit = {},
    ) {
        val model = MishwariViewModel(application)
        compose.activity.setContent {
            MishwariRoot(
                model = model,
                permissionRevision = revision.intValue,
                deepLinkPlace = null,
                consumeDeepLink = {},
                requestLocation = requestLocation,
                requestNotifications = requestNotifications,
                openAppSettings = {},
                openLocationSettings = {},
                exportBackup = {},
                importBackup = {},
                importDirectory = {},
                selectPhoto = {},
            )
        }
    }

    private fun openSettings() {
        // The top-bar title and bottom-navigation label share the same text; choose the last match.
        compose.onAllNodesWithText("الإعدادات").onLast().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("permissionSummary").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("الموقع اختياري · غير مسموح").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openPermissionSection() {
        compose.onNodeWithText("الأذونات والخصوصية").performClick()
    }

    @Test
    fun settingsStartCollapsedShowSummaryAndDoNotRequestPermissionsOnOpen() {
        var locationRequests = 0
        var notificationRequests = 0
        val revision = mutableIntStateOf(0)
        showRoot(revision, { locationRequests++ }, { notificationRequests++ })
        openSettings()

        compose.onNodeWithTag("permissionSummary").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("الموقع اختياري · غير مسموح").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("مراجعة إذن الموقع").assertDoesNotExist()
        compose.onNodeWithText("مراجعة الإشعارات").assertDoesNotExist()
        compose.onNodeWithText("إعادة تهيئة تنبيهات الوصول").assertDoesNotExist()
        assertEquals(0, locationRequests)
        assertEquals(0, notificationRequests)

        openPermissionSection()
        compose.onNodeWithText("مراجعة إذن الموقع").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("مراجعة الإشعارات").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("إعداد تنبيهات الخلفية").assertDoesNotExist()

        // A fine-location grant alone must not reveal background setup without a real alert candidate.
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        compose.runOnIdle { revision.intValue++ }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("مراجعة إذن الموقع").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("إعداد تنبيهات الخلفية").assertDoesNotExist()
        compose.onNodeWithTag("permissionSummary").performScrollTo().assertIsDisplayed()
        assertEquals(0, locationRequests)
        assertEquals(0, notificationRequests)
    }

    @Test
    fun permissionRevisionRefreshesSummaryAndBackgroundActionNeedsCandidateAndFineLocation() {
        addAlertCandidate()
        val revision = mutableIntStateOf(0)
        showRoot(revision)
        openSettings()
        openPermissionSection()

        // GeoPolicy has a monitored, coordinate-bearing place with an active errand, but fine is denied.
        compose.onNodeWithText("مراجعة إذن الموقع").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("إعداد تنبيهات الخلفية").assertDoesNotExist()
        compose.onNodeWithText("الموقع اختياري · غير مسموح").performScrollTo().assertIsDisplayed()

        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        compose.runOnIdle { revision.intValue++ }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("مراجعة إذن الموقع").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("إعداد تنبيهات الخلفية").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("إعداد تنبيهات الخلفية").performScrollTo().assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText("الموقع جاهز").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("إذن الموقع ممنوح · موقع الهاتف مغلق").fetchSemanticsNodes().isNotEmpty(),
        )

        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        compose.runOnIdle { revision.intValue++ }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("مراجعة إذن الموقع").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("إعداد تنبيهات الخلفية").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("الموقع اختياري · غير مسموح").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun permissionExplanationWaitsForConfirmationAndNotNowKeepsListsAccessible() {
        var locationRequests = 0
        val revision = mutableIntStateOf(0)
        showRoot(revision, requestLocation = { locationRequests++ })
        openSettings()
        openPermissionSection()

        compose.onNodeWithText("مراجعة إذن الموقع").performScrollTo().performClick()
        compose.onAllNodesWithText("الموقع أثناء الاستخدام").onLast().assertIsDisplayed()
        compose.onNodeWithTag("permissionExplanationBody").performScrollTo().assertIsDisplayed()
            .assertTextContains("يفيد الموقع الدقيق في إعداد تنبيه وصول صحيح لأماكنك. سنطلب الموقع أثناء الاستخدام فقط، وليس في الخلفية. إذا رفضت، تبقى الطلبات والأماكن والنسخ الاحتياطي متاحة. إذا سبق رفض الإذن نهائيًا، ستفتح إعدادات التطبيق بدل تكرار الطلب.")
        assertEquals("opening the explanation must not launch a runtime permission request", 0, locationRequests)

        compose.onNodeWithText("ليس الآن").performClick()
        compose.onNodeWithText("مراجعة إذن الموقع").performScrollTo().assertIsDisplayed()
        assertEquals(0, locationRequests)
        compose.onAllNodesWithText("طلباتي").onLast().performClick()
        compose.onNodeWithText("ابحث عن طلب أو وجهة").assertIsDisplayed()

        // Return to settings and verify that only the explicit confirmation invokes the request callback.
        openSettings()
        if (compose.onAllNodesWithText("مراجعة إذن الموقع").fetchSemanticsNodes().isEmpty()) {
            openPermissionSection()
        }
        compose.onNodeWithText("مراجعة إذن الموقع").performScrollTo().performClick()
        assertEquals(0, locationRequests)
        compose.onNodeWithText("متابعة").performClick()
        compose.waitUntil(5_000) { locationRequests == 1 }
        assertEquals(1, locationRequests)
    }

    @Test
    fun nextAlertPermissionSelectsOnlyTheNextMissingRequirementInOrder() {
        fun state(
            fine: Boolean = false,
            notifications: Boolean = false,
            background: Boolean = false,
        ) = GeoEnvironmentSignature(
            fineLocationGranted = fine,
            coarseLocationGranted = fine,
            backgroundLocationGranted = background,
            notificationPermissionGranted = notifications,
            appNotificationsEnabled = true,
            geoChannelImportance = android.app.NotificationManager.IMPORTANCE_DEFAULT,
            locationEnabled = true,
        )

        assertEquals("foreground", nextAlertPermission(state()))
        assertEquals("notifications", nextAlertPermission(state(fine = true)))
        assertEquals("background", nextAlertPermission(state(fine = true, notifications = true)))
        assertEquals(null, nextAlertPermission(state(fine = true, notifications = true, background = true)))
    }

    @Test
    fun errandDisplayOrderPutsActiveFirstThenPriorityAndDueDate() {
        val errands = listOf(
            Errand(id = "done", title = "done", status = "مكتمل", priority = 2, dueDate = "2026-01-01"),
            Errand(id = "active-normal", title = "active-normal", priority = 1, dueDate = "2026-01-01"),
            Errand(id = "cancelled", title = "cancelled", status = "ملغى", priority = 2, dueDate = "2025-01-01"),
            Errand(id = "active-high-late", title = "active-high-late", priority = 2, dueDate = "2026-09-01"),
            Errand(id = "active-high-soon", title = "active-high-soon", priority = 2, dueDate = "2026-02-01"),
        )

        assertEquals(
            listOf("active-high-soon", "active-high-late", "active-normal", "cancelled", "done"),
            errands.sortedWith(errandDisplayOrder).map { it.id },
        )
    }

    companion object {
        private const val ALERT_PLACE_ID = "permission-ux-alert-place"
        private const val ALERT_PLACE_NAME = "وجهة تنبيه اختبار الأذونات"
        private const val ALERT_ERRAND_ID = "permission-ux-alert-errand"
        private const val ALERT_ERRAND_TITLE = "طلب تنبيه اختبار الأذونات"
    }
}
