package ye.mishwari

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ye.mishwari.ai.AssistantKeyCipher
import ye.mishwari.ai.AssistantProvider
import ye.mishwari.ai.AssistantProviderIds
import ye.mishwari.ai.AssistantStore
import ye.mishwari.ui.AssistantSettingsSection
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp")
class AssistantSettingsRobolectricTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()
    private var model: MishwariViewModel? = null

    @After
    fun cleanup() {
        model?.viewModelScope?.cancel()
        File(application.noBackupFilesDir, "assistant").deleteRecursively()
    }

    @Test
    fun savedCredentialIsReportedWithoutRenderingItsValue() {
        File(application.noBackupFilesDir, "assistant").deleteRecursively()
        val store = AssistantStore(application, TestSettingsCipher)
        store.save(
            "https://settings.example/v1/chat/completions",
            "settings-test-model",
            FAKE_SAVED_KEY,
            enabled = true,
            provider = AssistantProviderIds.OPENAI_COMPATIBLE,
        )
        model = MishwariViewModel(application, store)

        compose.activity.setContent { AssistantSettingsSection(model = requireNotNull(model)) }

        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            requireNotNull(model).assistantConfig.value.hasKey
        }
        compose.onNodeWithText("الذكاء الاصطناعي").assertIsDisplayed()
        compose.onNodeWithTag("assistantSavedKeyStatus").assertTextContains("المفتاح محفوظ")
        compose.onNodeWithTag("assistantSettingsKey").assertIsDisplayed()
        compose.onNodeWithText(FAKE_SAVED_KEY).assertDoesNotExist()
        compose.runOnIdle { assertFalse(requireNotNull(model).assistantConfig.value.toString().contains(FAKE_SAVED_KEY)) }
    }

    @Test
    fun failedConnectionLeavesSavedConfigUntouchedUntilExplicitReplacementAndCancelKeepsIt() {
        File(application.noBackupFilesDir, "assistant").deleteRecursively()
        val store = AssistantStore(application, TestSettingsCipher)
        store.save(OLD_ENDPOINT, "old-model", OLD_KEY, enabled = true)
        val failingProvider = object : ye.mishwari.ai.AssistantProvider {
            override fun complete(endpoint: String, model: String, key: String, prompt: String, systemPrompt: String): String {
                throw ye.mishwari.ai.AssistantException("synthetic connection failure")
            }
        }
        model = MishwariViewModel(application, store, failingProvider)
        compose.activity.setContent { AssistantSettingsSection(model = requireNotNull(model)) }
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            requireNotNull(model).assistantConfig.value.hasKey
        }

        compose.onNodeWithTag("assistantSettingsEndpoint").performTextReplacement(NEW_ENDPOINT)
        compose.onNodeWithTag("assistantSettingsModel").performTextReplacement("new-model")
        compose.onNodeWithTag("assistantSettingsKey").performTextInput("synthetic-new-key")
        compose.onNodeWithTag("assistantSettingsTest").performClick()
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !requireNotNull(model).assistantBusy.value && requireNotNull(model).assistantMessage.value != null
        }
        assertEquals(OLD_ENDPOINT, store.load().endpoint)
        assertEquals("old-model", store.load().model)
        assertEquals(OLD_KEY, store.readKey())
        assertTrue(store.load().enabled)

        compose.onNodeWithTag("assistantSettingsSave").performClick()
        compose.onNodeWithText("متابعة الاستبدال").assertIsDisplayed()
        compose.onNodeWithText("إلغاء").performClick()
        assertEquals(OLD_ENDPOINT, store.load().endpoint)
        assertEquals("old-model", store.load().model)
        assertEquals(OLD_KEY, store.readKey())
        assertTrue(store.load().enabled)
    }

    @Test
    fun geminiChipUsesOfficialDefaultsAndPersistsOnlyAfterExplicitSave() {
        File(application.noBackupFilesDir, "assistant").deleteRecursively()
        val calls = AtomicReference<List<String>?>(null)
        val provider = object : AssistantProvider {
            override fun complete(endpoint: String, model: String, key: String, prompt: String, systemPrompt: String): String {
                calls.set(listOf(endpoint, model, key, prompt, systemPrompt))
                return "جاهز"
            }
        }
        val store = AssistantStore(application, TestSettingsCipher)
        store.save(OLD_ENDPOINT, "old-model", OLD_KEY, enabled = true)
        model = MishwariViewModel(application, store, provider)
        compose.activity.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                AssistantSettingsSection(model = requireNotNull(model))
            }
        }
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.onAllNodesWithTag("assistantSettingsSection").fetchSemanticsNodes().isNotEmpty() &&
                requireNotNull(model).assistantConfig.value.hasKey
        }

        compose.onNodeWithTag("assistantProviderGemini").performClick()
        compose.onNodeWithTag("assistantSettingsEndpoint").assertTextContains(GEMINI_ENDPOINT)
        compose.onNodeWithTag("assistantSettingsModel").assertTextContains(GEMINI_MODEL)
        compose.onNodeWithTag("assistantSettingsKey").performTextInput(GEMINI_TEST_KEY)
        compose.onNodeWithTag("assistantSettingsTest").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !requireNotNull(model).assistantBusy.value && requireNotNull(model).assistantMessage.value != null
        }

        assertTrue("Gemini test request should reach the configured provider", calls.get() != null)
        assertEquals(GEMINI_ENDPOINT, calls.get()?.get(0))
        assertEquals(GEMINI_MODEL, calls.get()?.get(1))
        assertEquals(GEMINI_TEST_KEY, calls.get()?.get(2))
        assertEquals("أجب بكلمة واحدة فقط: جاهز", calls.get()?.get(3))
        assertEquals(OLD_ENDPOINT, store.load().endpoint)
        assertEquals("old-model", store.load().model)
        assertEquals(OLD_KEY, store.readKey())

        compose.onNodeWithTag("assistantSettingsSave").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            requireNotNull(model).assistantConfig.value.hasKey
        }
        assertEquals(GEMINI_ENDPOINT, store.load().endpoint)
        assertEquals(GEMINI_MODEL, store.load().model)
        assertEquals(AssistantProviderIds.GEMINI, store.load().provider)
        assertEquals(GEMINI_TEST_KEY, store.readKey())
        compose.onNodeWithText(GEMINI_TEST_KEY).assertDoesNotExist()
    }

    private companion object {
        const val FAKE_SAVED_KEY = "synthetic-settings-key-not-a-secret"
        const val OLD_ENDPOINT = "https://saved.example/v1/chat/completions"
        const val NEW_ENDPOINT = "https://draft.example/v1/chat/completions"
        const val OLD_KEY = "synthetic-old-valid-key"
        const val GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        const val GEMINI_MODEL = "gemini-3.8-flash"
        const val GEMINI_TEST_KEY = "synthetic-gemini-test-key"

        object TestSettingsCipher : AssistantKeyCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray =
                plaintext.map { (it.toInt() xor 0x31).toByte() }.toByteArray()

            override fun decrypt(ciphertext: ByteArray): ByteArray = encrypt(ciphertext)
        }
    }
}
