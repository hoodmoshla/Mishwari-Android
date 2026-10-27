@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ye.mishwari.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ye.mishwari.MishwariViewModel
import ye.mishwari.ai.AssistantProviderIds

private const val GEMINI_OPENAI_COMPATIBLE_ENDPOINT =
    "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
private const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"

/** Central assistant configuration. Credentials are never loaded into Compose state from storage. */
@Composable
fun AssistantSettingsSection(model: MishwariViewModel, modifier: Modifier = Modifier) {
    val config by model.assistantConfig.collectAsStateWithLifecycle()
    val busy by model.assistantBusy.collectAsStateWithLifecycle()
    val message by model.assistantMessage.collectAsStateWithLifecycle()

    var endpoint by rememberSaveable { mutableStateOf("") }
    var modelName by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf(AssistantProviderIds.OPENAI_COMPATIBLE) }
    var enabled by rememberSaveable { mutableStateOf(false) }
    // Deliberately remember, not rememberSaveable. The credential is never returned in AssistantConfig.
    var apiKey by remember { mutableStateOf("") }
    var testSucceeded by remember { mutableStateOf(false) }
    var showReplaceConfirmation by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(config.endpoint, config.model, config.enabled, config.provider) {
        provider = config.provider
        endpoint = if (config.provider == AssistantProviderIds.GEMINI) {
            GEMINI_OPENAI_COMPATIBLE_ENDPOINT
        } else config.endpoint
        modelName = if (config.provider == AssistantProviderIds.GEMINI && config.model.isBlank()) {
            DEFAULT_GEMINI_MODEL
        } else config.model
        enabled = config.enabled
    }
    val identityUnchanged = endpoint == config.endpoint && provider == config.provider
    val canUseSavedKey = config.hasKey && identityUnchanged
    val enteredKey = apiKey.isNotBlank()
    val hasCurrentKey = enteredKey || canUseSavedKey
    val endpointValid = endpoint.isAssistantEndpoint()
    val modelValid = modelName.length <= 200 && modelName.none { it == '\r' || it == '\n' || it == '\u0000' }
    val hasSavedValidConfiguration = config.hasKey && config.endpoint.isAssistantEndpoint()
    val replacingConfiguration = hasSavedValidConfiguration && (
        endpoint != config.endpoint || modelName != config.model || provider != config.provider || enteredKey
    )

    fun invalidateTest() {
        testSucceeded = false
    }

    fun saveConfiguration() {
        model.saveAssistantConfig(
            endpoint.trim(),
            modelName.trim(),
            apiKey.takeIf { it.isNotBlank() },
            enabled,
            provider,
        )
        apiKey = ""
        invalidateTest()
    }

    Card(modifier.fillMaxWidth().testTag("assistantSettingsSection")) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("الذكاء الاصطناعي", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "اختياري. لا تُرسل بياناتك عند فتح الإعدادات أو حفظها. قد تترتب على استخدام API تكلفة لدى مزود الخدمة؛ راجع أسعاره وسياسة الخصوصية.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("المزوّد", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = provider == AssistantProviderIds.OPENAI_COMPATIBLE,
                    onClick = {
                        provider = AssistantProviderIds.OPENAI_COMPATIBLE
                        endpoint = if (config.provider == AssistantProviderIds.OPENAI_COMPATIBLE) config.endpoint else ""
                        if (provider != config.provider || endpoint != config.endpoint) enabled = false
                        invalidateTest()
                    },
                    label = { Text("OpenAI-compatible") },
                    modifier = Modifier.testTag("assistantProviderOpenAiCompatible"),
                )
                FilterChip(
                    selected = provider == AssistantProviderIds.GEMINI,
                    onClick = {
                        provider = AssistantProviderIds.GEMINI
                        endpoint = GEMINI_OPENAI_COMPATIBLE_ENDPOINT
                        if (modelName.isBlank() || config.provider != AssistantProviderIds.GEMINI) {
                            modelName = DEFAULT_GEMINI_MODEL
                        }
                        if (provider != config.provider || endpoint != config.endpoint) enabled = false
                        invalidateTest()
                    },
                    label = { Text("Gemini") },
                    modifier = Modifier.testTag("assistantProviderGemini"),
                )
            }

            if (provider == AssistantProviderIds.GEMINI) {
                Text(
                    "يستخدم Gemini مسار OpenAI-compatible الرسمي ويرسل مفتاح API في ترويسة Bearer.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(
                value = endpoint,
                onValueChange = {
                    endpoint = it
                    if (endpoint != config.endpoint || provider != config.provider) enabled = false
                    invalidateTest()
                },
                readOnly = provider == AssistantProviderIds.GEMINI,
                label = { Text(if (provider == AssistantProviderIds.GEMINI) "عنوان Gemini الرسمي" else "عنوان HTTPS للـAPI") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("assistantSettingsEndpoint"),
            )
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it; invalidateTest() },
                label = { Text("النموذج") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("assistantSettingsModel"),
            )

            if (config.hasKey) {
                Text(
                    if (identityUnchanged) "المفتاح محفوظ" else "يوجد مفتاح محفوظ للإعداد السابق؛ لن يُعاد استخدامه بعد تغيير المزوّد أو العنوان.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("assistantSavedKeyStatus"),
                )
            } else {
                Text("لا يوجد مفتاح محفوظ", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; invalidateTest() },
                label = { Text(if (config.hasKey) "استبدال مفتاح API" else "مفتاح API") },
                placeholder = { Text(if (config.hasKey) "لن نعرض المفتاح المحفوظ" else "أدخله لحفظه مشفرًا على هذا الجهاز") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("assistantSettingsKey"),
            )
            Text(
                "المفتاح لا يُعرض مجددًا، ولا يُحفظ في حالة الاستعادة أو النسخ الاحتياطي أو Room؛ يُشفّر محليًا عبر Android Keystore.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    enabled = !busy && hasCurrentKey,
                    modifier = Modifier.testTag("assistantSettingsEnabled"),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (enabled) "التفعيل الصريح مفعّل" else "المساعد معطّل")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        if (replacingConfiguration && !testSucceeded) showReplaceConfirmation = true
                        else saveConfiguration()
                    },
                    enabled = !busy && endpointValid && modelValid && (!enabled || hasCurrentKey) &&
                        (apiKey.isBlank() || (apiKey.length <= 4096 && apiKey.none { it == '\r' || it == '\n' || it == '\u0000' })),
                    modifier = Modifier.weight(1f).testTag("assistantSettingsSave"),
                ) { Text("حفظ الإعدادات") }
                OutlinedButton(
                    onClick = {
                        testSucceeded = false
                        model.testAssistantConnection(endpoint.trim(), modelName.trim(), apiKey.takeIf { it.isNotBlank() }, provider) {
                            testSucceeded = true
                        }
                    },
                    enabled = !busy && endpointValid && modelValid && hasCurrentKey &&
                        (identityUnchanged || enteredKey),
                    modifier = Modifier.weight(1f).testTag("assistantSettingsTest"),
                ) { Text("اختبار الاتصال") }
            }
            Text(
                "الاختبار يرسل نصًا اصطناعيًا فقط، ولا يحفظ المفتاح أو يغيّر الإعدادات ولا يرسل طلباتك أو أماكنك.",
                style = MaterialTheme.typography.bodySmall,
            )
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("assistantSettingsMessage")) }

            if (config.hasKey) {
                TextButton(
                    onClick = { showDeleteConfirmation = true },
                    enabled = !busy,
                    modifier = Modifier.testTag("assistantSettingsDeleteKey"),
                ) { Text("حذف المفتاح المحفوظ") }
            }
        }
    }

    if (showReplaceConfirmation) {
        AlertDialog(
            onDismissRequest = { showReplaceConfirmation = false },
            title = { Text("تأكيد استبدال إعداد محفوظ") },
            text = { Text("لم ينجح اختبار الاتصال بهذه الإعدادات أو لم يُجرَ. هل تريد المتابعة؟ قد يتوقف المساعد أو لا يعمل الإعداد الجديد.") },
            confirmButton = {
                TextButton(onClick = { showReplaceConfirmation = false; saveConfiguration() }) { Text("متابعة الاستبدال") }
            },
            dismissButton = { TextButton(onClick = { showReplaceConfirmation = false }) { Text("إلغاء") } },
            modifier = Modifier.testTag("assistantSettingsReplaceConfirmation"),
        )
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("حذف المفتاح؟") },
            text = { Text("سيُحذف المفتاح المشفر ويُعطّل المساعد.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    model.deleteAssistantKey()
                    apiKey = ""
                    invalidateTest()
                }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("إلغاء") } },
            modifier = Modifier.testTag("assistantSettingsDeleteConfirmation"),
        )
    }
}
