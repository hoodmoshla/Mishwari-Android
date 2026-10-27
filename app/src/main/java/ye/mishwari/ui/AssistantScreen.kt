@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ye.mishwari.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ye.mishwari.MishwariViewModel
import ye.mishwari.ai.AssistantPayload
import ye.mishwari.data.Errand
import ye.mishwari.data.Place
import ye.mishwari.ai.AssistantSuggestion
import ye.mishwari.input.InputIntents
import java.net.URI

private data class AssistantMode(val id: String, val title: String, val hint: String)
private val assistantModes = listOf(
    AssistantMode("capture", "نص طبيعي", "اكتب ما تريد من المساعد"),
    AssistantMode("priorities", "الأولويات والمواعيد", "ما الذي تريد ترتيبه أو السؤال عنه؟"),
    AssistantMode("group", "تجميع الطلبات", "كيف تريد تجميع طلباتك؟"),
    AssistantMode("trip", "قائمة الوجهة", "اختر وجهة، ويمكنك إضافة توجيه اختياري"),
    AssistantMode("order", "اقتراح الترتيب", "ما القيود أو التفضيلات التي تريد مراعاتها؟"),
)

/**
 * Assistant UI only. It never loads a key into UI state; the password field is deliberately not
 * saveable. Local errands are sent only when the user selects specific items and reviews the
 * exact payload produced by the ViewModel before confirming transmission.
 */
@Composable
fun AssistantScreen(
    model: MishwariViewModel,
    errands: List<Errand>,
    places: List<Place>,
    requestVoice: () -> Unit,
    showConfiguration: Boolean = true,
    openAiSettings: () -> Unit = {},
) {
    val config by model.assistantConfig.collectAsStateWithLifecycle()
    val busy by model.assistantBusy.collectAsStateWithLifecycle()
    val message by model.assistantMessage.collectAsStateWithLifecycle()
    val suggestions by model.assistantSuggestions.collectAsStateWithLifecycle()
    val result by model.assistantResult.collectAsStateWithLifecycle()

    var endpoint by rememberSaveable { mutableStateOf("") }
    var modelName by rememberSaveable { mutableStateOf("") }
    // Never use rememberSaveable for credentials. The saved key is never returned by AssistantConfig.
    var apiKey by remember { mutableStateOf("") }
    var enabled by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf("capture") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var selectedIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var destinationId by rememberSaveable { mutableStateOf("") }
    var destinationMenu by remember { mutableStateOf(false) }
    var reviewPayload by remember { mutableStateOf<String?>(null) }
    var showSystemPrompt by remember { mutableStateOf(false) }
    var explainSpeech by remember { mutableStateOf(false) }
    var deleteConfirmation by remember { mutableStateOf(false) }
    var editingSuggestion by remember { mutableStateOf<AssistantSuggestion?>(null) }
    var speechMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(config.endpoint, config.model, config.enabled) {
        endpoint = config.endpoint
        modelName = config.model
        enabled = config.enabled
    }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultData ->
        val parsed = InputIntents.readSpeechResult(resultData.resultCode, resultData.data)
        if (parsed.text != null) {
            prompt = listOf(prompt, parsed.text).filter { it.isNotBlank() }.joinToString("\n").take(InputIntents.MAX_DRAFT_LENGTH)
            speechMessage = "أُضيف النص إلى المسودة فقط؛ راجعه واختر ما تريد إرساله. لم يُحفظ أو يُرسل تلقائيًا."
        } else speechMessage = parsed.message
    }

    val settingsUnchanged = endpoint.trim() == config.endpoint && modelName.trim() == config.model &&
        apiKey.isBlank() && enabled == config.enabled
    val tripExcludedSelectionCount = if (mode == "trip" && destinationId.isNotBlank()) {
        val relatedIds = subtree(destinationId, places)
        selectedIds.count { id -> errands.firstOrNull { it.id == id }?.placeId !in relatedIds }
    } else 0

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("assistantScreen"),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("مساعد المشوار الذكي", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("اختياري ومعطّل افتراضيًا. لا يُرسل شيء عند فتح الشاشة أو حفظ الإعدادات.", style = MaterialTheme.typography.bodyMedium)
        }
        if (!showConfiguration) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (config.enabled && config.hasKey) "المساعد جاهز؛ اختر ما تريد إرساله وراجع الحمولة قبل الإرسال."
                        else "المساعد غير مهيأ بعد. أضف المزود والمفتاح من إعدادات الذكاء الاصطناعي.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = openAiSettings, enabled = !busy) { Text("إعدادات الذكاء الاصطناعي") }
                }
            }
        }
        if (showConfiguration) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("إعداد الاتصال", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("أدخل رابط HTTPS الكامل لمزود تثق به كما أعطاك إياه، دون بيانات اعتماد أو معاملات query. لا ترسل بيانات إلى عنوان غير موثوق.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(endpoint, { endpoint = it }, label = { Text("رابط HTTPS الكامل للـAPI") }, placeholder = { Text("https://example.com/v1/chat/completions") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("assistantEndpoint"))
                    OutlinedTextField(modelName, { modelName = it }, label = { Text("اسم النموذج (اختياري حسب المزود)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("assistantModel"))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(if (config.hasKey) "مفتاح API · محفوظ مسبقًا، أدخل بديلًا للتغيير" else "مفتاح API") },
                        placeholder = { Text(if (config.hasKey) "لن نعرض المفتاح المحفوظ" else "يُحفظ مشفرًا على هذا الجهاز") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("assistantKey"),
                    )
                    Text("المفتاح لا يُعرض مجددًا ولا يُحفظ ضمن النسخ الاحتياطية؛ يُشفّر محليًا باستخدام Android Keystore، وهذا لا يضمن الحماية من كل صور استخراج الجهاز. لا تكتبه إلا إذا وثقت بمزود الخدمة.", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy, modifier = Modifier.testTag("assistantEnabled"))
                        Spacer(Modifier.width(8.dp))
                        Text(if (enabled) "تفعيل المساعد صراحةً" else "المساعد معطّل")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                model.saveAssistantConfig(endpoint.trim(), modelName.trim(), apiKey.takeIf { it.isNotBlank() }, enabled)
                                apiKey = ""
                            },
                            enabled = !busy && endpoint.isAssistantEndpoint() && modelName.length <= 128 && (!enabled || apiKey.isNotBlank() || (config.hasKey && config.endpoint == endpoint.trim())),
                            modifier = Modifier.weight(1f).testTag("assistantSaveConfig"),
                        ) { Text("حفظ الإعدادات") }
                        OutlinedButton(onClick = model::testAssistantConnection, enabled = !busy && settingsUnchanged && config.hasKey && config.endpoint.isAssistantEndpoint(), modifier = Modifier.weight(1f).testTag("assistantTestConnection")) { Text("اختبار آمن") }
                    }
                    Text("اختبار الاتصال يرسل رسالة اصطناعية قصيرة فقط، ولا يرسل طلباتك أو أماكنك.", style = MaterialTheme.typography.bodySmall)
                    if (config.hasKey) Text("يوجد مفتاح محفوظ · لن نعرض قيمته", style = MaterialTheme.typography.bodySmall)
                    if (config.hasKey) TextButton(onClick = { deleteConfirmation = true }, enabled = !busy, modifier = Modifier.testTag("assistantDeleteKey")) { Text("حذف المفتاح المحفوظ") }
                }
            }
        }
        item {
            Text("ما الذي تريد فعله؟", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                assistantModes.forEach { option ->
                    FilterChip(selected = mode == option.id, onClick = { mode = option.id }, label = { Text(option.title) }, modifier = Modifier.testTag("assistantMode_${option.id}"))
                }
            }
        }
        if (mode == "trip") item {
            ExposedDropdownMenuBox(expanded = destinationMenu, onExpandedChange = { destinationMenu = it }) {
                OutlinedTextField(
                    value = places.firstOrNull { it.id == destinationId }?.let(::placeLabel) ?: "اختر وجهة",
                    onValueChange = {}, readOnly = true, label = { Text("الوجهة المطلوبة") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(destinationMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth().testTag("assistantDestination"),
                )
                ExposedDropdownMenu(expanded = destinationMenu, onDismissRequest = { destinationMenu = false }) {
                    DropdownMenuItem(text = { Text("بدون وجهة محددة") }, onClick = { destinationId = ""; destinationMenu = false })
                    places.sortedBy(::placeLabel).forEach { place -> DropdownMenuItem(text = { Text(placeLabel(place)) }, onClick = { destinationId = place.id; destinationMenu = false }) }
                }
            }
        }
        item {
            OutlinedTextField(prompt, { prompt = it.take(InputIntents.MAX_DRAFT_LENGTH) }, label = { Text(assistantModes.first { it.id == mode }.hint) }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth().testTag("assistantPrompt"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { explainSpeech = true }, enabled = !busy, modifier = Modifier.testTag("assistantVoice")) { Text("إملاء صوتي") }
                Spacer(Modifier.width(8.dp))
                Text("الصوت يُحوّل إلى مسودة للمراجعة فقط.", style = MaterialTheme.typography.bodySmall)
            }
            speechMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        item {
            Text("طلبات تختارها أنت فقط · ${selectedIds.size} محدد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("لا نرسل الدليل الكامل أو الصور أو الإحداثيات. اترك الاختيار فارغًا إن لم ترد تضمين طلباتك.", style = MaterialTheme.typography.bodySmall)
            if (mode == "trip") Text(
                if (tripExcludedSelectionCount > 0) "تُستبعد الطلبات المختارة غير المرتبطة بهذه الوجهة ($tripExcludedSelectionCount طلب)."
                else "في وضع قائمة الوجهة تُستبعد الطلبات المختارة غير المرتبطة بهذه الوجهة.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(errands, key = { "assistant-errand-${it.id}" }) { errand ->
            val checked = errand.id in selectedIds
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { selectedIds = if (it) selectedIds + errand.id else selectedIds - errand.id }, modifier = Modifier.testTag("assistantSelect_${errand.id}"))
                    Column(Modifier.weight(1f)) {
                        Text(errand.title, fontWeight = FontWeight.Medium)
                        if (errand.description.isNotBlank()) Text(errand.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text("${errand.priority.assistantPriorityLabel()} · ${errand.dueDate.ifBlank { "بلا موعد" }}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Button(
                onClick = {
                    val selected = errands.filter { it.id in selectedIds }
                    reviewPayload = runCatching { model.prepareAssistantPayload(mode, prompt.trim(), selected, destinationId.takeIf { it.isNotBlank() }) }
                        .getOrElse { "تعذّر تجهيز المراجعة: ${it.message ?: "تحقق من المدخلات"}" }
                },
                enabled = !busy && config.enabled && config.hasKey && settingsUnchanged && config.endpoint.isAssistantEndpoint() && (mode != "trip" || destinationId.isNotBlank()) && (prompt.isNotBlank() || selectedIds.isNotEmpty() || mode == "trip"),
                modifier = Modifier.fillMaxWidth().testTag("assistantReviewPayload"),
            ) { Text("مراجعة البيانات قبل الإرسال") }
            if (!settingsUnchanged) Text("احفظ الإعدادات أولًا؛ الاختبار والإرسال يستخدمان الإعدادات المحفوظة فقط.", style = MaterialTheme.typography.bodySmall)
            Text("لن يبدأ أي طلب حتى تراجع الحمولة كاملة وتضغط موافقة الإرسال.", style = MaterialTheme.typography.bodySmall)
        }
        message?.let { status -> item { Text(status, Modifier.fillMaxWidth().testTag("assistantStatus"), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (busy) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Text("جارٍ تنفيذ الطلب…") } }
        result?.let { response ->
            item {
                OutlinedCard(Modifier.fillMaxWidth().testTag("assistantResult")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("الرد · راجعه بنفسك", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(response)
                        TextButton(onClick = model::clearAssistantResult) { Text("مسح الرد") }
                    }
                }
            }
        }
        if (suggestions.isNotEmpty()) item {
            Text("اقتراحات غير مؤكدة · لا تُطبّق إلا بعد مراجعتك وموافقتك", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        items(suggestions) { suggestion ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(suggestion.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (suggestion.errandId != null) Text("تحديث الطلب الأصلي · ${suggestion.errandId}", style = MaterialTheme.typography.labelSmall)
                    Text(suggestion.description)
                    Text("أولوية ${suggestion.priority.assistantPriorityLabel()} مقترحة · ${suggestion.dueDate.ifBlank { "بلا موعد مقترح" }} · ${places.firstOrNull { it.id == suggestion.placeId }?.let(::placeLabel) ?: "بلا وجهة"}", style = MaterialTheme.typography.bodySmall)
                    Text("هذه توصية غير مؤكدة وليست ترتيب مسار أو تغييرًا للبيانات.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { editingSuggestion = suggestion }, enabled = !busy, modifier = Modifier.testTag("assistantEditSuggestion")) { Text("مراجعة وتعديل") }
                }
            }
        }
    }

    reviewPayload?.let { payload ->
        AlertDialog(
            onDismissRequest = { if (!busy) reviewPayload = null },
            title = { Text("راجع الحمولة قبل إرسالها") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("الوجهة المحفوظة: ${config.endpoint}\nالنموذج: ${config.model.ifBlank { "يحدده المزود" }}\n\nهذه بياناتك التي ستُرسل حرفيًا. تُرفق بها تعليمات ثابتة لتنظيم الاقتراحات ومنع ادعاء التنفيذ، دون بيانات شخصية إضافية. لا تُرسل الصور أو الإحداثيات أو قائمة دليل الأماكن.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer { Text(payload, Modifier.fillMaxWidth().testTag("assistantPayloadText"), style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { showSystemPrompt = !showSystemPrompt }) {
                        Text(if (showSystemPrompt) "إخفاء التعليمات الثابتة" else "عرض التعليمات الثابتة المرفقة")
                    }
                    if (showSystemPrompt) SelectionContainer {
                        Text(AssistantPayload.system(mode), Modifier.fillMaxWidth().testTag("assistantSystemPrompt"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { reviewPayload = null; model.runAssistant(payload, mode) }, enabled = !busy && !payload.startsWith("تعذّر تجهيز المراجعة:") , modifier = Modifier.testTag("assistantConfirmSend")) { Text("أوافق، أرسل هذه البيانات") } },
            dismissButton = { TextButton(onClick = { reviewPayload = null }, enabled = !busy) { Text("إلغاء") } },
            modifier = Modifier.testTag("assistantPayloadReviewDialog"),
        )
    }

    explainSpeech.let { show -> if (show) AlertDialog(
        onDismissRequest = { explainSpeech = false },
        title = { Text("إملاء مسودة للمساعد") },
        text = { Text("سيُفتح تطبيق التعرف على الكلام بالعربية. قد يعالج مزود التعرف الصوت عبر الإنترنت حسب إعداداته. التطبيق لا يسجل الصوت ولا يضيف النص للحمولة إلا بعد مراجعتك؛ لن يُحفظ أو يُرسل تلقائيًا، ولا يُطلب إذن ميكروفون إضافي من التطبيق.") },
        confirmButton = { TextButton(onClick = {
            explainSpeech = false
            // Retain the supplied callback for host integrations; screen-owned ActivityResult keeps
            // the transcript in this prompt, never in the regular errand editor.
            try { speechLauncher.launch(InputIntents.createArabicSpeechIntent()) }
            catch (_: ActivityNotFoundException) { speechMessage = "لا يتوفر تطبيق للتعرف على الكلام؛ اكتب النص يدويًا." }
            catch (_: SecurityException) { speechMessage = "تعذّر فتح خدمة التعرف على الكلام؛ اكتب النص يدويًا." }
        }, modifier = Modifier.testTag("assistantVoiceConfirm")) { Text("متابعة إلى الإملاء") } },
        dismissButton = { TextButton(onClick = { explainSpeech = false }) { Text("إلغاء") } },
    ) }

    if (deleteConfirmation) AlertDialog(
        onDismissRequest = { deleteConfirmation = false },
        title = { Text("حذف مفتاح المساعد؟") },
        text = { Text("سيُحذف المفتاح المحفوظ ويُعطّل المساعد. لن تُمس الطلبات أو الأماكن.") },
        confirmButton = { TextButton(onClick = { deleteConfirmation = false; apiKey = ""; enabled = false; model.deleteAssistantKey() }, enabled = !busy) { Text("حذف المفتاح") } },
        dismissButton = { TextButton(onClick = { deleteConfirmation = false }) { Text("إلغاء") } },
    )

    editingSuggestion?.let { suggestion ->
        AssistantSuggestionEditor(
            suggestion = suggestion,
            places = places.filter { it.id in model.reviewableAssistantPlaceIds() },
            onDismiss = { editingSuggestion = null },
            onSave = { revised -> model.acceptReviewedAssistantSuggestion(suggestion, revised) {
                editingSuggestion = null
            } },
        )
    }
}

internal fun String.isAssistantEndpoint(): Boolean = runCatching {
    val uri = URI(trim())
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.fragment == null && length <= 2048
}.getOrDefault(false)

private fun Int.assistantPriorityLabel(): String = when (this) {
    0 -> "منخفضة"
    2 -> "مهمة"
    else -> "عادية"
}

@Composable
private fun AssistantSuggestionEditor(
    suggestion: AssistantSuggestion,
    places: List<Place>,
    onDismiss: () -> Unit,
    onSave: (AssistantSuggestion) -> Unit,
) {
    var title by remember(suggestion) { mutableStateOf(suggestion.title) }
    var description by remember(suggestion) { mutableStateOf(suggestion.description) }
    var priority by remember(suggestion) { mutableIntStateOf(suggestion.priority.coerceIn(0, 2)) }
    var dueDate by remember(suggestion) { mutableStateOf(suggestion.dueDate) }
    var placeId by remember(suggestion) { mutableStateOf(suggestion.placeId.orEmpty()) }
    var placeMenu by remember { mutableStateOf(false) }
    val capture = suggestion.mode == "capture"
    val editPriority = capture || suggestion.mode in setOf("priorities", "trip")
    val editPlace = capture || suggestion.mode == "group"
    val validDate = dueDate.isBlank() || runCatching {
        dueDate.length == 10 && java.time.LocalDate.parse(dueDate).toString() == dueDate
    }.getOrDefault(false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (capture) "راجع اقتراحًا قبل إنشاء طلب" else "راجع تحديث الطلب الأصلي") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (capture) "سيُنشأ طلب جديد فقط بعد موافقتك؛ لا يتغير أي طلب موجود."
                    else "سيُحدّث الطلب «${suggestion.title}» (${suggestion.errandId}) فقط بعد موافقتك. تبقى الصور والملاحظات وبقية التفاصيل محفوظة.", style = MaterialTheme.typography.bodySmall)
                if (capture) {
                OutlinedTextField(title, { title = it.take(200) }, label = { Text("العنوان") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it.take(2000) }, label = { Text("الوصف") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
                if (editPriority) {
                Text("الأولوية")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = priority == 0, onClick = { priority = 0 }, label = { Text("منخفضة") })
                    FilterChip(selected = priority == 1, onClick = { priority = 1 }, label = { Text("عادية") })
                    FilterChip(selected = priority == 2, onClick = { priority = 2 }, label = { Text("مهمة") })
                }
                OutlinedTextField(dueDate, { dueDate = it.take(10) }, label = { Text("الموعد · YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!validDate) Text("أدخل تاريخًا صحيحًا بصيغة YYYY-MM-DD أو اترك الموعد فارغًا.", color = MaterialTheme.colorScheme.error)
                }
                if (editPlace) {
                ExposedDropdownMenuBox(expanded = placeMenu, onExpandedChange = { placeMenu = it }) {
                    OutlinedTextField(value = places.firstOrNull { it.id == placeId }?.let(::placeLabel) ?: "بدون وجهة", onValueChange = {}, readOnly = true, label = { Text("الوجهة") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(placeMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = placeMenu, onDismissRequest = { placeMenu = false }) {
                        DropdownMenuItem(text = { Text("بدون وجهة") }, onClick = { placeId = ""; placeMenu = false })
                        places.sortedBy(::placeLabel).forEach { place -> DropdownMenuItem(text = { Text(placeLabel(place)) }, onClick = { placeId = place.id; placeMenu = false }) }
                    }
                }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(suggestion.copy(title = title.trim(), description = description.trim(), priority = priority, dueDate = dueDate.trim(), placeId = placeId.takeIf { it.isNotBlank() })) }, enabled = title.isNotBlank() && (!editPriority || validDate), modifier = Modifier.testTag(if (capture) "assistantSaveAsNewErrand" else "assistantApplyToOriginal")) { Text(if (capture) "حفظ كطلب جديد" else "أوافق، حدّث الطلب الأصلي") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
