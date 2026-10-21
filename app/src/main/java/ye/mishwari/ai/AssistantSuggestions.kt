package ye.mishwari.ai

import org.json.JSONException
import org.json.JSONObject
import ye.mishwari.data.Errand
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** A proposal only; persisted changes require an explicit acceptance call. */
data class AssistantSuggestion(
    val title: String,
    val description: String,
    val priority: Int,
    val dueDate: String,
    val placeId: String?,
    /** Trusted only after parsing against the approved payload; never use a generated title as identity. */
    val errandId: String? = null,
    val mode: String = "capture",
    val proposalId: String = "",
    val proposedFields: Set<String> = emptySet(),
    internal val proposalOrdinal: Int = -1,
)

data class AssistantResponse(val suggestions: List<AssistantSuggestion>, val advice: String)

object AssistantSuggestionParser {
    private val strictDateFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd")
        .withResolverStyle(ResolverStyle.STRICT)
    private val rootKeys = setOf("suggestions", "advice")
    private val captureItemKeys = setOf("title", "description", "priority", "dueDate", "placeId")

    /** Legacy capture parser retained for existing callers. New mode-aware code should pass approvedPayload. */
    fun parse(text: String, allowedPlaceIds: Set<String>): AssistantResponse =
        parseValidated(text, "capture", allowedPlaceIds, emptyMap(), "")

    /** Parses against the exact reviewed payload; edit IDs are accepted only if that payload supplied them. */
    fun parse(text: String, mode: String, approvedPayload: String): AssistantResponse {
        require(mode in AssistantPayload.modes) { "وظيفة غير مدعومة" }
        val payload = parseJsonObject(approvedPayload)
        require(payload.optString("mode") == mode) { "الحمولة لا تطابق وظيفة المساعد" }
        val placeIds = payloadIds(payload, "places")
        val errands = payload.optJSONArray("errands") ?: throw IllegalArgumentException("الحمولة غير صالحة")
        val rows = linkedMapOf<String, JSONObject>()
        for (i in 0 until errands.length()) {
            val row = errands.optJSONObject(i) ?: throw IllegalArgumentException("الحمولة غير صالحة")
            val id = row.optString("id")
            require(id.isNotBlank() && rows.put(id, row) == null) { "معرّفات الطلبات في الحمولة غير صالحة" }
        }
        return parseValidated(text, mode, placeIds, rows, approvedPayload)
    }

    private fun parseValidated(
        text: String,
        mode: String,
        allowedPlaceIds: Set<String>,
        payloadErrands: Map<String, JSONObject>,
        approvedPayload: String,
    ): AssistantResponse {
        require(text.length <= MAX_RESPONSE_CHARS) { "استجابة المساعد أطول من المسموح" }
        try {
            val root = parseJsonObject(text)
            rejectUnknownKeys(root, rootKeys)
            val array = root.optJSONArray("suggestions") ?: throw JSONException("missing suggestions")
            if (array.length() > MAX_SUGGESTIONS) throw JSONException("too many suggestions")
            val adviceValue = root.opt("advice")
            val advice = when (adviceValue) {
                null, JSONObject.NULL -> ""
                is String -> adviceValue.also { if (it.length > MAX_ADVICE_CHARS) throw JSONException("advice too long") }
                else -> throw JSONException("invalid advice")
            }
            if (mode == "order" && array.length() != 0) throw JSONException("order cannot edit stored fields")
            val suggestions = ArrayList<AssistantSuggestion>(array.length())
            val seenTargets = mutableSetOf<String>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: throw JSONException("suggestion must be an object")
                val parsed = if (mode == "capture") {
                    rejectUnknownKeys(item, captureItemKeys)
                    parseCaptureSuggestion(item, allowedPlaceIds)
                } else {
                    val edit = parseEditSuggestion(item, mode, allowedPlaceIds, payloadErrands, seenTargets)
                    edit
                }
                val stamped = parsed.copy(
                    mode = mode,
                    proposalOrdinal = index,
                    proposalId = proposalId(approvedPayload, mode, index, parsed),
                )
                suggestions += stamped
            }
            return AssistantResponse(suggestions, advice)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("صيغة اقتراحات المساعد غير صالحة")
        }
    }

    private fun parseCaptureSuggestion(item: JSONObject, allowedPlaceIds: Set<String>): AssistantSuggestion {
        val title = requiredText(item, "title", MAX_TITLE_CHARS)
        val description = optionalText(item, "description", MAX_DESCRIPTION_CHARS)
        val priority = parsePriority(item.opt("priority"))
        val dueDate = parseDate(item.opt("dueDate"))
        val placeId = parsePlaceId(item.opt("placeId"), allowedPlaceIds)
        return AssistantSuggestion(
            title = title,
            description = description,
            priority = priority,
            dueDate = dueDate,
            placeId = placeId,
            proposedFields = setOf("title", "description", "priority", "dueDate", "placeId"),
        )
    }

    private fun parseEditSuggestion(
        item: JSONObject,
        mode: String,
        allowedPlaceIds: Set<String>,
        payloadErrands: Map<String, JSONObject>,
        seenTargets: MutableSet<String>,
    ): AssistantSuggestion {
        val fields = editableFields(mode)
        val allowedKeys = setOf("errandId", "reason") + fields
        rejectUnknownKeys(item, allowedKeys)
        val id = requiredText(item, "errandId", MAX_ID_CHARS)
        if (id !in payloadErrands) throw JSONException("unknown errand id")
        if (!seenTargets.add(id)) throw JSONException("duplicate errand id")
        val row = payloadErrands.getValue(id)
        val reason = requiredText(item, "reason", MAX_DESCRIPTION_CHARS)
        val proposed = fields.filterTo(linkedSetOf()) { item.has(it) }
        if (proposed.isEmpty()) throw JSONException("no editable fields")
        val priority = if (item.has("priority")) parsePriority(item.opt("priority")) else parsePriority(row.opt("priority"))
        val dueDate = if (item.has("dueDate")) parseDate(item.opt("dueDate")) else parseDate(row.opt("dueDate"))
        val placeId = if (item.has("placeId")) {
            parsePlaceId(item.opt("placeId"), allowedPlaceIds).also {
                if (mode == "group" && it == null) throw JSONException("group target must be a known place")
            }
        } else parsePlaceId(row.opt("placeId"), allowedPlaceIds)
        return AssistantSuggestion(
            title = requiredText(row, "title", MAX_TITLE_CHARS),
            description = reason,
            priority = priority,
            dueDate = dueDate,
            placeId = placeId,
            errandId = id,
            mode = mode,
            proposedFields = proposed,
        )
    }

    internal fun editableFields(mode: String): Set<String> = when (mode) {
        "priorities" -> setOf("priority", "dueDate")
        "group" -> setOf("placeId")
        "trip" -> setOf("priority", "dueDate")
        else -> emptySet()
    }

    private fun parsePriority(value: Any?): Int {
        val number = value as? Number ?: throw JSONException("invalid priority")
        val parsed = number.toInt()
        if (parsed !in 0..2 || number.toDouble() != parsed.toDouble()) throw JSONException("invalid priority")
        return parsed
    }

    private fun parseDate(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value.also {
            if (it.isNotEmpty() && (it.length != 10 || !isValidDate(it))) throw JSONException("invalid date")
        }
        else -> throw JSONException("invalid date type")
    }

    private fun parsePlaceId(value: Any?, allowedPlaceIds: Set<String>): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.also {
            if (it.isBlank() || it !in allowedPlaceIds) throw JSONException("unknown place id")
        }
        else -> throw JSONException("invalid place id")
    }

    private fun payloadIds(payload: JSONObject, property: String): Set<String> {
        val rows = payload.optJSONArray(property) ?: throw IllegalArgumentException("الحمولة غير صالحة")
        val ids = linkedSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: throw IllegalArgumentException("الحمولة غير صالحة")
            val id = row.optString("id")
            require(id.isNotBlank() && ids.add(id)) { "معرّفات الحمولة غير صالحة" }
        }
        return ids
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

    private fun parseJsonObject(text: String): JSONObject {
        val source = unwrapCleanFence(text)
        StrictJson(source).validate()
        return JSONObject(source)
    }

    private fun unwrapCleanFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val match = CLEAN_JSON_FENCE.matchEntire(trimmed) ?: throw JSONException("invalid JSON fence")
        return match.groupValues[1]
    }

    private fun isValidDate(value: String): Boolean = try {
        LocalDate.parse(value, strictDateFormatter)
        true
    } catch (_: Exception) {
        false
    }

    internal fun proposalId(payload: String, mode: String, index: Int, suggestion: AssistantSuggestion): String {
        val fields = suggestion.proposedFields.sorted().joinToString(",")
        val canonical = listOf(
            payload, mode, index.toString(), suggestion.errandId.orEmpty(), suggestion.title,
            suggestion.description, suggestion.priority.toString(), suggestion.dueDate,
            suggestion.placeId.orEmpty(), fields,
        ).joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private const val MAX_RESPONSE_CHARS = 256 * 1024
    private const val MAX_SUGGESTIONS = 20
    private const val MAX_TITLE_CHARS = 200
    private const val MAX_DESCRIPTION_CHARS = 2000
    private const val MAX_ADVICE_CHARS = 4000
    private const val MAX_ID_CHARS = 200
    private val CLEAN_JSON_FENCE = Regex("```json[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n```[ \\t]*")
}

/**
 * Minimal strict-JSON grammar check in front of org.json (which intentionally accepts JSON-ish syntax).
 * It rejects trailing values/junk, comments, unquoted/single-quoted keys, trailing commas and duplicate keys.
 */
private class StrictJson(private val source: String) {
    private var offset = 0
    private var depth = 0

    fun validate() {
        whitespace()
        value()
        whitespace()
        if (offset != source.length) fail()
    }

    private fun value() {
        whitespace()
        if (++depth > 32) fail()
        try {
            when (peek()) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> numberValue()
                else -> fail()
            }
        } finally {
            depth--
        }
    }

    private fun objectValue() {
        expect('{')
        whitespace()
        if (take('}')) return
        val keys = hashSetOf<String>()
        while (true) {
            whitespace()
            if (peek() != '"') fail()
            val key = stringValue()
            if (!keys.add(key)) fail()
            whitespace()
            expect(':')
            value()
            whitespace()
            if (take('}')) return
            expect(',')
        }
    }

    private fun arrayValue() {
        expect('[')
        whitespace()
        if (take(']')) return
        while (true) {
            value()
            whitespace()
            if (take(']')) return
            expect(',')
        }
    }

    private fun stringValue(): String {
        expect('"')
        val start = offset
        while (offset < source.length) {
            val ch = source[offset++]
            when {
                ch == '"' -> {
                    return decodeJsonString(source.substring(start, offset - 1))
                }
                ch == '\\' -> {
                    if (offset >= source.length) fail()
                    val escaped = source[offset++]
                    if (escaped == 'u') {
                        repeat(4) { if (offset >= source.length || source[offset++].digitToIntOrNull(16) == null) fail() }
                    } else if (escaped !in "\"\\/bfnrt") fail()
                }
                ch.code < 0x20 -> fail()
            }
        }
        fail()
    }

    private fun numberValue() {
        val start = offset
        take('-')
        if (take('0')) {
            if (peek() in '0'..'9') fail()
        } else {
            digits(required = true)
        }
        if (take('.')) digits(required = true)
        if (take('e') || take('E')) {
            if (!take('+')) take('-')
            digits(required = true)
        }
        if (offset == start) fail()
    }

    private fun digits(required: Boolean) {
        val start = offset
        while (peek() in '0'..'9') offset++
        if (required && offset == start) fail()
    }

    private fun literal(value: String) {
        if (!source.startsWith(value, offset)) fail()
        offset += value.length
    }

    private fun whitespace() { while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') offset++ }
    private fun peek(): Char = source.getOrElse(offset) { '\u0000' }
    private fun take(ch: Char): Boolean = if (peek() == ch) { offset++; true } else false
    private fun expect(ch: Char) { if (!take(ch)) fail() }
    private fun fail(): Nothing = throw JSONException("invalid strict JSON")

    private companion object {
        fun decodeJsonString(content: String): String = JSONObject("{\"s\":\"$content\"}").getString("s")
    }
}
