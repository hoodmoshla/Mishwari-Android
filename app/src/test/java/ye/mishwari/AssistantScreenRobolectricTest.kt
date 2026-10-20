package ye.mishwari

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ye.mishwari.ai.AssistantKeyCipher
import ye.mishwari.ai.AssistantProvider
import ye.mishwari.ai.AssistantStore
import ye.mishwari.data.Errand
import ye.mishwari.ui.AssistantScreen
import ye.mishwari.ui.isAssistantEndpoint
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp")
class AssistantScreenRobolectricTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()
    private val models = mutableListOf<MishwariViewModel>()

    @After
    fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
        File(application.noBackupFilesDir, "assistant").deleteRecursively()
    }

    @Test
    fun endpointMustBeHttpsWithoutCredentialsOrQuery() {
        assertTrue("https://api.example.test/v1/chat/completions".isAssistantEndpoint())
        assertTrue("https://api.example.test/custom/assistant".isAssistantEndpoint())
        assertFalse("http://api.example.test/custom/assistant".isAssistantEndpoint())
        assertFalse("https://user:pass@api.example.test/v1/chat/completions".isAssistantEndpoint())
        assertFalse("https://api.example.test/v1/chat/completions?token=unsafe".isAssistantEndpoint())
        assertFalse("https://api.example.test/v1/chat/completions#fragment".isAssistantEndpoint())
    }

    @Test
    fun screenStartsDisabledAndDoesNotOpenReviewOrSendOnPresentation() {
        File(application.noBackupFilesDir, "assistant").deleteRecursively()
        val model = MishwariViewModel(application).also(models::add)
        compose.activity.setContent {
            AssistantScreen(model = model, errands = emptyList(), places = emptyList(), requestVoice = {})
        }

        compose.onNodeWithText("مساعد المشوار الذكي").assertIsDisplayed()
        compose.onNodeWithText("لا يُرسل شيء عند فتح الشاشة أو حفظ الإعدادات.", substring = true).assertIsDisplayed()
        scrollToTag("assistantReviewPayload")
        compose.onNodeWithTag("assistantReviewPayload").assertIsNotEnabled()
        scrollToTag("assistantTestConnection")
        compose.onNodeWithTag("assistantTestConnection").assertIsNotEnabled()
        compose.onNodeWithTag("assistantPayloadReviewDialog").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(model.assistantBusy.value)
            assertFalse(model.assistantConfig.value.enabled)
            assertFalse(model.assistantConfig.value.hasKey)
        }
    }

    @Test
    fun voiceFlowExplainsPrivacyBeforeOpeningSpeechRecognizer() {
        var legacyVoiceCallbackInvoked = false
        File(application.noBackupFilesDir, "assistant").deleteRecursively()
        val model = MishwariViewModel(application).also(models::add)
        compose.activity.setContent {
            AssistantScreen(model = model, errands = emptyList(), places = emptyList(), requestVoice = { legacyVoiceCallbackInvoked = true })
        }

        scrollToTag("assistantVoice")
        compose.onNodeWithTag("assistantVoice").performClick()
        compose.onNodeWithText("إملاء مسودة للمساعد").assertIsDisplayed()
        compose.onNodeWithText("لن يُحفظ أو يُرسل تلقائيًا", substring = true).assertIsDisplayed()
        compose.onNodeWithText("إلغاء").performClick()
        assertFalse(legacyVoiceCallbackInvoked)
        compose.onNodeWithText("إملاء مسودة للمساعد").assertDoesNotExist()
    }

    @Test
    fun exactSelectedPayloadAndFixedInstructionsAreReviewedBeforeProviderCall() {
        File(application.noBackupFilesDir, "assistant").deleteRecursively()
        val store = AssistantStore(application, object : AssistantKeyCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.map { (it.toInt() xor 91).toByte() }.toByteArray()
            override fun decrypt(ciphertext: ByteArray): ByteArray = encrypt(ciphertext)
        })
        store.save("https://api.example.test/v1/chat/completions", "ui-test-model", "ui-test-key", true)
        val providerCalls = AtomicInteger()
        val provider = object : AssistantProvider {
            override fun complete(endpoint: String, model: String, key: String, prompt: String, systemPrompt: String): String {
                providerCalls.incrementAndGet()
                return """{"advice":"اقتراح غير مؤكد","suggestions":[]}"""
            }
        }
        val model = MishwariViewModel(application, store, provider).also(models::add)
        val errand = Errand(id = "assistant-review-test", title = "طلب للمراجعة", notes = "ملاحظة مختارة")
        compose.activity.setContent {
            AssistantScreen(model = model, errands = listOf(errand), places = emptyList(), requestVoice = {})
        }
        compose.waitUntil(15_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            model.assistantConfig.value.enabled
        }
        scrollToTag("assistantSelect_assistant-review-test")
        compose.onNodeWithTag("assistantSelect_assistant-review-test").performClick()
        scrollToTag("assistantReviewPayload")
        compose.onNodeWithTag("assistantReviewPayload").performClick()
        compose.onNodeWithText("راجع الحمولة قبل إرسالها").assertIsDisplayed()
        compose.onNodeWithTag("assistantPayloadText").assertIsDisplayed()
        compose.onNodeWithTag("assistantPayloadText").assertTextContains("طلب للمراجعة", substring = true)
        compose.onNodeWithTag("assistantPayloadText").assertTextContains("ملاحظة مختارة", substring = true)
        compose.onNodeWithText("عرض التعليمات الثابتة المرفقة").performScrollTo().performClick()
        compose.onNodeWithTag("assistantSystemPrompt").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(model.assistantBusy.value)
            assertTrue(providerCalls.get() == 0)
        }
    }

    private fun scrollToTag(tag: String) {
        compose.onNodeWithTag("assistantScreen").performScrollToNode(hasTestTag(tag))
    }
}
