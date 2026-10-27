package ye.mishwari.ui

import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ye.mishwari.data.Errand
import java.math.BigDecimal

/** A display-only sum; blank prices are ignored and unclear nonblank prices are counted as excluded. */
internal data class BudgetTotals(
    val expected: BigDecimal,
    val actual: BigDecimal,
    val excludedExpected: Int,
    val excludedActual: Int
) {
    val excludedCount: Int get() = excludedExpected + excludedActual
}

/** Calculates raw price totals only: quantity is intentionally not applied and no currency is inferred. */
internal fun calculateBudgetTotals(errands: List<Errand>): BudgetTotals {
    var expected = BigDecimal.ZERO
    var actual = BigDecimal.ZERO
    var excludedExpected = 0
    var excludedActual = 0
    errands.forEach { errand ->
        if (errand.expectedPrice.isNotBlank()) {
            val amount = parseNumericPrice(errand.expectedPrice)
            if (amount == null) excludedExpected++ else expected = expected.add(amount)
        }
        if (errand.actualPrice.isNotBlank()) {
            val amount = parseNumericPrice(errand.actualPrice)
            if (amount == null) excludedActual++ else actual = actual.add(amount)
        }
    }
    return BudgetTotals(expected, actual, excludedExpected, excludedActual)
}

/** Parses unsigned decimal amounts with explicit digits/separators; currency labels and mixed text are rejected. */
private fun parseNumericPrice(raw: String): BigDecimal? {
    val normalized = StringBuilder(raw.length)
    for (character in raw.trim()) {
        when (character) {
            in '0'..'9', '.', ',', '\u066b', '\u066c' -> normalized.append(character)
            in '\u0660'..'\u0669' -> normalized.append(('0'.code + character.code - '\u0660'.code).toChar())
            in '\u06f0'..'\u06f9' -> normalized.append(('0'.code + character.code - '\u06f0'.code).toChar())
            else -> return null
        }
    }
    val value = normalized.toString()
    if (value.isEmpty()) return null

    fun digits(text: String): Boolean = text.isNotEmpty() && text.all { it in '0'..'9' }
    fun groupedInteger(text: String, separator: Char): Boolean {
        if (separator !in text) return digits(text)
        val groups = text.split(separator)
        return groups.size >= 2 && groups.first().length in 1..3 && digits(groups.first()) &&
            groups.drop(1).all { it.length == 3 && digits(it) }
    }

    val decimalSeparator = when {
        '.' in value -> '.'
        '\u066b' in value -> '\u066b'
        else -> null
    }
    if (decimalSeparator != null) {
        if (value.count { it == decimalSeparator } != 1) return null
        val groupingSeparator = if (decimalSeparator == '.') ',' else '\u066c'
        val otherGroupingSeparator = if (groupingSeparator == ',') '\u066c' else ','
        if (otherGroupingSeparator in value) return null
        val parts = value.split(decimalSeparator)
        if (parts.size != 2 || !digits(parts[1]) || !groupedInteger(parts[0], groupingSeparator)) return null
        val integerPart = parts[0].replace(groupingSeparator.toString(), "")
        return runCatching { BigDecimal("$integerPart.${parts[1]}") }.getOrNull()
    }

    if (',' in value) {
        val groups = value.split(',')
        if (groups.size == 2) {
            val integerPart = groups[0]
            val fractionalPart = groups[1]
            // A single comma followed by three digits is ambiguous (decimal or thousands separator).
            if (fractionalPart.length == 1 || fractionalPart.length == 2) {
                if (!digits(integerPart) || !digits(fractionalPart)) return null
                return runCatching { BigDecimal("$integerPart.$fractionalPart") }.getOrNull()
            }
            return null
        }
        if (groups.first().length !in 1..3 || !digits(groups.first()) ||
            groups.drop(1).any { it.length != 3 || !digits(it) }
        ) return null
        return runCatching { BigDecimal(groups.joinToString("")) }.getOrNull()
    }

    if ('\u066c' in value) {
        if (!groupedInteger(value, '\u066c')) return null
        return runCatching { BigDecimal(value.replace("\u066c", "")) }.getOrNull()
    }
    return if (digits(value)) runCatching { BigDecimal(value) }.getOrNull() else null
}

/** Optional, initially collapsed summary. It is derived from displayed prices and is never persisted. */
@Composable
fun BudgetSummary(errands: List<Errand>, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val totals = remember(errands) { calculateBudgetTotals(errands) }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "إخفاء ملخص الميزانية" else "إظهار ملخص الميزانية الاختياري")
            }
            if (expanded) {
                Text("المتوقع: ${displayAmount(totals.expected)}", style = MaterialTheme.typography.bodyLarge)
                Text("الفعلي: ${displayAmount(totals.actual)}", style = MaterialTheme.typography.bodyLarge)
                Text("قيم مستبعدة لاحتوائها على نص أو تنسيق غير واضح: ${totals.excludedCount}", style = MaterialTheme.typography.bodySmall)
                Text("المجموع لا يضرب بالكمية. استخدم عملة واحدة؛ لا تحويل.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun displayAmount(amount: BigDecimal): String = amount.stripTrailingZeros().toPlainString()
