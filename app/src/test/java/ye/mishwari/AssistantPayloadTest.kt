package ye.mishwari

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ye.mishwari.ai.AssistantPayload
import ye.mishwari.data.Errand
import ye.mishwari.data.Place

class AssistantPayloadTest {
    @Test fun payloadIncludesOnlySelectedRecordsAndNeverPhotosOrCoordinates() {
        val place = Place(id = "custom", name = "المتجر", latitude = 15.123, longitude = 44.234)
        val chosen = Errand(id = "selected", title = "دواء", notes = "ملاحظة يراجعها المستخدم", placeId = place.id, photoPath = "/private/photo.jpg")
        val payload = AssistantPayload.prepare("priorities", "", listOf(chosen), null, listOf(place, Place(id = "secret", name = "غير مختار")))
        val json = JSONObject(payload)
        assertEquals(1, json.getJSONArray("errands").length())
        assertEquals("ملاحظة يراجعها المستخدم", json.getJSONArray("errands").getJSONObject(0).getString("notes"))
        assertFalse(payload.contains("/private"))
        assertFalse(payload.contains("15.123"))
        assertFalse(payload.contains("44.234"))
        assertFalse(payload.contains("غير مختار"))
        assertEquals(setOf("custom"), AssistantPayload.allowedPlaceIds(payload))
    }
    @Test fun emptySelectionDoesNotImplicitlySendExistingErrands() {
        val payload = JSONObject(AssistantPayload.prepare("capture", "اشتر خبزاً", emptyList(), null, emptyList()))
        assertEquals(0, payload.getJSONArray("errands").length())
        assertEquals(0, payload.getJSONArray("places").length())
        assertEquals("اشتر خبزاً", payload.getString("text"))
    }
    @Test fun onlyNamedPlaceCandidatesAreIncludedForNaturalLanguage() {
        val payload = AssistantPayload.prepare("capture", "اشتر دواء من تعز", emptyList(), null,
            listOf(Place(id = "taiz", name = "تعز"), Place(id = "other", name = "عدن")))
        assertEquals(setOf("taiz"), AssistantPayload.allowedPlaceIds(payload))
    }
    @Test fun tripIncludesOnlySelectedErrandsLinkedToDestinationOrItsChildren() {
        val places = listOf(Place(id = "gov", name = "محافظة"),
            Place(id = "district", name = "مديرية", parentId = "gov"),
            Place(id = "shop", name = "متجر", parentId = "district"), Place(id = "elsewhere", name = "آخر"))
        val payload = JSONObject(AssistantPayload.prepare("trip", "", listOf(
            Errand(title = "مرتبط", placeId = "shop"), Errand(title = "خارج الوجهة", placeId = "elsewhere"),
            Errand(title = "بلا وجهة")), "gov", places))
        assertEquals(1, payload.getJSONArray("errands").length())
        assertEquals("مرتبط", payload.getJSONArray("errands").getJSONObject(0).getString("title"))
        assertEquals(2, payload.getInt("excludedNonDestinationCount"))
        assertFalse(payload.toString().contains("خارج الوجهة"))
    }
    @Test fun allModesExplicitlyForbidAutomaticActionsAndGeographicClaims() {
        AssistantPayload.modes.forEach { mode ->
            val system = AssistantPayload.system(mode)
            assertTrue(system.contains("لا تنفذ"))
            assertTrue(system.contains("ليست لديك خدمة مسارات"))
            assertTrue(system.contains("مراجعة المستخدم"))
        }
    }
    @Test fun oversizedInputAndUnknownModeAreRejectedBeforeSending() {
        assertThrows(IllegalArgumentException::class.java) { AssistantPayload.prepare("capture", "x".repeat(12001), emptyList(), null, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { AssistantPayload.prepare("unknown", "", emptyList(), null, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { AssistantPayload.prepare("group", "", List(101) { Errand(title = "a") }, null, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            AssistantPayload.prepare("group", "", List(30) { Errand(title = "طلب", notes = "م".repeat(2000)) }, null, emptyList())
        }
    }
}
