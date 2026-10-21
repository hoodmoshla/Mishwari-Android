package ye.mishwari.ai

import org.json.JSONObject
import ye.mishwari.data.Errand
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Result for a caller to persist transactionally; consume the proposal ID only after persistence succeeds. */
data class AcceptedErrandProposal(val errand: Errand, val consumedProposalId: String)

/** Pure validation/planning API. It never writes to Room or any other storage. */
object AssistantProposalApplier {
    private val dateFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

    /** Capture is deliberately create-only and always returns an Errand with a fresh generated ID. */
    fun acceptCapture(
        suggestion: AssistantSuggestion,
        approvedPayload: String,
        explicitlyAccepted: Boolean,
        consumedProposalIds: Set<String>,
    ): AcceptedErrandProposal {
        require(explicitlyAccepted) { "يتطلب حفظ الاقتراح موافقة صريحة" }
        val payload = approvedPayload(approvedPayload, "capture")
        require(suggestion.mode == "capture" && suggestion.errandId == null) { "هذا ليس اقتراح إنشاء" }
        verifyProposal(payload, approvedPayload, "capture", suggestion, consumedProposalIds)
        val allowedPlaces = ids(payload, "places")
        require(suggestion.placeId == null || suggestion.placeId in allowedPlaces) { "المكان غير موجود في الحمولة المعتمدة" }
        require(suggestion.priority in 0..2 && validDate(suggestion.dueDate)) { "حقول الاقتراح غير صالحة" }
        return AcceptedErrandProposal(
            errand = Errand(
                title = suggestion.title.trim(),
                description = suggestion.description.trim(),
                priority = suggestion.priority,
                dueDate = suggestion.dueDate,
                placeId = suggestion.placeId,
            ),
            consumedProposalId = suggestion.proposalId,
        )
    }

    /**
     * Plan one accepted edit. The caller must execute this inside its DB transaction using `current`
     * read in that transaction, then mark the proposal consumed/remove it only after commit succeeds.
     */
    fun acceptExisting(
        mode: String,
        approvedPayload: String,
        pending: Collection<AssistantSuggestion>,
        originalPending: AssistantSuggestion,
        revised: AssistantSuggestion,
        current: Errand,
        explicitlyAccepted: Boolean,
        consumedProposalIds: Set<String>,
    ): AcceptedErrandProposal {
        require(explicitlyAccepted) { "يتطلب تعديل الطلب موافقة صريحة" }
        require(mode in setOf("priorities", "group", "trip")) { "هذا النمط لا يغير الطلبات الموجودة" }
        require(pending.any { it === originalPending }) { "الاقتراح الأصلي لم يعد معلقًا" }
        require(originalPending.mode == mode && revised.mode == mode) { "نمط الاقتراح غير مطابق" }
        require(originalPending.proposalId == revised.proposalId && originalPending.errandId == revised.errandId) {
            "لا يمكن تغيير هوية الاقتراح أثناء مراجعته"
        }
        require(originalPending.title == revised.title && originalPending.description == revised.description) {
            "لا يمكن تغيير عنوان الطلب أو وصفه عبر اقتراح التعديل"
        }
        val payload = approvedPayload(approvedPayload, mode)
        verifyProposal(payload, approvedPayload, mode, originalPending, consumedProposalIds)
        val targetId = requireNotNull(originalPending.errandId) { "الاقتراح لا يستهدف طلبًا محفوظًا" }
        require(targetId == current.id) { "الطلب الحالي لا يطابق معرّف الاقتراح" }
        val rows = payload.optJSONArray("errands") ?: throw IllegalArgumentException("الحمولة غير صالحة")
        val snapshot = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .firstOrNull { it.optString("id") == targetId }
            ?: throw IllegalArgumentException("معرّف الطلب غير موجود في الحمولة المعتمدة")
        require(snapshotMatches(snapshot, current)) { "تغير الطلب منذ إعداد الحمولة؛ راجعه وأعد المحاولة" }

        val permitted = AssistantSuggestionParser.editableFields(mode)
        require(originalPending.proposedFields.all { it in permitted }) { "يتضمن الاقتراح حقولًا غير مسموحة" }
        val changed = buildSet {
            if (revised.priority != originalPending.priority) add("priority")
            if (revised.dueDate != originalPending.dueDate) add("dueDate")
            if (revised.placeId != originalPending.placeId) add("placeId")
        }
        require(changed.all { it in permitted }) { "تعديل حقل غير مسموح لهذا النمط" }
        val fieldsToApply = originalPending.proposedFields + changed
        require(fieldsToApply.isNotEmpty()) { "لا توجد حقول مقترحة للتطبيق" }

        var updated = current
        if ("priority" in fieldsToApply) {
            require(revised.priority in 0..2) { "الأولوية غير صالحة" }
            updated = updated.copy(priority = revised.priority)
        }
        if ("dueDate" in fieldsToApply) {
            require(validDate(revised.dueDate)) { "الموعد غير صالح" }
            updated = updated.copy(dueDate = revised.dueDate)
        }
        if ("placeId" in fieldsToApply) {
            val allowedPlaces = ids(payload, "places")
            require(revised.placeId == null || revised.placeId in allowedPlaces) { "المكان غير موجود في الحمولة المعتمدة" }
            updated = updated.copy(placeId = revised.placeId)
        }
        return AcceptedErrandProposal(updated, originalPending.proposalId)
    }

    private fun verifyProposal(
        payload: JSONObject,
        approvedPayload: String,
        mode: String,
        suggestion: AssistantSuggestion,
        consumedProposalIds: Set<String>,
    ) {
        require(suggestion.proposalId.isNotBlank() && suggestion.proposalOrdinal >= 0) { "الاقتراح غير مرتبط برد معتمد" }
        require(suggestion.proposalId !in consumedProposalIds) { "سبق اعتماد هذا الاقتراح" }
        val expected = AssistantSuggestionParser.proposalId(
            approvedPayload,
            mode,
            suggestion.proposalOrdinal,
            suggestion,
        )
        require(expected == suggestion.proposalId) { "الاقتراح لا يطابق الحمولة المعتمدة" }
    }

    private fun approvedPayload(text: String, expectedMode: String): JSONObject {
        val payload = try { JSONObject(text) } catch (_: Exception) { throw IllegalArgumentException("الحمولة المعتمدة غير صالحة") }
        require(payload.optString("mode") == expectedMode) { "الحمولة لا تطابق النمط" }
        return payload
    }

    private fun ids(payload: JSONObject, field: String): Set<String> {
        val array = payload.optJSONArray(field) ?: throw IllegalArgumentException("الحمولة غير صالحة")
        val result = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: throw IllegalArgumentException("الحمولة غير صالحة")
            val id = row.optString("id")
            require(id.isNotBlank() && result.add(id)) { "معرّفات الحمولة غير صالحة" }
        }
        return result
    }

    private fun snapshotMatches(snapshot: JSONObject, current: Errand): Boolean =
        snapshot.optString("id") == current.id &&
            snapshot.optString("title") == current.title &&
            snapshot.optString("description") == current.description &&
            snapshot.optString("notes") == current.notes &&
            snapshot.optInt("priority", Int.MIN_VALUE) == current.priority &&
            snapshot.optString("dueDate") == current.dueDate &&
            nullableString(snapshot.opt("placeId")) == current.placeId

    private fun nullableString(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value
        else -> "<invalid>"
    }

    private fun validDate(value: String): Boolean = value.isEmpty() || try {
        LocalDate.parse(value, dateFormatter)
        true
    } catch (_: Exception) {
        false
    }
}
