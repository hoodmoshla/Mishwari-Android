package ye.mishwari

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ye.mishwari.ai.*
import ye.mishwari.data.*
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AssistantFlowRobolectricTest {
    private lateinit var app: Application
    private lateinit var store: AssistantStore
    private lateinit var model: MishwariViewModel
    private val calls = AtomicInteger()
    @Volatile private var sent = ""
    @Volatile private var reply = """{"advice":"اقتراح غير مؤكد","suggestions":[{"title":"شراء خبز","description":"","priority":1,"dueDate":"","placeId":null}]}"""

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        java.io.File(app.noBackupFilesDir, "assistant").deleteRecursively()
        store = AssistantStore(app, object : AssistantKeyCipher {
            override fun encrypt(plaintext: ByteArray) = plaintext.map { (it.toInt() xor 91).toByte() }.toByteArray()
            override fun decrypt(ciphertext: ByteArray) = encrypt(ciphertext)
        })
        val provider = object : AssistantProvider {
            override fun complete(endpoint: String, model: String, key: String, prompt: String, systemPrompt: String): String {
                assertEquals("fake-test-key", key)
                calls.incrementAndGet(); sent = prompt
                return reply
            }
        }
        runBlocking(Dispatchers.IO) {
            val dao = MishwariDatabase.get(app).dao()
            dao.allErrands().forEach { dao.deleteErrand(it.id) }
        }
        model = MishwariViewModel(app, store, provider)
        waitFor { model.assistantConfig.value == store.load() }
    }
    @After fun cleanup() {
        if (::model.isInitialized) model.viewModelScope.cancel()
        runBlocking(Dispatchers.IO) {
            val dao = MishwariDatabase.get(app).dao()
            dao.allErrands().forEach { dao.deleteErrand(it.id) }
        }
        java.io.File(app.noBackupFilesDir, "assistant").deleteRecursively()
    }

    @Test fun configurationAndPreviewNeverSendAndResponseWaitsForExplicitSave() {
        assertFalse(model.assistantConfig.value.enabled)
        assertEquals(0, calls.get())
        model.saveAssistantConfig("https://example.invalid/v1/chat/completions", "fake-model", "fake-test-key", true)
        waitFor { !model.assistantBusy.value && model.assistantConfig.value.enabled }
        val payload = model.prepareAssistantPayload("capture", "اشتر خبزاً", emptyList(), null)
        assertEquals(0, calls.get())
        assertTrue(runBlocking { MishwariDatabase.get(app).dao().allErrands().isEmpty() })
        model.runAssistant(payload, "capture")
        waitFor { !model.assistantBusy.value && model.assistantSuggestions.value.isNotEmpty() }
        assertEquals(payload, sent)
        assertEquals(1, calls.get())
        assertTrue(runBlocking { MishwariDatabase.get(app).dao().allErrands().isEmpty() })
        val suggestion = model.assistantSuggestions.value.single()
        model.acceptAssistantSuggestion(suggestion)
        waitFor { !model.assistantBusy.value }
        assertEquals("شراء خبز", runBlocking { MishwariDatabase.get(app).dao().allErrands().single().title })
        assertTrue(model.assistantSuggestions.value.isEmpty())
    }

    @Test fun disabledAndDeletedKeysBlockProviderButBasicCrudStillWorks() {
        val payload = model.prepareAssistantPayload("capture", "خبز", emptyList(), null)
        model.runAssistant(payload, "capture")
        waitFor { !model.assistantBusy.value }
        assertEquals(0, calls.get())
        model.saveAssistantConfig("https://example.invalid/v1/chat/completions", "", "fake-test-key", true)
        waitFor { !model.assistantBusy.value && model.assistantConfig.value.hasKey }
        model.deleteAssistantKey()
        waitFor { !model.assistantBusy.value && !model.assistantConfig.value.hasKey }
        model.runAssistant(payload, "capture")
        waitFor { !model.assistantBusy.value }
        assertEquals(0, calls.get())
        model.save(Errand(title = "طلب دون API"))
        waitFor { runBlocking { MishwariDatabase.get(app).dao().allErrands().size == 1 } }
        assertEquals("طلب دون API", runBlocking { MishwariDatabase.get(app).dao().allErrands().single().title })
    }

    @Test fun nonCaptureResultsAreAdvisoryAndConnectionTestUsesSyntheticTextOnly() {
        model.saveAssistantConfig("https://example.invalid/v1/chat/completions", "fake-model", "fake-test-key", true)
        waitFor { !model.assistantBusy.value && model.assistantConfig.value.enabled }
        reply = """{"advice":"اقتراح للمراجعة فقط","suggestions":[]}"""
        val personal = Errand(title = "عنوان شخصي", notes = "ملاحظة شخصية")
        val payload = model.prepareAssistantPayload("priorities", "", listOf(personal), null)
        model.runAssistant(payload, "priorities")
        waitFor { !model.assistantBusy.value && model.assistantResult.value != null }
        assertTrue(model.assistantSuggestions.value.isEmpty())
        assertTrue(runBlocking { MishwariDatabase.get(app).dao().allErrands().isEmpty() })
        model.testAssistantConnection()
        waitFor { !model.assistantBusy.value }
        assertFalse(sent.contains("عنوان شخصي"))
        assertFalse(sent.contains("ملاحظة شخصية"))
        assertEquals("أجب بكلمة واحدة فقط: جاهز", sent)
    }

    @Test fun priorityProposalUpdatesOriginalOnlyAfterExplicitAcceptanceAndPreservesOtherFields() {
        enableAssistant()
        val original = Errand(id = "original-priority", title = "دواء أصلي", description = "وصف محفوظ",
            notes = "ملاحظة لا تتغير", quantity = "علبتان", expectedPrice = "1000", priority = 0)
        runBlocking { MishwariDatabase.get(app).dao().upsertErrand(original) }
        reply = """{"advice":"راجع الأولوية","suggestions":[{"errandId":"original-priority","reason":"مهم للمشوار","priority":2,"dueDate":"2026-12-31"}]}"""
        val payload = model.prepareAssistantPayload("priorities", "", listOf(original), null)
        model.runAssistant(payload, "priorities")
        waitFor { !model.assistantBusy.value && model.assistantSuggestions.value.isNotEmpty() }
        assertEquals(original, runBlocking { MishwariDatabase.get(app).dao().allErrands().single() })
        val suggestion = model.assistantSuggestions.value.single()
        model.acceptAssistantSuggestion(suggestion)
        waitFor { !model.assistantBusy.value }
        assertEquals(original.copy(priority = 2, dueDate = "2026-12-31"), runBlocking { MishwariDatabase.get(app).dao().allErrands().single() })
        model.acceptAssistantSuggestion(suggestion)
        waitFor { !model.assistantBusy.value }
        assertEquals(1, runBlocking { MishwariDatabase.get(app).dao().allErrands().size })
        assertTrue(model.assistantMessage.value.orEmpty().contains("معلق"))
    }

    @Test fun staleOrDeletedOriginalCannotBeOverwrittenOrRecreated() {
        enableAssistant()
        val original = Errand(id = "stale-original", title = "طلب للمراجعة", priority = 0)
        val dao = MishwariDatabase.get(app).dao()
        runBlocking { dao.upsertErrand(original) }
        reply = """{"advice":"اقتراح","suggestions":[{"errandId":"stale-original","reason":"مهم","priority":2}]}"""
        model.runAssistant(model.prepareAssistantPayload("priorities", "", listOf(original), null), "priorities")
        waitFor { !model.assistantBusy.value && model.assistantSuggestions.value.isNotEmpty() }
        val suggestion = model.assistantSuggestions.value.single()
        runBlocking { dao.upsertErrand(original.copy(notes = "تعديل يدوي أحدث")) }
        model.acceptAssistantSuggestion(suggestion)
        waitFor { !model.assistantBusy.value }
        assertEquals(0, runBlocking { dao.allErrands().single().priority })
        assertEquals("تعديل يدوي أحدث", runBlocking { dao.allErrands().single().notes })
        runBlocking { dao.deleteErrand(original.id) }
        model.acceptAssistantSuggestion(suggestion)
        waitFor { !model.assistantBusy.value }
        assertTrue(runBlocking { dao.allErrands().isEmpty() })
    }

    @Test fun editedCaptureCreatesOneNewRecordAndRejectsReplayAndArabicDuplicate() {
        enableAssistant()
        val payload = model.prepareAssistantPayload("capture", "اشتر خبز", emptyList(), null)
        model.runAssistant(payload, "capture")
        waitFor { !model.assistantBusy.value && model.assistantSuggestions.value.isNotEmpty() }
        val original = model.assistantSuggestions.value.single()
        val revised = original.copy(title = "شراء أدوية", description = "راجعتها بنفسي")
        model.acceptReviewedAssistantSuggestion(original, revised)
        waitFor { !model.assistantBusy.value }
        val saved = runBlocking { MishwariDatabase.get(app).dao().allErrands().single() }
        assertEquals("شراء أدوية", saved.title)
        assertEquals("راجعتها بنفسي", saved.description)
        model.acceptReviewedAssistantSuggestion(original, revised)
        waitFor { !model.assistantBusy.value }
        assertEquals(1, runBlocking { MishwariDatabase.get(app).dao().allErrands().size })
        reply = """{"advice":"راجع","suggestions":[{"title":"شراء ادوية","description":"","priority":1,"dueDate":"","placeId":null}]}"""
        model.runAssistant(payload, "capture")
        waitFor { !model.assistantBusy.value && model.assistantSuggestions.value.isNotEmpty() }
        model.acceptAssistantSuggestion(model.assistantSuggestions.value.single())
        waitFor { !model.assistantBusy.value }
        assertEquals(1, runBlocking { MishwariDatabase.get(app).dao().allErrands().size })
        assertTrue(model.assistantMessage.value.orEmpty().contains("مكرر"))
    }

    @Test fun fabricatedSuggestionOutsidePendingResponseIsRejected() {
        model.acceptAssistantSuggestion(AssistantSuggestion("غير معتمد", "", 1, "", null))
        waitFor { !model.assistantBusy.value }
        assertTrue(runBlocking { MishwariDatabase.get(app).dao().allErrands().isEmpty() })
    }

    private fun enableAssistant() {
        model.saveAssistantConfig("https://example.invalid/v1/chat/completions", "fake-model", "fake-test-key", true)
        waitFor { !model.assistantBusy.value && model.assistantConfig.value.enabled }
    }

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        do {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(15)
        } while (System.nanoTime() < deadline)
        fail("Timed out waiting for assistant state: ${model.assistantMessage.value}")
    }
}
