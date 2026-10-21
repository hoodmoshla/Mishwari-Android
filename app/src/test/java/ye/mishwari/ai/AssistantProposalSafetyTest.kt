package ye.mishwari.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ye.mishwari.data.Errand
import ye.mishwari.data.Place
import org.json.JSONObject

class AssistantProposalSafetyTest {
    @Test
    fun excessiveJsonNestingIsRejectedWithoutStackOverflow() {
        val nested = "{\"suggestions\":" + "[".repeat(10000) + "0" + "]".repeat(10000) + ",\"advice\":\"\"}"
        assertThrows(IllegalArgumentException::class.java) {
            AssistantSuggestionParser.parse(nested, "capture", capturePayload())
        }
    }

    @Test
    fun acceptsOnlyCompleteStrictJsonOrAWholeCleanJsonFence() {
        val payload = capturePayload()
        val fenced = "```json\n${captureReply()}\n```"
        assertEquals("دواء", AssistantSuggestionParser.parse(fenced, "capture", payload).suggestions.single().title)

        listOf(
            captureReply() + " trailing",
            "```json\n${captureReply()}\n``` trailing",
            captureReply().replace("\"advice\":\"\"", "\"advice\":\"\",\"advice\":\"dup\""),
            "{'suggestions':[], 'advice':''}",
            "{\"suggestions\":[,],\"advice\":\"\"}",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                AssistantSuggestionParser.parse(invalid, "capture", payload)
            }
        }
    }

    @Test
    fun existingMaximumLengthTitleCanStillReceiveAnIdBoundPriorityProposal() {
        val original = Errand(id = "long-title", title = "ع".repeat(200))
        val payload = AssistantPayload.prepare("priorities", "", listOf(original), null, emptyList())
        val response = AssistantSuggestionParser.parse(
            """{"suggestions":[{"errandId":"long-title","priority":2,"reason":"أولوية"}],"advice":""}""",
            "priorities", payload,
        )
        assertEquals(original.title, response.suggestions.single().title)
    }

    @Test
    fun editParserBindsErrandIdsToApprovedPayloadAndUsesSnapshotDefaults() {
        val errand = Errand(
            id = "trusted-task", title = "طلب أصلي", description = "وصف أصلي", notes = "ملاحظة خاصة",
            priority = 1, dueDate = "2026-11-30", placeId = "shop", category = "صيانة", quantity = "3",
            expectedPrice = "25", photoPath = "/private/image.jpg", status = "جديد", createdAt = 123L,
        )
        val payload = AssistantPayload.prepare("priorities", "", listOf(errand), null, listOf(Place(id = "shop", name = "المتجر")))
        val parsed = AssistantSuggestionParser.parse(
            """{"suggestions":[{"errandId":"trusted-task","priority":2,"reason":"عاجل"}],"advice":"مراجعة فقط"}""",
            "priorities",
            payload,
        ).suggestions.single()
        assertEquals("trusted-task", parsed.errandId)
        assertEquals("طلب أصلي", parsed.title)
        assertEquals("عاجل", parsed.description)
        assertEquals(2, parsed.priority)
        assertEquals("2026-11-30", parsed.dueDate)
        assertEquals("shop", parsed.placeId)
        assertEquals(setOf("priority"), parsed.proposedFields)

        listOf(
            """{"suggestions":[{"errandId":"forged","priority":2,"reason":"x"}],"advice":""}""",
            """{"suggestions":[{"errandId":"trusted-task","title":"حقل غير مسموح","priority":2,"reason":"x"}],"advice":""}""",
            """{"suggestions":[{"errandId":"trusted-task","placeId":"shop","reason":"x"}],"advice":""}""",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                AssistantSuggestionParser.parse(invalid, "priorities", payload)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssistantSuggestionParser.parse(captureReply(), "priorities", payload)
        }
    }

    @Test
    fun acceptedEditRequiresExplicitApprovalPendingOriginalAndFreshSnapshotAndPreservesOtherFields() {
        val original = Errand(
            id = "edit-1", title = "طلب", description = "وصف", notes = "لا تمسها",
            priority = 1, dueDate = "2026-11-30", placeId = "shop", category = "صيانة", quantity = "2",
            expectedPrice = "55", photoPath = "/secret/photo", status = "جديد", createdAt = 777L,
        )
        val payload = AssistantPayload.prepare("priorities", "", listOf(original), null, listOf(Place(id = "shop", name = "متجر")))
        val parsed = AssistantSuggestionParser.parse(
            """{"suggestions":[{"errandId":"edit-1","priority":2,"reason":"أولوية أعلى"}],"advice":""}""",
            "priorities", payload,
        ).suggestions.single()
        val pending = listOf(parsed)
        val revised = parsed.copy(priority = 0)
        val accepted = AssistantProposalApplier.acceptExisting(
            mode = "priorities", approvedPayload = payload, pending = pending,
            originalPending = parsed, revised = revised, current = original,
            explicitlyAccepted = true, consumedProposalIds = emptySet(),
        )
        assertEquals(0, accepted.errand.priority)
        assertEquals(original.dueDate, accepted.errand.dueDate)
        assertEquals(original.title, accepted.errand.title)
        assertEquals(original.description, accepted.errand.description)
        assertEquals(original.notes, accepted.errand.notes)
        assertEquals(original.placeId, accepted.errand.placeId)
        assertEquals(original.category, accepted.errand.category)
        assertEquals(original.quantity, accepted.errand.quantity)
        assertEquals(original.expectedPrice, accepted.errand.expectedPrice)
        assertEquals(original.photoPath, accepted.errand.photoPath)
        assertEquals(original.status, accepted.errand.status)
        assertEquals(original.createdAt, accepted.errand.createdAt)
        assertEquals(parsed.proposalId, accepted.consumedProposalId)

        assertThrows(IllegalArgumentException::class.java) {
            AssistantProposalApplier.acceptExisting("priorities", payload, pending, parsed, revised, original, false, emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssistantProposalApplier.acceptExisting("priorities", payload, listOf(parsed.copy()), parsed, revised, original, true, emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssistantProposalApplier.acceptExisting("priorities", payload, pending, parsed, revised, original, true, setOf(parsed.proposalId))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssistantProposalApplier.acceptExisting("priorities", payload, pending, parsed, revised, original.copy(notes = "تغيرت"), true, emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssistantProposalApplier.acceptExisting("priorities", payload, pending, parsed, revised.copy(placeId = "forged"), original, true, emptySet())
        }
    }

    @Test
    fun groupChangesOnlyAReviewedPlaceIdAndRejectsPlaceOutsidePayload() {
        val source = Place(id = "shop", name = "المتجر")
        val candidate = Place(id = "market", name = "السوق")
        val original = Errand(id = "group-1", title = "شراء", placeId = source.id, notes = "ملاحظة", priority = 2)
        val payload = AssistantPayload.prepare("group", "انقل إلى السوق", listOf(original), null, listOf(source, candidate))
        val proposal = AssistantSuggestionParser.parse(
            """{"suggestions":[{"errandId":"group-1","placeId":"market","reason":"التجميع المكاني"}],"advice":""}""",
            "group", payload,
        ).suggestions.single()
        val accepted = AssistantProposalApplier.acceptExisting(
            "group", payload, listOf(proposal), proposal, proposal, original, true, emptySet(),
        )
        assertEquals("market", accepted.errand.placeId)
        assertEquals(original.title, accepted.errand.title)
        assertEquals(original.notes, accepted.errand.notes)
        assertEquals(original.priority, accepted.errand.priority)
    }

    @Test
    fun captureAlwaysCreatesFreshRecordAndReplayMarkerBlocksReuse() {
        val payload = capturePayload()
        val proposal = AssistantSuggestionParser.parse(captureReply(), "capture", payload).suggestions.single()
        val saved = AssistantProposalApplier.acceptCapture(proposal, payload, true, emptySet())
        assertNotEquals("old-record", saved.errand.id)
        assertEquals("دواء", saved.errand.title)
        assertEquals(proposal.proposalId, saved.consumedProposalId)
        assertThrows(IllegalArgumentException::class.java) {
            AssistantProposalApplier.acceptCapture(proposal, payload, true, setOf(saved.consumedProposalId))
        }
        assertNull(proposal.errandId)
    }

    @Test
    fun orderIsAdviceOnlyAndArabicPlaceMatchingIsConservative() {
        val orderPayload = AssistantPayload.prepare("order", "", listOf(Errand(id = "order-1", title = "A")), null, emptyList())
        assertTrue(AssistantSuggestionParser.parse("""{"suggestions":[],"advice":"١ ثم ٢"}""", "order", orderPayload).suggestions.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            AssistantSuggestionParser.parse("""{"suggestions":[{"errandId":"order-1","priority":2,"reason":"x"}],"advice":""}""", "order", orderPayload)
        }

        assertEquals("ابراهيم", ArabicPlaceMatcher.normalize("إِبْرَاهِيم"))
        assertNotEquals(ArabicPlaceMatcher.normalize("مدرسة"), ArabicPlaceMatcher.normalize("مدرسه"))
        val duplicateNormalizedNames = listOf(Place(id = "one", name = "إبراهيم"), Place(id = "two", name = "ابراهيم"))
        val ambiguous = AssistantPayload.prepare("capture", "اذهب إلى إِبْرَاهِيم", emptyList(), null, duplicateNormalizedNames)
        assertTrue(JSONObject(ambiguous).getJSONArray("places").length() == 0)

        val shortMatch = AssistantPayload.prepare("capture", "تعزية", emptyList(), null, listOf(Place(id = "taiz", name = "تعز")))
        assertTrue(JSONObject(shortMatch).getJSONArray("places").length() == 0)
        val overlapping = AssistantPayload.prepare("capture", "اذهب إلى مدينة صنعاء", emptyList(), null,
            listOf(Place(id = "city", name = "مدينة صنعاء"), Place(id = "sanaa", name = "صنعاء")))
        assertFalse(JSONObject(overlapping).getJSONArray("places").length() > 0)
    }

    private fun capturePayload() = AssistantPayload.prepare("capture", "دواء", emptyList(), null, emptyList())

    private fun captureReply() =
        """{"suggestions":[{"title":"دواء","description":"شراء دواء","priority":1,"dueDate":"","placeId":null}],"advice":""}"""
}
