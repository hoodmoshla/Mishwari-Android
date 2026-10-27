package ye.mishwari.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkErrandInputTest {
    @Test
    fun ignoresBlankLines() {
        val errands = parseBulkErrands("\n \t\n\r\n", placeId = null, priority = 1)

        assertTrue(errands.isEmpty())
    }

    @Test
    fun trimsEachNonBlankTitle() {
        val errands = parseBulkErrands("  حليب  \n\tخبز\t", placeId = null, priority = 1)

        assertEquals(listOf("حليب", "خبز"), errands.map { it.title })
    }

    @Test
    fun preservesArabicTitleOrder() {
        val errands = parseBulkErrands("خبز\nدواء\nحليب", placeId = null, priority = 1)

        assertEquals(listOf("خبز", "دواء", "حليب"), errands.map { it.title })
    }

    @Test
    fun acceptsExactlyFiftyErrands() {
        val text = (1..50).joinToString("\n") { "طلب $it" }

        assertEquals(50, parseBulkErrands(text, placeId = null, priority = 0).size)
    }

    @Test
    fun rejectsMoreThanFiftyErrands() {
        val text = (1..51).joinToString("\n") { "طلب $it" }

        assertRejected { parseBulkErrands(text, placeId = null, priority = 1) }
    }

    @Test
    fun rejectsTitleLongerThanTwoHundredCharacters() {
        assertRejected { parseBulkErrands("أ".repeat(201), placeId = null, priority = 1) }
    }

    @Test
    fun acceptsTitleExactlyTwoHundredCharactersWithoutTruncating() {
        val title = "أ".repeat(200)

        assertEquals(title, parseBulkErrands(title, placeId = null, priority = 1).single().title)
    }

    @Test
    fun rejectsPriorityOutsideZeroThroughTwo() {
        assertRejected { parseBulkErrands("حليب", placeId = null, priority = -1) }
        assertRejected { parseBulkErrands("حليب", placeId = null, priority = 3) }
    }

    @Test
    fun placeIsOptionalAndAppliedToEveryDraftWhenProvided() {
        val withoutPlace = parseBulkErrands("حليب\nخبز", placeId = null, priority = 2)
        val withPlace = parseBulkErrands("حليب\nخبز", placeId = "market-1", priority = 2)

        assertTrue(withoutPlace.all { it.placeId == null })
        assertEquals(listOf("market-1", "market-1"), withPlace.map { it.placeId })
        assertTrue(withPlace.all { it.priority == 2 })
    }

    @Test
    fun createsShoppingDraftsWithEmptyExpectedPrice() {
        val errands = parseBulkErrands("حليب", placeId = null, priority = 1)

        assertEquals("مشتريات", errands.single().category)
        assertEquals("", errands.single().expectedPrice)
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected invalid bulk input to be rejected")
    }
}
