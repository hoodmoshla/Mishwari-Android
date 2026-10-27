package ye.mishwari.data

import java.time.LocalDate

/** Pure validation shared by persistence operations and JVM tests. */
object DataValidation {
    const val MIN_RADIUS_METERS = 100f
    const val MAX_RADIUS_METERS = 20_000f
    const val MAX_ID_LENGTH = 128
    private const val MAX_TITLE_LENGTH = 200
    private const val MAX_ACTUAL_PRICE_LENGTH = 100
    private val categories = setOf("مشتريات", "قطع غيار", "أدوية", "معاملات", "استلام", "صيانة", "أخرى")
    private val statuses = setOf("جديد", "مخطط له", "مكتمل", "مؤجل", "ملغى")

    fun validateErrand(errand: Errand) {
        validateId(errand.id, "معرّف الطلب")
        require(errand.title.isNotBlank() && errand.title.length <= MAX_TITLE_LENGTH) {
            "اسم الطلب مطلوب وبحد أقصى 200 حرف"
        }
        require(errand.category in categories) { "فئة الطلب غير معروفة" }
        require(errand.status in statuses) { "حالة الطلب غير معروفة" }
        require(errand.priority in 0..2) { "الأولوية يجب أن تكون بين 0 و2" }
        require(errand.actualPrice.length <= MAX_ACTUAL_PRICE_LENGTH) { "السعر الفعلي يجب ألا يتجاوز 100 حرف" }
        if (errand.dueDate.isNotBlank()) {
            require(errand.dueDate.length == 10 && runCatching { LocalDate.parse(errand.dueDate) }.isSuccess) {
                "تاريخ الاستحقاق يجب أن يكون بصيغة YYYY-MM-DD صحيحة"
            }
        }
        errand.placeId?.let { validateId(it, "معرّف المكان") }
    }

    fun validatePlace(place: Place) {
        validateId(place.id, "معرّف المكان")
        place.parentId?.let { validateId(it, "معرّف المكان الأب") }
        require(place.name.isNotBlank() && place.name.length <= MAX_TITLE_LENGTH) {
            "اسم المكان مطلوب وبحد أقصى 200 حرف"
        }
        val hasLatitude = place.latitude != null
        val hasLongitude = place.longitude != null
        require(hasLatitude == hasLongitude) { "يجب إدخال خط العرض وخط الطول معًا" }
        if (hasLatitude) {
            val latitude = requireNotNull(place.latitude)
            val longitude = requireNotNull(place.longitude)
            require(latitude.isFinite() && latitude in -90.0..90.0) { "خط العرض يجب أن يكون بين -90 و90" }
            require(longitude.isFinite() && longitude in -180.0..180.0) { "خط الطول يجب أن يكون بين -180 و180" }
        }
        require(place.radiusMeters.isFinite() && place.radiusMeters in MIN_RADIUS_METERS..MAX_RADIUS_METERS) {
            "نطاق التنبيه يجب أن يكون بين 100 و20000 متر"
        }
    }

    fun validateSettingKey(key: String) {
        require(key.isNotBlank() && key.length <= MAX_ID_LENGTH) { "مفتاح الإعداد مطلوب وبحد أقصى 128 حرفًا" }
    }

    private fun validateId(id: String, label: String) {
        require(id.isNotBlank() && id.length <= MAX_ID_LENGTH) { "$label مطلوب وبحد أقصى 128 حرفًا" }
    }

    /** Rejects missing parents and cycles, including cycles that cross imported and stored rows. */
    fun validateHierarchy(places: Collection<Place>, requireParents: Boolean = true) {
        val byId = places.associateBy { it.id }
        require(byId.size == places.size) { "توجد معرّفات أماكن مكررة" }
        places.forEach { place ->
            validatePlace(place)
            if (requireParents && place.parentId != null) {
                require(place.parentId in byId) { "المكان الأب ${place.parentId} غير موجود للمكان ${place.id}" }
            }
        }
        val complete = HashSet<String>()
        for (place in places) {
            val visiting = HashSet<String>()
            var cursor: Place? = place
            while (cursor != null) {
                require(cursor.id !in visiting) { "تسلسل الأماكن يحتوي على دورة غير مسموحة" }
                if (!complete.add(cursor.id)) break
                visiting += cursor.id
                cursor = cursor.parentId?.let(byId::get)
            }
        }
    }
}
