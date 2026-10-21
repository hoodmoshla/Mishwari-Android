package ye.mishwari.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ye.mishwari.data.Errand
import ye.mishwari.data.Place

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp")
class EditorsLocationReadinessRobolectricTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val noCoords = Place(id = "no-coords", name = "مكان بلا إحداثيات", monitored = false)

    @Test fun placeSelectorExplainsNoCoordinatesAndMonitoringOff() {
        var selected by mutableStateOf<String?>(noCoords.id)
        compose.setContent {
            MishwariTheme {
                PlaceSelector("الوجهة", selected, listOf(noCoords), { selected = it })
            }
        }
        compose.onNodeWithText("جاهزية تنبيه الوصول", substring = true).assertIsDisplayed()
        compose.onNodeWithText("المراقبة متوقفة", substring = true).assertIsDisplayed()
        compose.onNodeWithText("لا توجد إحداثيات صالحة", substring = true).assertIsDisplayed()
    }

    @Test fun placeEditorKeepsCaptureCollapsedUntilUserOpensOptionalLocationSettings() {
        compose.setContent {
            MishwariTheme { PlaceEditor(noCoords, listOf(noCoords), {}, {}) }
        }
        compose.onNodeWithText("لا توجد إحداثيات صالحة", substring = true).assertIsDisplayed()
        compose.onNodeWithText("التقاط موقعي الحالي للمراجعة").assertDoesNotExist()
        compose.onNodeWithText("إعدادات الموقع والتنبيه الاختيارية").performClick()
        compose.onNodeWithText("التقاط موقعي الحالي للمراجعة").assertIsDisplayed()
    }

    @Test fun errandDestinationShowsThatMonitoringIsOffWithoutPromisingAlerts() {
        val errand = Errand(id = "errand", title = "طلب اختبار", placeId = noCoords.id)
        compose.setContent {
            MishwariTheme { ErrandEditor(errand, listOf(noCoords), {}, {}, {}, {}) }
        }
        compose.onNodeWithText("الوجهة: ${placeLabel(noCoords)}").assertIsDisplayed()
        compose.onNodeWithText("التنبيه غير مفعّل", substring = true).assertIsDisplayed()
    }
}
