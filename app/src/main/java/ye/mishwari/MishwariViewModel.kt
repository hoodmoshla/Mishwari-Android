package ye.mishwari

import android.app.Application
import android.net.Uri
import androidx.room.withTransaction
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
    private val database = MishwariDatabase.get(app)
    private val repository = Repository(database)
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
    private val draftPhotoPaths = linkedSetOf<String>()

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
    private var approvedAssistantPayload: String? = null
    private val consumedAssistantProposalIds = mutableSetOf<String>()

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
            AssistantSuggestionParser.parse(text, mode, payload)
        }
        approvedAssistantPayload = payload
        _assistantResult.value = response.advice
        _assistantSuggestions.value = response.suggestions
    }

    /** Convenience for an unchanged pending proposal; no unreviewed object can be persisted. */
    fun acceptAssistantSuggestion(suggestion: AssistantSuggestion, onSaved: () -> Unit = {}) =
        acceptReviewedAssistantSuggestion(suggestion, suggestion, onSaved)

    /** Called only by the final explicit save button, with the original pending proposal identity. */
    fun acceptReviewedAssistantSuggestion(
        original: AssistantSuggestion,
        revised: AssistantSuggestion,
        onSaved: () -> Unit = {},
    ) = assistantTask("حُفظ الطلب بعد موافقتك") {
            val payload = requireNotNull(approvedAssistantPayload) { "أعد مراجعة الاقتراح" }
            val pending = _assistantSuggestions.value
            require(pending.any { it === original }) { "الاقتراح لم يعد معلقًا أو سبق اعتماده" }
            require(revised.proposalId == original.proposalId && revised.errandId == original.errandId && revised.mode == original.mode) {
                "لا يمكن تغيير هوية الاقتراح أثناء مراجعته"
            }
            val acceptedId = withContext(Dispatchers.IO) {
                database.withTransaction {
                    val current = database.dao().allErrands()
                    val accepted = if (original.mode == "capture") {
                        val planned = AssistantProposalApplier.acceptCapture(original, payload, true, consumedAssistantProposalIds)
                        require(revised.placeId == null || revised.placeId in AssistantPayload.allowedPlaceIds(payload)) {
                            "الوجهة ليست ضمن البيانات التي راجعتها"
                        }
                        val fresh = planned.errand.copy(title = revised.title.trim(), description = revised.description.trim(),
                            priority = revised.priority, dueDate = revised.dueDate, placeId = revised.placeId)
                        val normalizedTitle = ArabicPlaceMatcher.normalize(fresh.title)
                        require(current.none { it.status !in setOf("مكتمل", "ملغى") && it.placeId == fresh.placeId &&
                            ArabicPlaceMatcher.normalize(it.title) == normalizedTitle }) { "يوجد طلب غير منجز بالعنوان والوجهة نفسيهما؛ لم يُنشأ طلب مكرر" }
                        planned.copy(errand = fresh)
                    } else {
                        val stored = current.singleOrNull { it.id == original.errandId }
                            ?: throw IllegalArgumentException("الطلب الأصلي لم يعد موجودًا؛ لن يُنشأ بديل عنه")
                        AssistantProposalApplier.acceptExisting(original.mode, payload, pending, original, revised, stored,
                            true, consumedAssistantProposalIds)
                    }
                    repository.saveErrand(accepted.errand)
                    accepted.consumedProposalId
                }
            }
            consumedAssistantProposalIds += acceptedId
            _assistantSuggestions.value = _assistantSuggestions.value.filterNot { it.proposalId == original.proposalId }
            onSaved()
            runCatching { GeoScheduler.schedule(getApplication()) }
        }

    fun dismissAssistantSuggestion(suggestion: AssistantSuggestion) {
        _assistantSuggestions.value = _assistantSuggestions.value.filterNot { it == suggestion }
    }

    fun reviewableAssistantPlaceIds(): Set<String> = approvedAssistantPayload?.let {
        runCatching { AssistantPayload.allowedPlaceIds(it) }.getOrDefault(emptySet())
    }.orEmpty()

    fun clearAssistantResult() {
        _assistantSuggestions.value = emptyList()
        _assistantResult.value = null
        approvedAssistantPayload = null
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
    fun save(e: Errand, onSaved: () -> Unit = {}) {
        val protected = draftPhotoPaths.toSet()
        action(onSuccess = onSaved) { repository.saveErrand(e, getApplication(), protected) }
    }
    fun remove(e: Errand) {
        val protected = draftPhotoPaths.toSet()
        action("تم حذف الطلب") { repository.removeErrand(e, getApplication(), protected) }
    }
    fun save(p: Place, onSaved: () -> Unit = {}) = action(onSuccess = onSaved) { repository.savePlace(p) }
    fun remove(p: Place) = action("تم حذف المكان؛ حُفظت الطلبات والأماكن التابعة وفُصلت عن الأب المحذوف") { repository.removePlace(p) }
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
    fun importBackup(uri: Uri) {
        val protected = draftPhotoPaths.toSet()
        action("تم استيراد النسخة المحلية") {
        val app = getApplication<Application>()
        val bytes = requireNotNull(app.contentResolver.openInputStream(uri)).use { it.readBounded(25 * 1024 * 1024) }
        require(bytes.size <= 25 * 1024 * 1024) { "الملف أكبر من 25 ميغابايت" }
        repository.importBackup(app, bytes.toString(Charsets.UTF_8), protected)
        }
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
    /** An explicit editor finish releases only files created by this draft, never a directory scan. */
    fun finishPhotoEditing() {
        pendingPhoto = null
        val candidates = draftPhotoPaths.toSet()
        draftPhotoPaths.clear()
        if (candidates.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            // A saved/shared file is retained by the repository reference check.
            runCatching { repository.cleanupPhotoCandidates(getApplication(), candidates, emptySet()) }
        }
    }

    fun importPhoto(uri: Uri) {
        // The system picker may return after its editor was dismissed. Do not create an orphan.
        val callback = pendingPhoto ?: return
        action("تم إرفاق الصورة؛ احفظ الطلب للاحتفاظ بها") {
        val app = getApplication<Application>()
        val bytes = requireNotNull(app.contentResolver.openInputStream(uri)).use { it.readBounded(5 * 1024 * 1024) }
        require(bytes.size <= 5 * 1024 * 1024) { "اختر صورة أقل من 5 ميغابايت" }
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..16000 && options.outHeight in 1..16000) { "الصورة غير صالحة أو كبيرة الأبعاد" }
        val folder = File(app.filesDir, "photos").canonicalFile
        require(folder.parentFile == app.filesDir.canonicalFile && (folder.isDirectory || folder.mkdirs())) { "تعذّر فتح مجلد الصور الآمن" }
        val file = File(folder, "${UUID.randomUUID()}.img")
        var delivered = false
        try {
            file.outputStream().use { it.write(bytes); it.fd.sync() }
            withContext(Dispatchers.Main) {
                if (pendingPhoto === callback) {
                    draftPhotoPaths += file.absolutePath
                    delivered = true
                    callback(file.absolutePath)
                    pendingPhoto = null
                } else file.delete()
            }
        } catch (failure: Throwable) {
            if (!delivered) file.delete()
            throw failure
        }
        }
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
