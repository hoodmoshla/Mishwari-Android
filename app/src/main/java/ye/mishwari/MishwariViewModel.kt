package ye.mishwari

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ye.mishwari.data.*
import ye.mishwari.geo.GeoScheduler
import ye.mishwari.ai.*
import java.io.File
import java.util.UUID

class MishwariViewModel @JvmOverloads constructor(
    app: Application,
    assistantStoreOverride: AssistantStore? = null,
    assistantProviderOverride: AssistantProvider? = null,
) : AndroidViewModel(app) {
    private val repository = Repository(MishwariDatabase.get(app))
    val errands = repository.errands.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val places = repository.places.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    // Do not present a false empty state before Room has emitted all persisted lists.
    val dataReady = combine(repository.errands, repository.places, repository.settings) { _, _, _ -> true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _message.asSharedFlow()
    val error = MutableStateFlow<String?>(null)
    var pendingPhoto: ((String) -> Unit)? = null

    // Isolated from Room and JSON backups; core CRUD never needs this store or network.
    private val assistantStore by lazy { assistantStoreOverride ?: AssistantStore(app) }
    private val assistantProvider: AssistantProvider = assistantProviderOverride ?: OpenAiCompatibleProvider()
    private val _assistantConfig = MutableStateFlow(AssistantConfig("", "", false, false))
    val assistantConfig = _assistantConfig.asStateFlow()
    private val _assistantBusy = MutableStateFlow(false)
    val assistantBusy = _assistantBusy.asStateFlow()
    private val _assistantMessage = MutableStateFlow<String?>(null)
    val assistantMessage = _assistantMessage.asStateFlow()
    private val _assistantSuggestions = MutableStateFlow<List<AssistantSuggestion>>(emptyList())
    val assistantSuggestions = _assistantSuggestions.asStateFlow()
    private val _assistantResult = MutableStateFlow<String?>(null)
    val assistantResult = _assistantResult.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { assistantStore.load() } }
                .onSuccess { _assistantConfig.value = it }
                .onFailure { _assistantMessage.value = "تعذّر قراءة إعدادات المساعد؛ وظائف التطبيق الأساسية متاحة" }
        }
    }

    private fun assistantTask(success: String, block: suspend () -> Unit) {
        if (_assistantBusy.value) return
        _assistantBusy.value = true
        _assistantMessage.value = null
        viewModelScope.launch {
            try {
                block()
                _assistantMessage.value = success
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _assistantMessage.value = when (e) {
                    is AssistantException -> e.message
                    is IllegalArgumentException -> e.message ?: "تحقق من إعدادات المساعد والبيانات"
                    else -> "تعذّرت العملية؛ تحقق من إعدادات المساعد أو أعد إدخال المفتاح"
                }
            } finally {
                _assistantBusy.value = false
                runCatching { withContext(Dispatchers.IO) { assistantStore.load() } }
                    .onSuccess { _assistantConfig.value = it }
            }
        }
    }

    fun saveAssistantConfig(endpoint: String, model: String, key: String?, enabled: Boolean) =
        assistantTask("حُفظت إعدادات المساعد. عند تغيير العنوان يُحذف المفتاح ويُوقف المساعد؛ أدخل المفتاح وفعّله من جديد.") {
            withContext(Dispatchers.IO) { assistantStore.save(endpoint.trim(), model.trim(), key, enabled) }
            clearAssistantResult()
        }

    fun deleteAssistantKey() = assistantTask("حُذف المفتاح وتعطّل المساعد") {
        withContext(Dispatchers.IO) { assistantStore.deleteKey() }
        clearAssistantResult()
    }

    fun testAssistantConnection() = assistantTask("نجح الاتصال بالمزود والنموذج المحددين") {
        withContext(Dispatchers.IO) {
            val config = assistantStore.load()
            val key = assistantStore.readKey() ?: throw IllegalArgumentException("أدخل مفتاح API واحفظه أولاً")
            assistantProvider.complete(config.endpoint, config.model, key, "أجب بكلمة واحدة فقط: جاهز")
        }
    }

    fun prepareAssistantPayload(mode: String, input: String, selectedErrands: List<Errand>, destinationId: String?): String =
        AssistantPayload.prepare(mode, input, selectedErrands, destinationId, places.value)

    /** Called only after the UI has displayed this exact payload and obtained send confirmation. */
    fun runAssistant(payload: String, mode: String) = assistantTask("وصلت اقتراحات غير مؤكدة؛ راجعها قبل استخدامها") {
        clearAssistantResult()
        val response = withContext(Dispatchers.IO) {
            require(mode in AssistantPayload.modes) { "اختر وظيفة المساعد" }
            require(org.json.JSONObject(payload).getString("mode") == mode) { "أعد مراجعة بيانات العملية" }
            val config = assistantStore.load()
            require(config.enabled) { "المساعد معطّل؛ فعّله من إعداداته أولاً" }
            val key = assistantStore.readKey() ?: throw IllegalArgumentException("أدخل مفتاح API واحفظه أولاً")
            val text = assistantProvider.complete(config.endpoint, config.model, key, payload, AssistantPayload.system(mode))
            AssistantSuggestionParser.parse(text, AssistantPayload.allowedPlaceIds(payload))
        }
        _assistantResult.value = response.advice
        _assistantSuggestions.value = if (mode == "capture") response.suggestions else emptyList()
    }

    /** Explicit final user action creates a NEW record; existing errands are never auto-updated. */
    fun acceptAssistantSuggestion(suggestion: AssistantSuggestion, onSaved: () -> Unit = {}) =
        assistantTask("حُفظ الطلب بعد موافقتك") {
            val errand = Errand(title = suggestion.title.trim(), description = suggestion.description.trim(),
                priority = suggestion.priority, dueDate = suggestion.dueDate, placeId = suggestion.placeId)
            withContext(Dispatchers.IO) { repository.saveErrand(errand) }
            _assistantSuggestions.value = _assistantSuggestions.value.filterNot { it == suggestion }
            onSaved()
            runCatching { GeoScheduler.schedule(getApplication()) }
        }

    fun dismissAssistantSuggestion(suggestion: AssistantSuggestion) {
        _assistantSuggestions.value = _assistantSuggestions.value.filterNot { it == suggestion }
    }

    fun clearAssistantResult() {
        _assistantSuggestions.value = emptyList()
        _assistantResult.value = null
    }

    fun action(success: String = "تم الحفظ", onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            error.value = null
            try {
                withContext(Dispatchers.IO) { block() }
                onSuccess()
                _message.emit(success)
                runCatching { GeoScheduler.schedule(getApplication()) }.onFailure {
                    error.value = "تمت العملية وحُفظت البيانات، لكن تعذّر تحديث تنبيهات الوصول. أعد فتح التطبيق وراجع إعدادات التنبيهات."
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error.value = "تعذّر التنفيذ: ${e.localizedMessage ?: "تحقق من البيانات وحاول مجددًا"}"
            }
        }
    }
    fun save(e: Errand, onSaved: () -> Unit = {}) = action(onSuccess = onSaved) { repository.saveErrand(e) }
    fun remove(e: Errand) = action("تم حذف الطلب") { repository.removeErrand(e) }
    fun save(p: Place, onSaved: () -> Unit = {}) = action(onSuccess = onSaved) { repository.savePlace(p) }
    fun remove(p: Place) = action("تم حذف المكان؛ الطلبات محفوظة دون وجهة") { repository.removePlace(p) }
    fun setting(key: String, value: String) = action { repository.setSetting(key, value) }
    fun refreshAlerts() = action("طُلبت إعادة تهيئة التنبيهات؛ راجع حالة التسجيل") {
        GeoScheduler.schedule(getApplication(), force = true, rearmSelected = true)
    }
    fun export(uri: Uri) = action("تم تصدير النسخة المحلية") {
        val app = getApplication<Application>()
        val data = repository.exportBackup(app)
        require(data.toByteArray(Charsets.UTF_8).size <= 25 * 1024 * 1024) { "النسخة تتجاوز حد 25 ميغابايت؛ قلّل الصور المرفقة قبل التصدير" }
        requireNotNull(app.contentResolver.openOutputStream(uri, "wt")) { "تعذّر فتح الملف" }.bufferedWriter(Charsets.UTF_8).use { it.write(data) }
    }
    fun importBackup(uri: Uri) = action("تم استيراد النسخة المحلية") {
        val app = getApplication<Application>()
        val bytes = requireNotNull(app.contentResolver.openInputStream(uri)).use { it.readBounded(25 * 1024 * 1024) }
        require(bytes.size <= 25 * 1024 * 1024) { "الملف أكبر من 25 ميغابايت" }
        repository.importBackup(app, bytes.toString(Charsets.UTF_8))
    }
    fun importDirectory(uri: Uri) = action("تم تحديث دليل الأماكن") {
        val app = getApplication<Application>()
        val bytes = requireNotNull(app.contentResolver.openInputStream(uri)).use { it.readBounded(8 * 1024 * 1024) }
        require(bytes.size <= 8 * 1024 * 1024) { "ملف الدليل كبير جدًا" }
        repository.importDirectory(bytes.toString(Charsets.UTF_8))
    }
    fun installBundledDirectory() = action("تم استيراد الدليل المرفق") {
        val app = getApplication<Application>()
        repository.importDirectory(app.assets.open("yemen-directory.json").bufferedReader().use { it.readText() })
    }
    fun importPhoto(uri: Uri) = action("تم إرفاق الصورة؛ احفظ الطلب للاحتفاظ بها") {
        val app = getApplication<Application>()
        val bytes = requireNotNull(app.contentResolver.openInputStream(uri)).use { it.readBounded(5 * 1024 * 1024) }
        require(bytes.size <= 5 * 1024 * 1024) { "اختر صورة أقل من 5 ميغابايت" }
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..16000 && options.outHeight in 1..16000) { "الصورة غير صالحة أو كبيرة الأبعاد" }
        val folder = File(app.filesDir, "photos").apply { mkdirs() }
        val file = File(folder, "${UUID.randomUUID()}.img").apply { writeBytes(bytes) }
        withContext(Dispatchers.Main) { pendingPhoto?.invoke(file.absolutePath); pendingPhoto = null }
    }
}

private fun java.io.InputStream.readBounded(maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= maximum) { "الملف أكبر من الحد المسموح" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
