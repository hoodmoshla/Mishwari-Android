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
import java.io.File
import java.util.UUID

class MishwariViewModel(app: Application) : AndroidViewModel(app) {
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
