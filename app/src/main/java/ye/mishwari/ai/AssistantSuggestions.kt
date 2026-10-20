package ye.mishwari.ai

import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** A proposal only; this core never writes suggestions to application data. */
data class AssistantSuggestion(
    val title: String,
    val description: String,
    val priority: Int,
    val dueDate: String,
    val placeId: String?,
)

data class AssistantResponse(
    val suggestions: List<AssistantSuggestion>,
    val advice: String,
)

object AssistantSuggestionParser {
    private val strictDateFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd")
        .withResolverStyle(ResolverStyle.STRICT)
    private val rootKeys = setOf("suggestions", "advice")
    private val itemKeys = setOf("title", "description", "priority", "dueDate", "placeId")

    /** Parses only the agreed response object; unknown place IDs and unsupported fields are rejected. */
    fun parse(text: String, allowedPlaceIds: Set<String>): AssistantResponse {
        require(text.length <= MAX_RESPONSE_CHARS) { "استجابة المساعد أطول من المسموح" }
        try {
            val root = JSONObject(text)
            rejectUnknownKeys(root, rootKeys)
            val array = root.optJSONArray("suggestions") ?: throw JSONException("missing suggestions")
            if (array.length() > MAX_SUGGESTIONS) throw JSONException("too many suggestions")
            val adviceValue = root.opt("advice")
            val advice = when (adviceValue) {
                null, JSONObject.NULL -> ""
                is String -> adviceValue.also { if (it.length > MAX_ADVICE_CHARS) throw JSONException("advice too long") }
                else -> throw JSONException("invalid advice")
            }
            val suggestions = ArrayList<AssistantSuggestion>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: throw JSONException("suggestion must be an object")
                rejectUnknownKeys(item, itemKeys)
                suggestions += parseSuggestion(item, allowedPlaceIds)
            }
            return AssistantResponse(suggestions, advice)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("صيغة اقتراحات المساعد غير صالحة")
        }
    }

    private fun parseSuggestion(item: JSONObject, allowedPlaceIds: Set<String>): AssistantSuggestion {
        val title = requiredText(item, "title", MAX_TITLE_CHARS)
        val description = optionalText(item, "description", MAX_DESCRIPTION_CHARS)
        val priorityValue = item.opt("priority")
        val priorityNumber = priorityValue as? Number ?: throw JSONException("invalid priority")
        val priority = priorityNumber.toInt()
        if (priority !in VALID_PRIORITIES || priorityNumber.toDouble() != priority.toDouble()) {
            throw JSONException("invalid priority")
        }

        val dateValue = item.opt("dueDate")
        val dueDate = when (dateValue) {
            null, JSONObject.NULL -> ""
            is String -> dateValue.also {
                if (it.isNotEmpty() && (it.length != 10 || !isValidDate(it))) throw JSONException("invalid date")
            }
            else -> throw JSONException("invalid date type")
        }

        val placeValue = item.opt("placeId")
        val placeId = when (placeValue) {
            null, JSONObject.NULL -> null
            is String -> placeValue.also {
                if (it.isBlank() || it !in allowedPlaceIds) throw JSONException("unknown place id")
            }
            else -> throw JSONException("invalid place id")
        }
        return AssistantSuggestion(title, description, priority, dueDate, placeId)
    }

    private fun requiredText(item: JSONObject, name: String, maxLength: Int): String {
        val value = item.opt(name) as? String ?: throw JSONException("missing $name")
        if (value.isBlank() || value.length > maxLength) throw JSONException("invalid $name")
        return value.trim()
    }

    private fun optionalText(item: JSONObject, name: String, maxLength: Int): String {
        val value = item.opt(name) as? String ?: throw JSONException("missing $name")
        if (value.length > maxLength) throw JSONException("invalid $name")
        return value.trim()
    }

    private fun rejectUnknownKeys(objectValue: JSONObject, allowed: Set<String>) {
        val keys = objectValue.keys()
        while (keys.hasNext()) if (keys.next() !in allowed) throw JSONException("unknown field")
    }

    private fun isValidDate(value: String): Boolean = try {
        LocalDate.parse(value, strictDateFormatter)
        true
    } catch (_: Exception) {
        false
    }

    private const val MAX_RESPONSE_CHARS = 256 * 1024
    private const val MAX_SUGGESTIONS = 20
    private const val MAX_TITLE_CHARS = 160
    private const val MAX_DESCRIPTION_CHARS = 2000
    private const val MAX_ADVICE_CHARS = 4000
    private val VALID_PRIORITIES = 0..2
}
