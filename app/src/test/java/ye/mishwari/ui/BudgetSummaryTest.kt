package ye.mishwari.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ye.mishwari.data.Errand
import java.math.BigDecimal

class BudgetSummaryTest {
    @Test
    fun sumsExpectedAndActualPricesWithoutMultiplyingByQuantity() {
        val totals = calculateBudgetTotals(
            listOf(
                Errand(id = "one", title = "الأول", quantity = "5", expectedPrice = "10.50", actualPrice = "8"),
                Errand(id = "two", title = "الثاني", quantity = "3", expectedPrice = "2.5", actualPrice = "4.25")
            )
        )

        assertDecimal("13", totals.expected)
        assertDecimal("12.25", totals.actual)
        assertEquals(0, totals.excludedCount)
    }

    @Test
    fun acceptsArabicDigitsAndExplicitArabicSeparators() {
        val totals = calculateBudgetTotals(
            listOf(Errand(id = "arabic", title = "أرقام عربية", expectedPrice = "١٬٢٣٤٫٥٠", actualPrice = "۲۵٫٧٥"))
        )

        assertDecimal("1234.50", totals.expected)
        assertDecimal("25.75", totals.actual)
        assertEquals(0, totals.excludedCount)
    }

    @Test
    fun excludesMixedCurrencyTextAndAmbiguousCommaFormatting() {
        val totals = calculateBudgetTotals(
            listOf(
                Errand(id = "mixed", title = "نص مختلط", expectedPrice = "100 ريال", actualPrice = "$25"),
                Errand(id = "ambiguous", title = "فاصلة ملتبسة", expectedPrice = "1,234")
            )
        )

        assertDecimal("0", totals.expected)
        assertDecimal("0", totals.actual)
        assertEquals(2, totals.excludedExpected)
        assertEquals(1, totals.excludedActual)
        assertEquals(3, totals.excludedCount)
    }

    @Test
    fun emptyPricesProduceZeroTotalsAndAreNotCountedAsExcluded() {
        val totals = calculateBudgetTotals(listOf(Errand(id = "empty", title = "بلا أسعار")))

        assertDecimal("0", totals.expected)
        assertDecimal("0", totals.actual)
        assertEquals(0, totals.excludedCount)
        assertTrue(totals.expected == BigDecimal.ZERO)
    }

    private fun assertDecimal(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }
}
