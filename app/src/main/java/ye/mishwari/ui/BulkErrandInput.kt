package ye.mishwari.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ye.mishwari.data.Errand
import ye.mishwari.data.Place

private const val MAX_BULK_ERRANDS = 50
private const val MAX_ERRAND_TITLE_LENGTH = 200

/** Parses one trimmed, non-empty title per line into local errand drafts. */
fun parseBulkErrands(text: String, placeId: String?, priority: Int): List<Errand> {
    require(priority in 0..2) { "priority must be between 0 and 2" }

    val titles = text.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()

    require(titles.size <= MAX_BULK_ERRANDS) { "a bulk batch cannot exceed 50 errands" }
    titles.forEach { title ->
        require(title.length <= MAX_ERRAND_TITLE_LENGTH) { "an errand title cannot exceed 200 characters" }
    }

    return titles.map { title ->
        Errand(
            title = title,
            placeId = placeId,
            category = "مشتريات",
            expectedPrice = "",
            priority = priority
        )
    }
}

/** A local-only editor for reviewing and saving a batch of shopping drafts. */
@Composable
fun BulkErrandInputDialog(
    places: List<Place>,
    onDismiss: () -> Unit,
    onSave: (List<Errand>) -> Unit,
    enabled: Boolean = true
) {
    // Shopping-list text is intentionally kept only in ordinary composition state, never saveable state.
    var text by remember { mutableStateOf("") }
    var selectedPlaceId by remember { mutableStateOf<String?>(null) }
    var priority by remember { mutableStateOf(1) }
    var priorityMenuExpanded by remember { mutableStateOf(false) }

    val normalizedText = text.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalizedText.split('\n').mapIndexedNotNull { lineIndex, line ->
        line.trim().takeIf(String::isNotEmpty)?.let { lineIndex to it }
    }
    val validationMessage = when {
        lines.isEmpty() -> "أضف عنوانًا واحدًا على الأقل؛ السطور الفارغة ستُتجاهل."
        lines.size > MAX_BULK_ERRANDS -> "الحد الأقصى 50 طلبًا في الدفعة. احذف بعض السطور للمتابعة."
        lines.any { (_, title) -> title.length > MAX_ERRAND_TITLE_LENGTH } -> "لا يمكن أن يتجاوز عنوان الطلب 200 حرف. عدّل العنوان الطويل بدل قصّه."
        else -> null
    }
    val canSave = enabled && validationMessage == null

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .fillMaxHeight(0.94f)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "إدخال طلبات متعددة",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "كل سطر يصبح مسودة مشتريات مستقلة. تتم المعالجة محليًا فقط؛ لا يُحفظ شيء حتى تضغط حفظ الدفعة.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.replace("\r\n", "\n").replace('\r', '\n') },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        label = { Text("أدخل عنوانًا في كل سطر") },
                        placeholder = { Text("مثال: حليب\nخبز\nدواء للصداع") },
                        minLines = 3,
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions.Default,
                        supportingText = { Text("${lines.size}/$MAX_BULK_ERRANDS طلبًا · الحد الأقصى للعنوان 200 حرف") }
                    )

                    if (enabled) {
                        PlaceSelector(
                            label = "المكان (اختياري)",
                            selected = selectedPlaceId,
                            places = places,
                            change = { selectedPlaceId = it },
                            allowNone = true,
                            showAlertReadiness = false
                        )
                    } else {
                        Text("المكان: ${places.firstOrNull { it.id == selectedPlaceId }?.name ?: "دون وجهة"}")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("الأولوية للجميع", modifier = Modifier.weight(1f))
                        Column {
                            OutlinedButton(
                                onClick = { priorityMenuExpanded = true },
                                enabled = enabled
                            ) {
                                Text(listOf("منخفضة", "عادية", "عالية")[priority])
                            }
                            DropdownMenu(
                                expanded = priorityMenuExpanded,
                                onDismissRequest = { priorityMenuExpanded = false }
                            ) {
                                listOf("منخفضة", "عادية", "عالية").forEachIndexed { value, label ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            priority = value
                                            priorityMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("المعاينة والتعديل", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("${lines.size} طلب", style = MaterialTheme.typography.labelLarge)
                    }
                    if (validationMessage != null) {
                        Text(
                            validationMessage,
                            color = if (lines.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = true),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(lines, key = { it.first }) { (sourceLineIndex, title) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = title,
                                    onValueChange = { editedTitle ->
                                        val sourceLines = normalizedText.split('\n').toMutableList()
                                        if (sourceLineIndex in sourceLines.indices) {
                                            sourceLines[sourceLineIndex] = editedTitle
                                            text = sourceLines.joinToString("\n")
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = enabled,
                                    label = { Text("طلب ${lines.indexOfFirst { it.first == sourceLineIndex } + 1}") },
                                    singleLine = true,
                                    supportingText = if (title.length > MAX_ERRAND_TITLE_LENGTH) {
                                        { Text("${title.length}/$MAX_ERRAND_TITLE_LENGTH", color = MaterialTheme.colorScheme.error) }
                                    } else null
                                )
                                TextButton(
                                    onClick = {
                                        val sourceLines = normalizedText.split('\n').toMutableList()
                                        if (sourceLineIndex in sourceLines.indices) {
                                            sourceLines.removeAt(sourceLineIndex)
                                            text = sourceLines.joinToString("\n")
                                        }
                                    },
                                    enabled = enabled
                                ) {
                                    Text("حذف")
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) { Text("إلغاء") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = canSave,
                            onClick = {
                                val drafts = parseBulkErrands(normalizedText, selectedPlaceId, priority)
                                onSave(drafts)
                            }
                        ) {
                            Text("حفظ الدفعة")
                        }
                    }
                }
            }
        }
    }
}
