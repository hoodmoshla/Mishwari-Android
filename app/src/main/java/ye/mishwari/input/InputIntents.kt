package ye.mishwari.input

import android.content.Intent
import android.speech.RecognizerIntent

/** Parsing and intent construction for user-reviewed input drafts; no persistence or URI access. */
object InputIntents {
    const val MAX_DRAFT_LENGTH = 2_000

    data class Result(val text: String? = null, val message: String? = null) {
        val accepted: Boolean get() = text != null
    }

    fun offerDraft(pendingDraft: String?, candidate: Result): Result {
        if (!candidate.accepted || pendingDraft == null) return candidate
        return Result(message = "توجد مسودة إدخال غير مستهلكة؛ راجعها أولًا. لم تُستبدل المسودة الحالية")
    }

    fun readSharedText(intent: Intent): Result {
        if (intent.action != Intent.ACTION_SEND) return Result(message = "نوع المشاركة غير مدعوم")
        if (!intent.type.equals("text/plain", ignoreCase = true)) {
            return Result(message = "يمكن استقبال النص العادي فقط")
        }
        if (intent.hasExtra(Intent.EXTRA_STREAM)) {
            return Result(message = "مشاركة الملفات أو الروابط غير مدعومة؛ أرسل نصًا فقط")
        }
        if (!intent.hasExtra(Intent.EXTRA_TEXT)) return Result(message = "لم تتضمن المشاركة نصًا")
        val text = try {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        } catch (_: RuntimeException) {
            return Result(message = "تعذر قراءة نص المشاركة؛ أرسل نصًا عاديًا")
        } ?: return Result(message = "تعذر قراءة نص المشاركة؛ أرسل نصًا عاديًا")
        return validateDraft(text)
    }

    fun createArabicSpeechIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
        // A preference hint only; speech services may still use network processing.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    fun readSpeechResult(resultCode: Int, data: Intent?): Result {
        if (resultCode != android.app.Activity.RESULT_OK) {
            return Result(message = "تم إلغاء الإدخال الصوتي")
        }
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            ?: return Result(message = "لم يتم التعرف على كلام؛ حاول مرة أخرى أو اكتب النص")
        return validateDraft(text)
    }

    private fun validateDraft(text: String): Result {
        if (text.isBlank()) return Result(message = "النص فارغ؛ أدخل نصًا للمراجعة")
        if (text.length > MAX_DRAFT_LENGTH) {
            return Result(message = "النص أطول من الحد المسموح (2000 حرف) ولم يُستورد")
        }
        return Result(text = text)
    }
}
