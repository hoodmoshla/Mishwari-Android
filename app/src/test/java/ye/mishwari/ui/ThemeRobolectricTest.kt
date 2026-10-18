package ye.mishwari.ui

import android.content.res.Configuration
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "notnight")
class ThemeRobolectricTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun followsSystemLightModeAndProvidesSemanticTheme() {
        var primary = Color.Unspecified
        compose.activity.setContent {
            MishwariTheme {
                val currentPrimary = MaterialTheme.colorScheme.primary
                SideEffect { primary = currentPrimary }
                Text("اختبار الثيم")
            }
        }

        compose.onNodeWithTag("themeLight").assertIsDisplayed()
        compose.onNodeWithText("اختبار الثيم").assertIsDisplayed()
        compose.runOnIdle { assertEquals(Color(0xFF176B5B), primary) }
    }
    @Test
    fun reactsToSystemAppearanceChangeWithoutReopeningScreen() {
        val config = mutableStateOf(Configuration(compose.activity.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        })
        compose.activity.setContent {
            CompositionLocalProvider(LocalConfiguration provides config.value) {
                MishwariTheme { Text("يتبع الهاتف") }
            }
        }
        compose.onNodeWithTag("themeLight").assertIsDisplayed()
        compose.runOnIdle {
            config.value = Configuration(config.value).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
        }
        compose.onNodeWithTag("themeDark").assertIsDisplayed()
        compose.runOnIdle {
            config.value = Configuration(config.value).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
            }
        }
        compose.onNodeWithTag("themeLight").assertIsDisplayed()
    }

}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "night")
class ThemeDarkRobolectricTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun followsSystemDarkModeAndProvidesSemanticTheme() {
        var primary = Color.Unspecified
        compose.activity.setContent {
            MishwariTheme {
                val currentPrimary = MaterialTheme.colorScheme.primary
                SideEffect { primary = currentPrimary }
                Text("اختبار الوضع الداكن")
            }
        }

        compose.onNodeWithTag("themeDark").assertIsDisplayed()
        compose.onNodeWithText("اختبار الوضع الداكن").assertIsDisplayed()
        compose.runOnIdle { assertEquals(Color(0xFF8DD5BF), primary) }
    }
}
