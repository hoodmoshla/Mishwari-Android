package ye.mishwari.data

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Persistence and import/export facade. Backups contain only explicitly stored records and
 * coordinates, never a hidden or inferred location history. Inline photos are optional, capped,
 * and read only from filesDir/photos. External imports do not enable place monitoring.
 */
class Repository(private val db: MishwariDatabase) {
    private val dao = db.dao()

    val errands: Flow<List<Errand>> = dao.observeErrands()
    val places: Flow<List<Place>> = dao.observePlaces()
    val settings: Flow<List<AppSetting>> = dao.observeSettings()

    suspend fun saveErrand(e: Errand) = db.withTransaction {
        DataValidation.validateErrand(e)
        if (e.placeId != null) require(dao.allPlaces().any { it.id == e.placeId }) { "المكان المحدد للطلب غير موجود" }
        dao.upsertErrand(e)
    }

    suspend fun removeErrand(e: Errand) = dao.deleteErrand(e.id)

    suspend fun savePlace(p: Place) = db.withTransaction {
        DataValidation.validatePlace(p)
        val combined = (dao.allPlaces().filterNot { it.id == p.id } + p)
        DataValidation.validateHierarchy(combined, requireParents = true)
        dao.upsertPlace(p)
    }

    suspend fun removePlace(p: Place) = dao.deletePlace(p.id)

    suspend fun setSetting(key: String, value: String) {
        DataValidation.validateSettingKey(key)
        dao.putSetting(AppSetting(key, value))
    }

    /** Exports schema-1 UTF-8 JSON. Only files inside filesDir/photos and <= 2 MiB are embedded. */
    suspend fun exportBackup(context: Context): String = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val snapshot = dao.snapshot()
        val places = snapshot.places
        val errands = snapshot.errands
        val settings = snapshot.settings
        val root = JSONObject().put("schema", BACKUP_SCHEMA)
        root.put("places", JSONArray().apply { places.forEach { put(it.toJson()) } })
        root.put("errands", JSONArray().apply {
            errands.forEach { errand ->
                val json = errand.toJson()
                safeLocalPhotoBytes(context, errand.photoPath)?.let { bytes ->
                    json.put("photoBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
                put(json)
            }
        })
        root.put("settings", JSONArray().apply {
            settings.forEach { put(JSONObject().put("key", it.key).put("value", it.value)) }
        })
        root.toString()
    }

    /**
     * Validates the complete input before writing. Records are additive (colliding ids/keys update);
     * the database change itself is one Room transaction. Returns the imported errand count.
     */
    suspend fun importBackup(context: Context, json: String): Int = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val parsed = parseBackup(json)
        val importedPlaces = parsed.places.map { it.copy(monitored = false) }
        val currentPlaces = dao.allPlaces()
        val mergedPlaces = mergePlaces(currentPlaces, importedPlaces)
        DataValidation.validateHierarchy(mergedPlaces, requireParents = true)
        val allPlaceIds = mergedPlaces.mapTo(HashSet()) { it.id }
        parsed.errands.forEach { errand ->
            DataValidation.validateErrand(errand)
            require(errand.placeId == null || errand.placeId in allPlaceIds) {
                "الطلب ${errand.id} يشير إلى مكان غير موجود"
            }
        }

        val createdFiles = mutableListOf<File>()
        try {
            val importedErrands = parsed.errands.map { errand ->
                val bytes = parsed.photoBytes[errand.id] ?: return@map errand
                val dir = File(context.filesDir, "photos")
                require(dir.exists() || dir.mkdirs()) { "تعذر إنشاء مجلد الصور الآمن" }
                val filesRoot = context.filesDir.canonicalFile
                val safeDir = dir.canonicalFile
                require(safeDir.path.startsWith(filesRoot.path + File.separator)) { "مسار مجلد الصور غير آمن" }
                val target = File(safeDir, "${UUID.randomUUID()}.bin").canonicalFile
                require(target.parentFile == safeDir) { "وجهة الصورة غير آمنة" }
                target.writeBytes(bytes)
                createdFiles += target
                errand.copy(photoPath = target.absolutePath)
            }
            val sanitizedErrands = importedErrands.map { imported ->
                if (parsed.photoBytes.containsKey(imported.id)) imported else imported.copy(photoPath = null)
            }
            // Never import worker fingerprints, delivery state, or an active trip from a file.
            // Otherwise an apparently passive restore could silently register location monitoring.
            val safeSettings = parsed.settings.filter { it.key == "theme" && it.value in setOf("system", "light", "dark") } +
                AppSetting("travel_place", "")
            dao.applySnapshot(orderParentsFirst(importedPlaces, mergedPlaces), sanitizedErrands, safeSettings)
            parsed.errands.size
        } catch (failure: Throwable) {
            createdFiles.forEach { it.delete() }
            throw failure
        }
    }

    /** Imports the standardized administrative hierarchy additively; returns imported place count. */
    suspend fun importDirectory(json: String): Int {
        val root = try { JSONObject(json) } catch (e: Exception) {
            throw IllegalArgumentException("بيانات الدليل الإداري غير صالحة", e)
        }
        val source = root.requiredString("source")
        val updated = root.requiredString("updated")
        val rows = root.getJSONArray("places")
        val imported = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            Place(
                id = row.optionalString("id") ?: UUID.randomUUID().toString(),
                name = row.requiredString("name"),
                parentId = row.optionalNullableString("parentId"),
                kind = row.optionalString("kind") ?: "custom",
                governorate = row.optionalString("governorate") ?: "",
                district = row.optionalString("district") ?: "",
                area = row.optionalString("area") ?: "",
                detail = row.optionalString("detail") ?: "",
                latitude = row.optionalDouble("latitude"),
                longitude = row.optionalDouble("longitude"),
                radiusMeters = row.optionalFloat("radiusMeters") ?: 500f,
                // Directory imports must never reactivate monitoring flags from external datasets.
                monitored = false,
                source = row.optionalString("source") ?: source,
                sourceUpdated = row.optionalString("sourceUpdated") ?: updated
            )
        }
        require(imported.map { it.id }.toSet().size == imported.size) { "توجد معرّفات أماكن مكررة في الدليل" }
        val merged = mergePlaces(dao.allPlaces(), imported)
        DataValidation.validateHierarchy(merged, requireParents = true)
        dao.applySnapshot(orderParentsFirst(imported, merged), emptyList(), emptyList())
        return imported.size
    }

    private data class ParsedBackup(
        val places: List<Place>,
        val errands: List<Errand>,
        val settings: List<AppSetting>,
        val photoBytes: Map<String, ByteArray>
    )

    private fun parseBackup(json: String): ParsedBackup {
        try {
            val root = JSONObject(json)
            require(root.getInt("schema") == BACKUP_SCHEMA) { "إصدار النسخة الاحتياطية غير مدعوم" }
            val places = root.getJSONArray("places").toObjects { it.toPlace() }
            require(places.map { it.id }.toSet().size == places.size) { "توجد معرّفات أماكن مكررة في النسخة الاحتياطية" }
            val photoBytes = mutableMapOf<String, ByteArray>()
            val errands = root.getJSONArray("errands").toObjects { row ->
                val errand = row.toErrand()
                row.optionalString("photoBase64")?.let { encoded ->
                    require(encoded.length <= MAX_BASE64_PHOTO_CHARS) { "حجم الصورة المضمّنة يتجاوز الحد المسموح" }
                    require(encoded.isNotEmpty() && BASE64_PATTERN.matches(encoded)) { "بيانات الصورة المضمّنة غير صالحة" }
                    val bytes = try { Base64.decode(encoded, Base64.DEFAULT) } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("Invalid embedded photo", e)
                    }
                    require(bytes.size <= MAX_INLINE_PHOTO_BYTES) { "حجم الصورة المضمّنة يتجاوز الحد المسموح" }
                    photoBytes[errand.id] = bytes
                }
                errand
            }
            require(errands.map { it.id }.toSet().size == errands.size) { "توجد معرّفات طلبات مكررة في النسخة الاحتياطية" }
            val settings = root.getJSONArray("settings").toObjects { row ->
                AppSetting(row.requiredString("key"), row.requiredString("value"))
            }
            require(settings.map { it.key }.toSet().size == settings.size) { "توجد مفاتيح إعدادات مكررة في النسخة الاحتياطية" }
            settings.forEach { DataValidation.validateSettingKey(it.key) }
            DataValidation.validateHierarchy(places, requireParents = true)
            errands.forEach(DataValidation::validateErrand)
            return ParsedBackup(places, errands, settings, photoBytes)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("ملف النسخة الاحتياطية غير صالح", e)
        }
    }

    private fun <T> JSONArray.toObjects(block: (JSONObject) -> T): List<T> =
        (0 until length()).map { block(getJSONObject(it)) }

    private fun JSONObject.toPlace() = Place(
        id = requiredString("id"),
        name = requiredString("name"),
        parentId = optionalNullableString("parentId"),
        kind = optionalString("kind") ?: "custom",
        governorate = optionalString("governorate") ?: "",
        district = optionalString("district") ?: "",
        area = optionalString("area") ?: "",
        detail = optionalString("detail") ?: "",
        latitude = optionalDouble("latitude"),
        longitude = optionalDouble("longitude"),
        radiusMeters = optionalFloat("radiusMeters") ?: 500f,
        monitored = optionalBoolean("monitored") ?: false,
        source = optionalString("source") ?: "يدوي",
        sourceUpdated = optionalString("sourceUpdated") ?: ""
    )

    private fun JSONObject.toErrand() = Errand(
        id = requiredString("id"),
        title = requiredString("title"),
        description = optionalString("description") ?: "",
        placeId = optionalNullableString("placeId"),
        category = optionalString("category") ?: "مشتريات",
        quantity = optionalString("quantity") ?: "",
        expectedPrice = optionalString("expectedPrice") ?: "",
        notes = optionalString("notes") ?: "",
        priority = optionalInt("priority") ?: 1,
        dueDate = optionalString("dueDate") ?: "",
        photoPath = optionalNullableString("photoPath"),
        status = optionalString("status") ?: "جديد",
        createdAt = optionalLong("createdAt") ?: System.currentTimeMillis()
    )

    private fun Place.toJson() = JSONObject()
        .put("id", id).put("name", name).putNullable("parentId", parentId)
        .put("kind", kind).put("governorate", governorate).put("district", district)
        .put("area", area).put("detail", detail).putNullable("latitude", latitude)
        .putNullable("longitude", longitude).put("radiusMeters", radiusMeters)
        .put("monitored", monitored).put("source", source).put("sourceUpdated", sourceUpdated)

    private fun Errand.toJson() = JSONObject()
        .put("id", id).put("title", title).put("description", description)
        .putNullable("placeId", placeId).put("category", category).put("quantity", quantity)
        .put("expectedPrice", expectedPrice).put("notes", notes).put("priority", priority)
        .put("dueDate", dueDate).putNullable("photoPath", photoPath).put("status", status)
        .put("createdAt", createdAt)

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private fun JSONObject.requiredString(name: String): String {
        val value = opt(name)
        require(value is String) { "الحقل '$name' يجب أن يكون نصًا" }
        return value
    }

    private fun JSONObject.optionalString(name: String): String? {
        val value = opt(name)
        if (value == null || value === JSONObject.NULL) return null
        require(value is String) { "الحقل '$name' يجب أن يكون نصًا" }
        return value
    }

    private fun JSONObject.optionalNullableString(name: String): String? = optionalString(name)

    private fun JSONObject.optionalDouble(name: String): Double? {
        val value = opt(name)
        if (value == null || value === JSONObject.NULL) return null
        require(value is Number) { "الحقل '$name' يجب أن يكون رقمًا" }
        return (value as Number).toDouble()
    }

    private fun JSONObject.optionalFloat(name: String): Float? = optionalDouble(name)?.toFloat()

    private fun JSONObject.optionalInt(name: String): Int? {
        val value = opt(name)
        if (value == null || value === JSONObject.NULL) return null
        require(value is Number) { "الحقل '$name' يجب أن يكون عددًا صحيحًا" }
        val number = (value as Number).toDouble()
        require(number.isFinite() && number % 1.0 == 0.0) { "الحقل '$name' يجب أن يكون عددًا صحيحًا" }
        return number.toInt()
    }

    private fun JSONObject.optionalLong(name: String): Long? {
        val value = opt(name)
        if (value == null || value === JSONObject.NULL) return null
        require(value is Number) { "الحقل '$name' يجب أن يكون عددًا صحيحًا" }
        val number = (value as Number).toDouble()
        require(number.isFinite() && number % 1.0 == 0.0) { "الحقل '$name' يجب أن يكون عددًا صحيحًا" }
        return number.toLong()
    }

    private fun JSONObject.optionalBoolean(name: String): Boolean? {
        val value = opt(name)
        if (value == null || value === JSONObject.NULL) return null
        require(value is Boolean) { "الحقل '$name' يجب أن يكون قيمة منطقية" }
        return value as Boolean
    }

    private fun safeLocalPhotoBytes(context: Context, path: String?): ByteArray? {
        if (path.isNullOrBlank()) return null
        return try {
            val file = File(path).canonicalFile
            val filesRoot = context.filesDir.canonicalFile
            val photoDirectory = File(filesRoot, "photos").canonicalFile
            if (!photoDirectory.path.startsWith(filesRoot.path + File.separator)) return null
            val root = photoDirectory.path + File.separator
            if (!file.isFile || !file.canonicalPath.startsWith(root) || file.length() > MAX_INLINE_PHOTO_BYTES) {
                return null
            }
            val output = ByteArrayOutputStream()
            file.inputStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_INLINE_PHOTO_BYTES) return null
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        } catch (_: IOException) { null }
    }

    private fun mergePlaces(existing: List<Place>, incoming: List<Place>): List<Place> {
        val merged = LinkedHashMap<String, Place>()
        existing.forEach { merged[it.id] = it }
        incoming.forEach { merged[it.id] = it }
        return merged.values.toList()
    }

    private fun orderParentsFirst(incoming: List<Place>, merged: List<Place>): List<Place> {
        val all = merged.associateBy { it.id }
        fun depth(place: Place): Int {
            var level = 0
            var cursor = place.parentId
            val seen = HashSet<String>()
            while (cursor != null && seen.add(cursor)) {
                level++
                cursor = all[cursor]?.parentId
            }
            return level
        }
        return incoming.sortedBy(::depth)
    }

    companion object {
        private const val BACKUP_SCHEMA = 1
        private const val MAX_INLINE_PHOTO_BYTES = 2 * 1024 * 1024
        private const val MAX_BASE64_PHOTO_CHARS = ((MAX_INLINE_PHOTO_BYTES + 2) / 3) * 4
        private val BASE64_PATTERN = Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")
    }
}

/** Backwards-compatible spelling; the supported public facade is [Repository]. */
typealias MishwariRepository = Repository
