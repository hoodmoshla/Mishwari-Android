package ye.mishwari

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ye.mishwari.input.InputIntents

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class InputRobolectricTest {
    @Test
    fun acceptsOnlyPlainTextSendAsReviewDraft() {
        val result = InputIntents.readSharedText(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "أحضر الدواء")
        )
        assertTrue(result.accepted)
        assertEquals("أحضر الدواء", result.text)
        assertNull(result.message)
    }

    @Test
    fun rejectsBlankShareAndMissingText() {
        val blank = InputIntents.readSharedText(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "  \n")
        )
        assertFalse(blank.accepted)
        assertTrue(blank.message.orEmpty().contains("فارغ"))

        val missing = InputIntents.readSharedText(Intent(Intent.ACTION_SEND).setType("text/plain"))
        assertFalse(missing.accepted)
        assertTrue(missing.message.orEmpty().contains("لم تتضمن"))
    }

    @Test
    fun rejectsWrongActionAndHtmlMime() {
        val wrongAction = InputIntents.readSharedText(
            Intent(Intent.ACTION_VIEW).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "نص")
        )
        assertFalse(wrongAction.accepted)

        val html = InputIntents.readSharedText(
            Intent(Intent.ACTION_SEND).setType("text/html").putExtra(Intent.EXTRA_TEXT, "<b>نص</b>")
        )
        assertFalse(html.accepted)
        assertTrue(html.message.orEmpty().contains("النص العادي"))
    }

    @Test
    fun malformedTextExtraIsRejectedSafely() {
        val result = InputIntents.readSharedText(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, 42)
        )
        assertFalse(result.accepted)
        assertTrue(result.message.orEmpty().contains("تعذر قراءة"))
    }

    @Test
    fun rejectsStreamEvenWhenTextIsAlsoPresent() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "نص")
            .putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse("content://private/item"))
        val result = InputIntents.readSharedText(intent)
        assertFalse(result.accepted)
        assertTrue(result.message.orEmpty().contains("الملفات"))
    }

    @Test
    fun rejectsOversizedTextWithoutTruncating() {
        val large = "س".repeat(InputIntents.MAX_DRAFT_LENGTH + 1)
        val result = InputIntents.readSharedText(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, large)
        )
        assertFalse(result.accepted)
        assertNull(result.text)
        assertTrue(result.message.orEmpty().contains("2000"))
    }

    @Test
    fun acceptsExactlyTwoThousandCharacters() {
        val exactLimit = "ن".repeat(InputIntents.MAX_DRAFT_LENGTH)
        val result = InputIntents.readSharedText(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, exactLimit)
        )
        assertTrue(result.accepted)
        assertEquals(exactLimit, result.text)
    }

    @Test
    fun pendingDraftCannotBeOverwrittenByAnotherInput() {
        val candidate = InputIntents.readSharedText(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "مسودة جديدة")
        )
        val result = InputIntents.offerDraft("المسودة الحالية", candidate)
        assertFalse(result.accepted)
        assertNull(result.text)
        assertTrue(result.message.orEmpty().contains("لم تُستبدل"))
    }

    @Test
    fun speechIntentRequestsArabicAndPrefersOfflineWithoutGuaranteeingIt() {
        val intent = InputIntents.createArabicSpeechIntent()
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals("ar", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL)
        )
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissions = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS
        ).requestedPermissions.orEmpty()
        assertFalse(permissions.contains(android.Manifest.permission.RECORD_AUDIO))
    }

    @Test
    fun emptyAndCancelledSpeechResultsGiveClearErrors() {
        val empty = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf())
        val noSpeech = InputIntents.readSpeechResult(Activity.RESULT_OK, empty)
        assertFalse(noSpeech.accepted)
        assertTrue(noSpeech.message.orEmpty().contains("لم يتم التعرف"))

        val cancelled = InputIntents.readSpeechResult(Activity.RESULT_CANCELED, null)
        assertFalse(cancelled.accepted)
        assertTrue(cancelled.message.orEmpty().contains("إلغاء"))
    }

    @Test
    fun successfulSpeechResultBecomesArabicReviewDraft() {
        val recognized = Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS, arrayListOf("شراء الخبز والحليب")
        )
        val result = InputIntents.readSpeechResult(Activity.RESULT_OK, recognized)
        assertTrue(result.accepted)
        assertEquals("شراء الخبز والحليب", result.text)
    }
}
