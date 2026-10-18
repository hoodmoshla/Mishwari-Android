@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ye.mishwari.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ye.mishwari.data.Errand
import ye.mishwari.data.Place

@Composable
internal fun Editor(
    title: String,
    dismiss: () -> Unit,
    save: () -> Unit,
    enabled: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth().widthIn(max = 560.dp).fillMaxHeight(0.94f).imePadding().navigationBarsPadding().padding(horizontal = 12.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column {
                Text(title, Modifier.padding(horizontal = 20.dp, vertical = 16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content
                )
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text("إلغاء") }
                    Button(onClick = save, enabled = enabled) { Text("حفظ") }
                }
            }
        }
    }
}

@Composable
internal fun Field(label: String, value: String, change: (String) -> Unit, numeric: Boolean = false, single: Boolean = true) {
    OutlinedTextField(
        value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = single,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text)
    )
}

@Composable
internal fun Choice(label: String, value: String, options: List<String>, change: (String) -> Unit, labels: Map<String, String> = emptyMap()) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
        OutlinedTextField(labels[value] ?: value, {}, readOnly = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
        ExposedDropdownMenu(expanded, { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(labels[option] ?: option) }, onClick = { change(option); expanded = false }) }
        }
    }
}

/** A compact administrative drill-down; global search always includes custom and orphaned records. */
@Composable
internal fun PlaceSelector(label: String, selected: String?, places: List<Place>, change: (String?) -> Unit, allowNone: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    val selectedPlace = places.firstOrNull { it.id == selected }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text("$label: ${selectedPlace?.let(::placeLabel) ?: "دون وجهة"}")
    }
    if (open) PlacePickerDialog(label, selected, places, allowNone, onSelect = { change(it); open = false }, onClose = { open = false })
}

@Composable
private fun PlacePickerDialog(
    label: String, selected: String?, places: List<Place>, allowNone: Boolean,
    onSelect: (String?) -> Unit, onClose: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableIntStateOf(0) }
    var governorateId by rememberSaveable { mutableStateOf("") }
    var districtId by rememberSaveable { mutableStateOf("") }
    var showOtherRoots by rememberSaveable { mutableStateOf(false) }
    val byId = remember(places) { places.associateBy { it.id } }
    val selectedPlace = byId[selected]
    val initialGovernorate = remember(selected, places) {
        selectedPlace?.let { place ->
            generateSequence(place) { current -> current.parentId?.let(byId::get) }.firstOrNull { it.kind == "governorate" }?.id
                ?: places.firstOrNull { it.kind == "governorate" && it.name == place.governorate }?.id
                ?: ""
        }.orEmpty()
    }
    val initialDistrict = remember(selected, places) {
        selectedPlace?.let { place ->
            generateSequence(place) { current -> current.parentId?.let(byId::get) }.firstOrNull { it.kind == "district" }?.id
                ?: places.firstOrNull { it.kind == "district" && it.name == place.district && (place.governorate.isBlank() || it.governorate == place.governorate) }?.id
                ?: ""
        }.orEmpty()
    }
    LaunchedEffect(selected, places) {
        if (governorateId.isBlank()) governorateId = initialGovernorate
        if (districtId.isBlank()) districtId = initialDistrict
        if (districtId.isNotBlank()) level = 2 else if (governorateId.isNotBlank()) level = 1
    }
    val governorate = byId[governorateId]
    val district = byId[districtId]
    val allMatches = remember(query, places) {
        val term = query.trim()
        if (term.isBlank()) emptyList() else places.filter { place ->
            listOf(place.name, place.governorate, place.district, place.area, place.detail, placeLabel(place))
                .any { it.contains(term, ignoreCase = true) }
        }.sortedBy(::placeLabel)
    }
    val rootPlaces = remember(places) {
        places.filter { it.kind == "governorate" }.ifEmpty {
            places.filter { it.parentId == null || it.parentId !in byId && it.kind != "country" }
        }
    }
    val otherRoots = remember(places, rootPlaces) {
        places.filter {
            it.kind != "country" && it !in rootPlaces &&
                (it.parentId == null || it.parentId !in byId || byId[it.parentId]?.kind == "country")
        }.sortedBy(::placeLabel)
    }
    val shown = when {
        level <= 0 -> if (showOtherRoots) otherRoots else rootPlaces.sortedBy(::placeLabel)
        level == 1 -> {
            val direct = places.filter { it.parentId == governorateId && it.kind != "country" }
            (direct.filter { it.kind == "district" } + direct.filter { it.kind != "district" } + places.filter {
                it.kind == "district" && it.governorate == governorate?.name && it !in direct
            }).distinctBy { it.id }.sortedBy(::placeLabel)
        }
        else -> {
            val direct = places.filter { it.parentId == districtId && it.kind != "country" }
            val namedChildren = places.filter { it.governorate == governorate?.name && it.district == district?.name && it.kind != "governorate" && it.kind != "district" }
            (direct + namedChildren).distinctBy { it.id }.sortedBy(::placeLabel)
        }
    }
    val heading = when {
        query.isNotBlank() -> "نتائج البحث (${allMatches.size})"
        level == 0 && showOtherRoots -> "أماكن أخرى غير مرتبطة"
        level == 0 -> "اختر المحافظة"
        level == 1 -> governorate?.name ?: "اختر المديرية"
        else -> district?.name ?: "اختر السوق أو المكان"
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth().widthIn(max = 520.dp).fillMaxHeight(0.86f).imePadding().navigationBarsPadding().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (query.isNotBlank()) query = ""
                        else when {
                            level >= 2 -> { level = 1; districtId = "" }
                            level == 1 || showOtherRoots -> { level = 0; governorateId = ""; districtId = ""; showOtherRoots = false }
                            else -> onClose()
                        }
                    }) { Text(if (query.isNotBlank() || level > 0 || showOtherRoots) "رجوع" else "إغلاق") }
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                OutlinedTextField(
                    query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("ابحث في جميع الأماكن") }, placeholder = { Text("اسم محافظة أو مديرية أو سوق") }
                )
                val results = if (query.isNotBlank()) allMatches else shown
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                Text(heading, style = MaterialTheme.typography.titleMedium)
                if (allowNone) TextButton(onClick = { onSelect(null) }, modifier = Modifier.fillMaxWidth()) { Text("دون وجهة / إلغاء الاختيار") }
                if (query.isBlank() && level > 0) {
                    val current = if (level == 1) governorate else district
                    current?.let { item ->
                        OutlinedButton(onClick = { onSelect(item.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text("اختيار ${if (level == 1) "هذه المحافظة" else "هذه المديرية"}: ${item.name}")
                        }
                    }
                }
                if (query.isBlank() && level == 0 && !showOtherRoots && otherRoots.isNotEmpty()) {
                    TextButton(onClick = { showOtherRoots = true }, modifier = Modifier.fillMaxWidth()) { Text("أماكن أخرى أو غير مرتبطة (${otherRoots.size})") }
                }
                }
                if (results.isEmpty()) {
                    item { Text(if (query.isBlank()) "لا توجد عناصر في هذا المستوى. يمكنك البحث في الدليل كاملًا." else "لا توجد نتائج مطابقة.", style = MaterialTheme.typography.bodyMedium) }
                } else {
                        items(results, key = { it.id }) { item ->
                            val hasChildren = places.any { it.parentId == item.id && it.kind != "country" }
                            TextButton(
                                onClick = {
                                    if (query.isNotBlank() || level >= 2 || item.kind == "custom" || item.kind !in setOf("governorate", "district") && !hasChildren) onSelect(item.id)
                                    else when {
                                        item.kind == "governorate" || level == 0 -> { governorateId = item.id; districtId = ""; level = 1 }
                                        item.kind == "district" || level == 1 -> { districtId = item.id; level = 2 }
                                        else -> onSelect(item.id)
                                    }
                                }, modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(item.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (item.id == selected) FontWeight.Bold else FontWeight.Normal)
                                    val detail = when {
                                        query.isNotBlank() -> placeLabel(item)
                                        item.kind == "governorate" -> "محافظة · ${places.count { it.kind == "district" && (it.parentId == item.id || it.governorate == item.name) }} مديرية"
                                        item.kind == "district" -> "مديرية · ${places.count { it.parentId == item.id }} مكان تابع"
                                        else -> placeLabel(item).takeIf { it != item.name }.orEmpty()
                                    }
                                    if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

            }
        }
    }
}

@Composable
internal fun ErrandEditor(original: Errand, places: List<Place>, dismiss: () -> Unit, save: (Errand) -> Unit, addPlace: () -> Unit, selectPhoto: ((String) -> Unit) -> Unit) {
    var title by rememberSaveable(original.id) { mutableStateOf(original.title) }
    var description by rememberSaveable(original.id) { mutableStateOf(original.description) }
    var place by rememberSaveable(original.id) { mutableStateOf(original.placeId) }
    var category by rememberSaveable(original.id) { mutableStateOf(original.category) }
    var quantity by rememberSaveable(original.id) { mutableStateOf(original.quantity) }
    var price by rememberSaveable(original.id) { mutableStateOf(original.expectedPrice) }
    var notes by rememberSaveable(original.id) { mutableStateOf(original.notes) }
    var priority by rememberSaveable(original.id) { mutableIntStateOf(original.priority) }
    var due by rememberSaveable(original.id) { mutableStateOf(original.dueDate) }
    var status by rememberSaveable(original.id) { mutableStateOf(original.status) }
    var photo by rememberSaveable(original.id) { mutableStateOf(original.photoPath) }
    var detailsExpanded by rememberSaveable(original.id) { mutableStateOf(false) }
    var lastOriginalPlace by rememberSaveable(original.id) { mutableStateOf(original.placeId) }
    LaunchedEffect(original.placeId) {
        if (lastOriginalPlace != original.placeId) {
            place = original.placeId
            lastOriginalPlace = original.placeId
        }
    }
    val normalizedDue = westernDigits(due).trim()
    val validDate = due.isBlank() || runCatching { java.time.LocalDate.parse(normalizedDue); true }.getOrDefault(false)
    Editor(if (original.title.isBlank()) "إضافة طلب" else "تعديل الطلب", dismiss, {
        save(original.copy(title = title.trim(), description = description.trim(), placeId = place, category = category, quantity = quantity, expectedPrice = price, notes = notes, priority = priority, dueDate = normalizedDue, photoPath = photo, status = status))
    }, title.isNotBlank() && validDate) {
        Text("أدخل الاسم والوجهة؛ بقية التفاصيل اختيارية.", style = MaterialTheme.typography.bodySmall)
        Field("اسم الغرض أو المهمة *", title, { title = it })
        PlaceSelector("الوجهة", place, places, { place = it })
        TextButton(onClick = addPlace, modifier = Modifier.fillMaxWidth()) { Text("إضافة مكان غير موجود في الدليل") }
        TextButton(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.fillMaxWidth()) {
            Text(if (detailsExpanded) "إخفاء التفاصيل الاختيارية" else "إظهار التفاصيل الاختيارية")
        }
        if (detailsExpanded) {
            Field("الوصف (اختياري)", description, { description = it }, single = false)
            Choice("الفئة", category, categories, { category = it })
            Field("الكمية", quantity, { quantity = it })
            Field("السعر المتوقع", price, { price = it })
            Choice("الأولوية", priority.toString(), listOf("0", "1", "2"), { priority = it.toInt() }, mapOf("0" to "منخفضة", "1" to "عادية", "2" to "عالية"))
            Field("الاستحقاق: YYYY-MM-DD", due, { due = it })
            if (!validDate) Text("اكتب تاريخًا صحيحًا مثل 2026-12-31؛ تُقبل الأرقام العربية أيضًا", color = MaterialTheme.colorScheme.error)
            Choice("الحالة", status, statuses, { status = it })
            Field("ملاحظات", notes, { notes = it }, single = false)
            if (photo != null) {
                PhotoPreview(photo!!)
                TextButton(onClick = { photo = null }) { Text("إزالة إرفاق الصورة") }
            }
            OutlinedButton(onClick = { selectPhoto { photo = it } }, modifier = Modifier.fillMaxWidth()) { Text(if (photo == null) "إرفاق صورة من الجهاز" else "تغيير الصورة") }
            Text("لا تحتاج إذنًا للوصول إلى جميع صورك؛ تختار صورة واحدة فقط.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PhotoPreview(path: String) {
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, options)
            var sample = 1
            while (options.outWidth / sample > 800 || options.outHeight / sample > 800) sample *= 2
            android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = "صورة الغرض", modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)) }
}

@Composable
internal fun PlaceEditor(original: Place, places: List<Place>, dismiss: () -> Unit, save: (Place) -> Unit) {
    var name by rememberSaveable(original.id) { mutableStateOf(original.name) }
    var parent by rememberSaveable(original.id) { mutableStateOf(original.parentId) }
    var governorate by rememberSaveable(original.id) { mutableStateOf(original.governorate) }
    var district by rememberSaveable(original.id) { mutableStateOf(original.district) }
    var area by rememberSaveable(original.id) { mutableStateOf(original.area) }
    var detail by rememberSaveable(original.id) { mutableStateOf(original.detail) }
    var latitude by rememberSaveable(original.id) { mutableStateOf(original.latitude?.toString().orEmpty()) }
    var longitude by rememberSaveable(original.id) { mutableStateOf(original.longitude?.toString().orEmpty()) }
    var radius by rememberSaveable(original.id) { mutableStateOf(original.radiusMeters.toInt().toString()) }
    var monitored by rememberSaveable(original.id) { mutableStateOf(original.monitored) }
    var locationExpanded by rememberSaveable(original.id) { mutableStateOf(false) }
    var alertExpanded by rememberSaveable(original.id) { mutableStateOf(false) }
    var map by remember { mutableStateOf(false) }
    val ancestors = remember(places) { places.associateBy { it.id } }
    val lat = westernDigits(latitude).trim().toDoubleOrNull()
    val lon = westernDigits(longitude).trim().toDoubleOrNull()
    val r = westernDigits(radius).trim().toFloatOrNull()
    val noCoordinates = latitude.isBlank() && longitude.isBlank()
    val validCoordinates = lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    val validRadius = r != null && r.isFinite() && r in 100f..20000f
    Editor("${if (original.name.isBlank()) "إضافة" else "تعديل"} مكان", dismiss, {
        save(original.copy(name = name.trim(), parentId = parent, governorate = governorate.trim(), district = district.trim(), area = area.trim(), detail = detail.trim(), latitude = lat, longitude = lon, radiusMeters = r ?: 500f, monitored = monitored && validCoordinates))
    }, name.isNotBlank() && (noCoordinates || validCoordinates) && validRadius) {
        Field("اسم السوق / المحل / المكان *", name, { name = it })
        PlaceSelector("يتبع إداريًا", parent, places.filter { it.id !in subtree(original.id, places) }, { id ->
            parent = id
            val picked = ancestors[id]
            if (picked != null) {
                val chain = generateSequence(picked) { current -> current.parentId?.let(ancestors::get) }.toList()
                val gov = chain.firstOrNull { it.kind == "governorate" }
                val dist = chain.firstOrNull { it.kind == "district" }
                governorate = gov?.name ?: picked.governorate
                district = dist?.name ?: picked.district
                if (picked.kind == "governorate") { governorate = picked.name; district = "" }
                if (picked.kind == "district") district = picked.name
            }
        }, allowNone = true)
        TextButton(onClick = { locationExpanded = !locationExpanded }, modifier = Modifier.fillMaxWidth()) { Text(if (locationExpanded) "إخفاء تفاصيل الموقع" else "تفاصيل موقع إضافية") }
        if (locationExpanded) {
            Field("المحافظة (يمكن إدخالها يدويًا)", governorate, { governorate = it })
            Field("المديرية", district, { district = it })
            Field("المدينة / المنطقة", area, { area = it })
            Field("تفاصيل الموقع", detail, { detail = it }, single = false)
        }
        TextButton(onClick = { alertExpanded = !alertExpanded }, modifier = Modifier.fillMaxWidth()) { Text(if (alertExpanded) "إخفاء إعدادات الموقع والتنبيه" else "إعدادات الموقع والتنبيه الاختيارية") }
        if (alertExpanded) {
            OutlinedButton(onClick = { map = true }, modifier = Modifier.fillMaxWidth()) { Text("تحديد نقطة من خريطة OpenStreetMap") }
            Field("خط العرض — اختياري", latitude, { latitude = it }, numeric = true)
            Field("خط الطول — اختياري", longitude, { longitude = it }, numeric = true)
            if (!noCoordinates && !validCoordinates) Text("أدخل الإحداثيين بصورة صحيحة، أو اترك كليهما فارغًا.", color = MaterialTheme.colorScheme.error)
            Field("نصف قطر التنبيه بالمتر (100–20000)", radius, { radius = it }, numeric = true)
            Text("500 متر بداية عملية. النطاقات الصغيرة ودقة GPS الضعيفة قد تؤخر التنبيه.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(monitored && validCoordinates, { monitored = it }, enabled = validCoordinates, modifier = Modifier.semantics { contentDescription = "تفعيل مراقبة الوصول" })
                Text("تفعيل مراقبة الوصول", Modifier.padding(start = 8.dp))
            }
            Text("من دون إحداثيات يبقى المكان صالحًا لتنظيم الطلبات فقط.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (map) MapPicker(lat, lon, { a, b -> latitude = a.toString(); longitude = b.toString(); map = false }, { map = false })
}

private fun westernDigits(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            in '٠'..'٩' -> append('0' + (character - '٠'))
            in '۰'..'۹' -> append('0' + (character - '۰'))
            '٫' -> append('.')
            '٬' -> Unit
            '−' -> append('-')
            else -> append(character)
        }
    }
}
