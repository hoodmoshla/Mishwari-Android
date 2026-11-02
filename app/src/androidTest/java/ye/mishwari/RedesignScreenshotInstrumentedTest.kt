package ye.mishwari

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ye.mishwari.data.Errand
import ye.mishwari.data.AppSetting
import ye.mishwari.data.MishwariDatabase
import ye.mishwari.data.Place
import java.io.File
import java.io.FileOutputStream

/** Captures real app windows on CI emulators; it never requests permissions or calls an AI provider. */
@RunWith(AndroidJUnit4::class)
class RedesignScreenshotInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val savedSettings = mutableMapOf<String, String?>()

    @Test(timeout = 180_000)
    fun capturesFiveTabsPlacesAndErrandEditor() {
        try {
            removeFixtureRows()
            seedFixtureRows()

            scenario = ActivityScenario.launch(MainActivity::class.java)
            waitForText("أهلاً بك في مشواري")
            waitForText("شراء قهوة وحليب")
            compose.onNodeWithTag("navigation_0").performClick()
            capture("01-home.png")

            compose.onNodeWithTag("navigation_1").performClick()
            waitForTag("errandList")
            compose.onNodeWithTag("errandList").performScrollToNode(hasText("شراء قهوة وحليب"))
            compose.onNodeWithText("شراء قهوة وحليب").assertIsDisplayed()
            capture("02-errands.png")

            compose.onNodeWithTag("navigation_2").performClick()
            waitForTag("travelList")
            compose.onNodeWithTag("travelList").assertIsDisplayed()
            compose.onNodeWithTag("travelList").performScrollToNode(hasText("شراء قهوة وحليب"))
            compose.onNodeWithText("شراء قهوة وحليب").assertIsDisplayed()
            compose.onNodeWithTag("travelList").performScrollToIndex(0)
            android.os.SystemClock.sleep(1_500) // Only lets native map drawing settle; network is not an assertion.
            capture("03-travel.png")

            compose.onNodeWithTag("navigation_3").performClick()
            waitForTag("assistantScreen")
            capture("04-assistant.png")

            compose.onNodeWithTag("navigation_4").performClick()
            waitForTag("assistantSettingsSection")
            capture("05-settings.png")

            compose.onNodeWithText("إدارة الأماكن والدليل").performScrollTo().performClick()
            waitForTag("placesList")
            compose.onNodeWithTag("placesList").assertIsDisplayed()
            compose.onNodeWithTag("placesList").performScrollToNode(hasText("سوق القاع"))
            compose.onNodeWithText("سوق القاع").assertIsDisplayed()
            capture("06-places.png")

            compose.onNodeWithTag("navigation_1").performClick()
            waitForTag("errandList")
            compose.onNodeWithText("+ إضافة طلب", useUnmergedTree = true).performClick()
            waitForText("اسم الغرض أو المهمة *")
            compose.onNodeWithText("اسم الغرض أو المهمة *").assertIsDisplayed()
            capture("07-errand-editor.png")
            compose.onNodeWithText("إلغاء").performClick()
            runBlocking { MishwariDatabase.get(context).dao().putSetting(AppSetting("theme_mode", "dark")) }
            compose.onNodeWithTag("navigation_0").performClick()
            waitForTag("themeDark")
            capture("08-home-dark.png")
        } finally {
            scenario?.close()
            scenario = null
        }
    }

    @After
    fun cleanUpFixtureRows() {
        runBlocking {
            val database = MishwariDatabase.get(context)
            val dao = database.dao()
            savedSettings.forEach { (key, value) ->
                if (value == null) database.openHelper.writableDatabase.execSQL("DELETE FROM app_settings WHERE `key` = ?", arrayOf(key))
                else dao.putSetting(AppSetting(key, value))
            }
            savedSettings.clear()
        }
        removeFixtureRows()
    }

    private fun seedFixtureRows() = runBlocking {
        val dao = MishwariDatabase.get(context).dao()
        listOf("travel_place", "theme_mode").forEach { key -> savedSettings[key] = dao.getSetting(key) }
        dao.putSetting(AppSetting("travel_place", PLACE_ID))
        dao.putSetting(AppSetting("theme_mode", "light"))
        dao.upsertPlace(
            Place(
                id = PLACE_ID,
                name = "سوق القاع",
                kind = "custom",
                governorate = "أمانة العاصمة",
                district = "التحرير",
                area = "صنعاء",
                detail = FIXTURE_MARKER,
                latitude = 15.3550,
                longitude = 44.2075,
                radiusMeters = 350f,
                monitored = false,
            )
        )
        dao.upsertErrands(
            listOf(
                Errand(
                    id = ERRAND_COFFEE_ID,
                    title = "شراء قهوة وحليب",
                    description = "مستلزمات منزلية تجريبية",
                    placeId = PLACE_ID,
                    category = "مشتريات",
                    quantity = "2",
                    expectedPrice = "4500",
                    notes = FIXTURE_MARKER,
                    priority = 2,
                    dueDate = "2026-11-04",
                    createdAt = 1_798_000_000_000L,
                ),
                Errand(
                    id = ERRAND_MEDICINE_ID,
                    title = "شراء دواء للبرد",
                    description = "طلب تجريبي للقطة",
                    placeId = PLACE_ID,
                    category = "أدوية",
                    quantity = "1",
                    expectedPrice = "1200",
                    notes = FIXTURE_MARKER,
                    priority = 1,
                    createdAt = 1_798_000_001_000L,
                ),
                Errand(
                    id = ERRAND_COMPLETED_ID,
                    title = "شراء ماء للشرب",
                    description = "طلب تجريبي مكتمل",
                    placeId = PLACE_ID,
                    category = "مشتريات",
                    quantity = "3",
                    expectedPrice = "900",
                    actualPrice = "850",
                    notes = FIXTURE_MARKER,
                    priority = 0,
                    status = "مكتمل",
                    createdAt = 1_798_000_002_000L,
                ),
            )
        )
    }

    /** Only rows carrying this test's reserved IDs and marker may be removed; the app database is retained. */
    private fun removeFixtureRows() = runBlocking {
        val dao = MishwariDatabase.get(context).dao()
        val fixtureIds = setOf(ERRAND_COFFEE_ID, ERRAND_MEDICINE_ID, ERRAND_COMPLETED_ID)
        val errands = dao.allErrands()
        val places = dao.allPlaces()
        val fixturePlace = places.firstOrNull { it.id == PLACE_ID }
        val fixtureErrands = errands.filter { it.id in fixtureIds }

        check(fixtureErrands.all { it.notes == FIXTURE_MARKER }) {
            "Reserved screenshot-test errand ID belongs to an unmarked row; refusing to alter it"
        }
        check(fixturePlace == null || fixturePlace.detail == FIXTURE_MARKER) {
            "Reserved screenshot-test place ID belongs to an unmarked row; refusing to alter it"
        }
        check(errands.none { it.placeId == PLACE_ID && it.id !in fixtureIds }) {
            "Another errand references the screenshot-test place; refusing to alter it"
        }

        fixtureErrands.forEach { dao.deleteErrand(it.id) }
        if (fixturePlace != null) dao.deletePlace(PLACE_ID)
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(fileName: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "UiAutomation.takeScreenshot() returned no frame for $fileName"
        }
        val directory = File(context.cacheDir, "redesign-screenshots")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create screenshot directory: $directory" }
        val output = File(directory, fileName)
        try {
            FileOutputStream(output).use { stream ->
                assertTrue("Could not encode screenshot $fileName", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            assertTrue("Screenshot is empty: $output", output.isFile && output.length() > 0L)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val PLACE_ID = "redesign-screenshot-fixture-place"
        const val ERRAND_COFFEE_ID = "redesign-screenshot-fixture-coffee"
        const val ERRAND_MEDICINE_ID = "redesign-screenshot-fixture-medicine"
        const val ERRAND_COMPLETED_ID = "redesign-screenshot-fixture-completed"
        const val FIXTURE_MARKER = "redesign-screenshot-instrumented-test-fixture"
    }
}
