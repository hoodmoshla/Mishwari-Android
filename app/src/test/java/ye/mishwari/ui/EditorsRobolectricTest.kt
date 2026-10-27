package ye.mishwari.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import ye.mishwari.data.Errand
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ye.mishwari.data.Place

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp")
class EditorsRobolectricTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val governorate = Place(id = "gov-sanaa", name = "صنعاء", kind = "governorate")
    private val district = Place(id = "district-maeen", name = "معين", parentId = governorate.id, kind = "district", governorate = governorate.name, district = "معين")
    private val market = Place(id = "market-central", name = "السوق المركزي", parentId = district.id, kind = "custom", governorate = governorate.name, district = district.name)
    private val orphan = Place(id = "orphan-place", name = "مكان يتيم مخصص", kind = "custom", area = "حي قديم")

    @Test
    fun drillsDownAndSelectsMarketWithoutListingHundredsOfPlaces() {
        val places = listOf(governorate, district, market, orphan)
        var selected by mutableStateOf<String?>(null)
        compose.setContent {
            MishwariTheme {
                PlaceSelector("الوجهة", selected, places, { selected = it }, allowNone = true)
            }
        }

        compose.onNodeWithText("الوجهة: دون وجهة").performClick()
        compose.onNodeWithText("صنعاء").performClick()
        compose.onNodeWithText("معين").assertIsDisplayed().performClick()
        compose.onNodeWithText("السوق المركزي").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(market.id, selected) }
    }

    @Test
    fun globalSearchFindsOrphanCustomPlaceAndCanClearSelection() {
        val places = listOf(governorate, district, market, orphan)
        var selected by mutableStateOf<String?>(market.id)
        compose.setContent {
            MishwariTheme {
                PlaceSelector("الوجهة", selected, places, { selected = it }, allowNone = true)
            }
        }

        compose.onNodeWithText("الوجهة: ${placeLabel(market)}").performClick()
        compose.onNodeWithText("ابحث في جميع الأماكن").performTextInput("مكان يتيم")
        compose.onNodeWithText("مكان يتيم مخصص").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(orphan.id, selected) }

        compose.onNodeWithText("الوجهة: ${placeLabel(orphan)}").performClick()
        compose.onNodeWithText("دون وجهة / إلغاء الاختيار").performClick()
        compose.runOnIdle { assertEquals(null, selected) }
    }

    @Test
    fun globalPlaceSearchNormalizesArabicHamzaAndDiacritics() {
        val place = Place(id = "place-search-normalized", name = "مَأْرِب", kind = "governorate")
        var selected by mutableStateOf<String?>(null)
        compose.setContent {
            MishwariTheme { PlaceSelector("الوجهة", selected, listOf(place), { selected = it }, allowNone = true) }
        }

        compose.onNodeWithText("الوجهة: دون وجهة").performClick()
        compose.onNodeWithText("ابحث في جميع الأماكن").performTextInput("مارب")
        compose.onNodeWithText("مَأْرِب").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(place.id, selected) }
    }

    @Test
    fun collapsedOptionalFieldsArePreservedOnSave() {
        val original = Errand(title = "طلب بتفاصيل", description = "وصف محفوظ", quantity = "٢",
            expectedPrice = "٣٠٠", notes = "ملاحظة محفوظة", priority = 2,
            dueDate = "2026-12-31", status = "مؤجل", photoPath = "/unused-test-photo")
        var saved: Errand? = null
        compose.setContent {
            MishwariTheme { ErrandEditor(original, emptyList(), {}, { saved = it }, {}, {}) }
        }
        compose.onNodeWithText("الوصف (اختياري)").assertDoesNotExist()
        compose.onNodeWithText("حفظ").performClick()
        compose.runOnIdle { assertEquals(original, saved) }
    }

    @Test
    fun chosenDestinationSurvivesDraftRestoration() {
        val original = Errand(title = "مسودة اختيار المكان")
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MishwariTheme { ErrandEditor(original, listOf(orphan), {}, {}, {}, {}) }
        }
        compose.onNodeWithText("الوجهة: دون وجهة").performClick()
        compose.onNodeWithText(orphan.name).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("الوجهة: ${placeLabel(orphan)}").assertIsDisplayed()
    }

}
