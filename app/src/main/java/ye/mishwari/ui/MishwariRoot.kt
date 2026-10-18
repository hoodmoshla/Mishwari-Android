@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ye.mishwari.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import android.os.Bundle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ye.mishwari.MishwariViewModel
import ye.mishwari.data.*

internal val categories = listOf("مشتريات", "قطع غيار", "أدوية", "معاملات", "استلام", "صيانة", "أخرى")
internal val statuses = listOf("جديد", "مخطط له", "مكتمل", "مؤجل", "ملغى")
private fun active(e: Errand) = e.status != "مكتمل" && e.status != "ملغى"
internal fun subtree(root: String, places: List<Place>): Set<String> {
    val result = mutableSetOf(root)
    var changed = true
    while (changed) { changed = false; places.forEach { if (it.parentId in result && result.add(it.id)) changed = true } }
    return result
}
internal fun placeLabel(p: Place) = listOf(p.governorate, p.district, p.area, p.name).filter { it.isNotBlank() }.distinct().joinToString(" / ")

// Keep the editor identity across Activity recreation; its individual fields use rememberSaveable.
private val errandEditorSaver = Saver<Errand?, Bundle>(
    save = { e -> Bundle().apply { if (e != null) {
        putString("id", e.id); putString("title", e.title); putString("description", e.description)
        putString("place", e.placeId); putString("category", e.category); putString("quantity", e.quantity)
        putString("price", e.expectedPrice); putString("notes", e.notes); putInt("priority", e.priority)
        putString("due", e.dueDate); putString("photo", e.photoPath); putString("status", e.status); putLong("created", e.createdAt)
    } } },
    restore = { b -> if (!b.containsKey("id")) null else Errand(
        id = b.getString("id")!!, title = b.getString("title").orEmpty(), description = b.getString("description").orEmpty(),
        placeId = b.getString("place"), category = b.getString("category")!!, quantity = b.getString("quantity").orEmpty(),
        expectedPrice = b.getString("price").orEmpty(), notes = b.getString("notes").orEmpty(), priority = b.getInt("priority"),
        dueDate = b.getString("due").orEmpty(), photoPath = b.getString("photo"), status = b.getString("status")!!, createdAt = b.getLong("created")
    ) }
)

@Composable
fun MishwariRoot(
    model: MishwariViewModel, permissionRevision: Int, deepLinkPlace: String?, consumeDeepLink: () -> Unit,
    requestLocation: () -> Unit, requestNotifications: () -> Unit, openAppSettings: () -> Unit,
    openLocationSettings: () -> Unit, exportBackup: () -> Unit, importBackup: () -> Unit,
    importDirectory: () -> Unit, selectPhoto: ((String) -> Unit) -> Unit,
    incomingDraft: String? = null, consumeIncomingDraft: () -> Unit = {}, requestVoice: () -> Unit = {}
) {
    val dataReady by model.dataReady.collectAsStateWithLifecycle()
    val errands by model.errands.collectAsStateWithLifecycle()
    val places by model.places.collectAsStateWithLifecycle()
    val settingsList by model.settings.collectAsStateWithLifecycle()
    val operationError by model.error.collectAsStateWithLifecycle()
    val settings = settingsList.associate { it.key to it.value }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MishwariTheme {
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }
            var tab by rememberSaveable { mutableIntStateOf(0) }
            var query by rememberSaveable { mutableStateOf("") }
            var filter by rememberSaveable { mutableStateOf("غير المنجزة") }
            var placeFilter by rememberSaveable { mutableStateOf<String?>(null) }
            var editingErrand by rememberSaveable(stateSaver = errandEditorSaver) { mutableStateOf<Errand?>(null) }
            var editingPlace by remember { mutableStateOf<Place?>(null) }
            var deletingErrand by remember { mutableStateOf<Errand?>(null) }
            var deletingPlace by remember { mutableStateOf<Place?>(null) }
            var backgroundExplanation by remember { mutableStateOf(false) }
            var importing by remember { mutableStateOf(false) }
            var voiceExplanation by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(incomingDraft, editingErrand, editingPlace) {
                if (incomingDraft != null && editingErrand == null && editingPlace == null) {
                    val text = incomingDraft.trim()
                    if (text.isNotBlank()) {
                        editingErrand = Errand(title = text.lineSequence().first().take(200),
                            description = if (text.length > 200 || '\n' in text) text else "")
                        tab = 0
                    }
                    consumeIncomingDraft()
                }
            }
            LaunchedEffect(deepLinkPlace) { if (deepLinkPlace != null) { placeFilter = deepLinkPlace; tab = 0; filter = "غير المنجزة"; consumeDeepLink() } }
            Scaffold(
                topBar = { TopAppBar(title = { Text(listOf("مشواري", "الأماكن", "السفر", "الإعدادات")[tab], fontWeight = FontWeight.Bold) }, actions = { if (tab == 0) TextButton(onClick = { voiceExplanation = true }) { Text("إضافة بالصوت") } }) },
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar {
                        listOf("طلباتي", "الأماكن", "السفر", "الإعدادات").forEachIndexed { i, label ->
                            NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { NavigationIcon(i) }, label = { Text(label) })
                        }
                    }
                },
                floatingActionButton = {
                    if (tab == 0 || tab == 1) ExtendedFloatingActionButton(
                        onClick = { if (tab == 0) editingErrand = Errand(title = "", placeId = placeFilter) else editingPlace = Place(name = "") },
                    ) { Text(if (tab == 0) "+ إضافة طلب" else "+ إضافة مكان") }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    if (!dataReady) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("جارٍ تحميل قوائمك…")
                    } else when (tab) {
                        0 -> {
                            val shown = errands.filter { e ->
                                val p = places.find { it.id == e.placeId }
                                val withinPlace = placeFilter == null || e.placeId in subtree(placeFilter!!, places)
                                val matchStatus = when (filter) { "غير المنجزة" -> active(e); "الكل" -> true; else -> e.status == filter }
                                withinPlace && matchStatus && listOf(e.title, e.description, e.notes, p?.let(::placeLabel).orEmpty()).any { it.contains(query.trim(), ignoreCase = true) }
                            }.sortedWith(compareByDescending<Errand> { it.priority }.thenBy { it.dueDate.ifBlank { "9999" } }.thenByDescending { it.createdAt })
                            LazyColumn(Modifier.fillMaxSize().testTag("errandList"), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { OutlinedTextField(query, { query = it }, label = { Text("ابحث عن طلب أو وجهة") }, singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("مسح") } }) }
                                item {
                                    Text("${errands.count(::active)} غير منجزة · ${errands.count { it.priority == 2 && active(it) }} مهمة · ${errands.count { it.status == "مكتمل" }} مكتملة", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!settings["travel_place"].isNullOrBlank()) item { AssistChip(onClick = { tab = 2 }, label = { Text("مشوار نشط: ${places.find { it.id == settings["travel_place"] }?.name ?: "راجع الوجهة"}") }) }
                                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { (listOf("غير المنجزة", "الكل") + statuses).forEach { value -> FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value) }) } } }
                                if (placeFilter != null) item { AssistChip(onClick = { placeFilter = null }, label = { Text("الوجهة: ${places.find { it.id == placeFilter }?.name ?: "غير متاحة"} — عرض الجميع ×") }) }
                                if (shown.isEmpty()) item {
                                    EmptyState("لا توجد طلبات هنا", if (errands.isEmpty()) "ابدأ بطلب واحد؛ يمكنك اختيار وجهته الآن أو لاحقًا. قوائمك تعمل دون إنترنت." else "لا توجد نتائج مطابقة. جرّب كلمة أخرى أو اعرض كل الطلبات.", if (errands.isEmpty()) "إضافة أول طلب" else "مسح البحث والمرشّحات", { if (errands.isEmpty()) editingErrand = Errand(title = "", placeId = placeFilter) else { query = ""; filter = "الكل"; placeFilter = null } })
                                }
                                items(shown, key = { it.id }) { e -> ErrandCard(e, places.find { it.id == e.placeId }, { model.save(e.copy(status = if (e.status == "مكتمل") "جديد" else "مكتمل")) }, { editingErrand = e }, { deletingErrand = e }) }
                            }
                        }
                        1 -> {
                            var placeQuery by rememberSaveable { mutableStateOf("") }
                            var customOnly by rememberSaveable { mutableStateOf(true) }
                            var parent by rememberSaveable { mutableStateOf<String?>(null) }
                            val current = places.find { it.id == parent }
                            val shown = places.filter {
                                if (placeQuery.isNotBlank()) placeLabel(it).contains(placeQuery.trim(), true)
                                else if (customOnly) it.kind == "custom" || it.monitored || it.latitude != null
                                else if (parent == null) it.kind == "governorate" || (it.kind != "country" && it.parentId == null)
                                else it.parentId == parent
                            }.sortedBy(::placeLabel)
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { OutlinedTextField(placeQuery, { placeQuery = it }, label = { Text("ابحث في الأماكن") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(customOnly, { customOnly = true }, label = { Text("أماكني") })
                                    FilterChip(!customOnly, { customOnly = false }, label = { Text("دليل المحافظات") })
                                } }
                                if (!customOnly && placeQuery.isBlank()) item {
                                    Text("المحافظة ← المديرية ← المدينة أو السوق", style = MaterialTheme.typography.bodySmall)
                                    if (current != null) TextButton(onClick = { parent = current.parentId?.takeUnless { id -> places.find { it.id == id }?.kind == "country" } }) { Text("رجوع · ${current.name}") }
                                }
                                if (shown.isEmpty()) item { EmptyState(if (placeQuery.isNotBlank()) "لا توجد أماكن مطابقة" else "لا توجد أماكن هنا بعد", if (placeQuery.isNotBlank()) "جرّب اسم المحافظة أو المديرية أو أضف مكانًا مخصصًا." else "أضف مدينة أو سوقًا أو محلاً. الدليل المرفق يحتوي أسماء المحافظات والمديريات دون نقاط وصول.", "إضافة مكان هنا", { editingPlace = Place(name = "", parentId = current?.id, governorate = current?.governorate.orEmpty(), district = current?.district.orEmpty()) }) }
                                if (places.none { it.kind == "governorate" } && !customOnly) item { OutlinedButton(onClick = model::installBundledDirectory, modifier = Modifier.fillMaxWidth()) { Text("إضافة الدليل المرفق دون إنترنت") } }
                                items(shown, key = { it.id }) { place ->
                                    var expanded by rememberSaveable(place.id) { mutableStateOf(false) }
                                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        Text(place.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        val contextLabel = if (customOnly || placeQuery.isNotBlank()) placeLabel(place) else when (place.kind) { "governorate" -> "محافظة"; "district" -> "مديرية"; else -> "مدينة / سوق / مكان" }
                                        if (contextLabel != place.name) Text(contextLabel, style = MaterialTheme.typography.bodySmall)
                                        Text("${errands.count { it.placeId in subtree(place.id, places) && active(it) }} طلب غير منجز", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                                            if (!customOnly && placeQuery.isBlank()) TextButton(onClick = { parent = place.id }) { Text("تصفح") }
                                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "أقل" else "التفاصيل") }
                                        }
                                        if (expanded) {
                                            Text(if (place.latitude == null) "بلا نقطة وصول؛ أضف إحداثيات من التعديل لتفعيل التنبيه." else "نطاق التنبيه: ${place.radiusMeters.toInt()} متر", style = MaterialTheme.typography.bodySmall)
                                            Row(verticalAlignment = Alignment.CenterVertically) { Switch(place.monitored, { model.save(place.copy(monitored = it)) }, enabled = place.latitude != null, modifier = Modifier.semantics { contentDescription = "مراقبة الوصول: ${place.name}" }); Text("مراقبة الوصول", Modifier.weight(1f)) }
                                            if (place.source != "يدوي") Text("${place.source} · ${place.sourceUpdated}", style = MaterialTheme.typography.labelSmall)
                                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                                TextButton(onClick = { placeFilter = place.id; tab = 0 }) { Text("الطلبات") }
                                                TextButton(onClick = { editingPlace = place }) { Text("تعديل") }
                                                TextButton(onClick = { deletingPlace = place }) { Text("حذف") }
                                            }
                                        }
                                    } }
                                }
                            }
                        }
                        2 -> {
                            val trip = settings["travel_place"].orEmpty()
                            val destination = places.find { it.id == trip }
                            var chosen by rememberSaveable(trip) { mutableStateOf(trip) }
                            val relevant = if (chosen.isBlank()) emptyList() else errands.filter { it.placeId in subtree(chosen, places) && active(it) }
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                item { Text("جهّز مشوارك", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("اختر الوجهة، راجع طلباتك، ثم فعّل السفر. لا نسجّل مسارك.", style = MaterialTheme.typography.bodyMedium) }
                                if (destination != null) item { Card { Column(Modifier.padding(12.dp)) { Text("مشوار نشط: ${destination.name}", fontWeight = FontWeight.Bold); TextButton(onClick = { model.setting("travel_place", "") }) { Text("إنهاء وضع السفر") } } } }
                                item { Text("١ · اختر الوجهة", style = MaterialTheme.typography.titleMedium); PlaceSelector("وجهة المشوار", chosen.ifBlank { null }, places, { chosen = it.orEmpty() }, allowNone = true) }
                                item { Text("٢ · راجع الطلبات · ${relevant.size}", style = MaterialTheme.typography.titleMedium) }
                                if (relevant.isEmpty()) item { EmptyState("قائمة المشوار فارغة", "اختر وجهة لها طلبات غير منجزة، أو أضف طلبًا مرتبطًا بها.", "إضافة طلب للوجهة", { editingErrand = Errand(title = "", placeId = chosen.ifBlank { null }) }) }
                                items(relevant, key = { it.id }) { e -> ErrandCard(e, places.find { it.id == e.placeId }, { model.save(e.copy(status = "مكتمل")) }, { editingErrand = e }, { deletingErrand = e }) }
                                item {
                                    Text("٣ · تفعيل السفر والتنبيهات", style = MaterialTheme.typography.titleMedium)
                                    Text("يُفعّل السفر نقاط الطلبات المرتبطة بالوجهة فقط. أسماء المحافظات والمديريات وحدها ليست نقاط وصول.", style = MaterialTheme.typography.bodySmall)
                                    Button(onClick = { model.setting("travel_place", chosen) }, enabled = chosen.isNotBlank() && chosen != trip, modifier = Modifier.fillMaxWidth()) { Text("تجهيز المشوار وتفعيل السفر") }
                                }
                                item { CompactStatus(permissionRevision, destination?.name, { tab = 3 }) }
                            }
                        }
                        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("المظهر", style = MaterialTheme.typography.titleMedium)
                            Text("حسب مظهر الهاتف تلقائيًا · يتغير فور تبديل إعداد النظام.", style = MaterialTheme.typography.bodyMedium)
                            SettingsSection("الأذونات والخصوصية", initiallyExpanded = true) {
                            CompactStatus(permissionRevision, null, null)
                            Text("التنبيه يحتاج موقعًا دقيقًا، وإتاحة الموقع دائمًا في الخلفية، وتشغيل الموقع والإشعارات. لن نطلب إذن الخلفية قبل موافقتك؛ الطلبات تعمل بدونه.")
                            Button(onClick = requestLocation, modifier = Modifier.fillMaxWidth()) { Text("السماح بالموقع أثناء الاستخدام") }
                            OutlinedButton(onClick = { backgroundExplanation = true }, modifier = Modifier.fillMaxWidth()) { Text("إعداد الموقع في الخلفية") }
                            OutlinedButton(onClick = requestNotifications, modifier = Modifier.fillMaxWidth()) { Text("السماح بالإشعارات") }
                            TextButton(onClick = openLocationSettings) { Text("فتح إعدادات تشغيل الموقع") }
                            TextButton(onClick = openAppSettings) { Text("أذونات التطبيق وإعدادات البطارية") }
                            Text("حالة التنبيهات: ${geoStatusText(settings["geo_status"])}", style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = model::refreshAlerts, modifier = Modifier.fillMaxWidth()) { Text("إعادة تهيئة تنبيهات الوصول") }
                            Text("استخدمها بعد توقف غير معتاد أو تغيير أذونات؛ يبقى فاصل منع التكرار 30 دقيقة.", style = MaterialTheme.typography.bodySmall)
                            Text("قد تتأخر التنبيهات بسبب توفير البطارية أو ضعف تحديد الموقع، وتعتمد على خدمات Google Play ومزود الموقع الشبكي. قد لا تتولد أحداث وصول دون اتصال بيانات موثوق. لا يوجد GPS مستمر ولا سجل لتحركاتك.", style = MaterialTheme.typography.bodySmall)
                            }
                            SettingsSection("نسخة احتياطية محلية") {
                            Text("الملف يحتوي طلباتك وأماكنك وإحداثياتها وإعداداتك والصور المدعومة. ليس مشفرًا؛ احفظه في مكان موثوق. لا تُرفع البيانات تلقائيًا إلى أي خادم.")
                            OutlinedButton(onClick = exportBackup, modifier = Modifier.fillMaxWidth()) { Text("تصدير إلى ملف JSON") }
                            OutlinedButton(onClick = { importing = true }, modifier = Modifier.fillMaxWidth()) { Text("استيراد نسخة احتياطية") }
                            }
                            SettingsSection("دليل اليمن والخرائط") {
                            Text("دليل أسماء: 22 محافظة و335 مديرية من OCHA Yemen / HDX (COD-AB)، عن الجهاز المركزي للإحصاء. CC BY-IGO 3.0. صلاحية المصدر تبدأ 2019-11-22، ومراجعته المسجلة 2024-12-23؛ ليس تأكيدًا لتغييرات 2026. بلا حدود أو إحداثيات.")
                            OutlinedButton(onClick = model::installBundledDirectory, modifier = Modifier.fillMaxWidth()) { Text("إضافة الدليل المرفق دون إنترنت") }
                            TextButton(onClick = importDirectory) { Text("استيراد تحديث دليل من JSON") }
                            Text("الخرائط: MapLibre Native / © OpenStreetMap contributors. تحتاج اتصالًا وتحمل بلاطات العرض من tile.openstreetmap.org؛ قد يكشف مزود الخريطة نطاق العرض وعنوان الشبكة. لا نرسل طلباتك. لا يوجد دعم مضمون للخرائط غير المتصلة.", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("مشواري 0.1.0 · إصدار اختبار أولي. اختبر الوصول والخلفية على هاتفك قبل الاعتماد على التنبيهات لمهام مهمة.", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
            editingErrand?.let { value -> ErrandEditor(value, places, { editingErrand = null; model.pendingPhoto = null }, { model.save(it) { editingErrand = null; model.pendingPhoto = null } }, { editingPlace = Place(name = "") }, selectPhoto) }
            editingPlace?.let { value -> PlaceEditor(value, places, { editingPlace = null }, { place -> model.save(place) { editingPlace = null; editingErrand = editingErrand?.copy(placeId = place.id) } }) }
            deletingErrand?.let { e -> Confirm("حذف الطلب؟", "سيُحذف «${e.title}» من هذا الجهاز.", { deletingErrand = null }, { model.remove(e); deletingErrand = null }) }
            deletingPlace?.let { p -> Confirm("حذف المكان؟", "تبقى طلباته محفوظة دون وجهة. إذا كانت له أماكن تابعة، انقلها أو احذفها أولًا.", { deletingPlace = null }, { model.remove(p); deletingPlace = null }) }
            if (backgroundExplanation) AlertDialog(onDismissRequest = { backgroundExplanation = false }, title = { Text("التذكير حتى والتطبيق مغلق") }, text = { Text("لتلقي تنبيه الوصول إلى أماكنك المختارة، اسمح لمشواري بالوصول إلى الموقع دائمًا من إعدادات الأذونات، بعد السماح بالموقع الدقيق أثناء الاستخدام. تُرسل نقاط التنبيه المختارة إلى خدمة السياجات على الجهاز؛ لا نخزن سجل تنقلاتك ولا نستخدم خادمًا خاصًا. يمكنك رفض هذا الإذن والاستمرار باستخدام قوائمك.") }, confirmButton = { TextButton(onClick = { backgroundExplanation = false; openAppSettings() }) { Text("أوافق، افتح الإعدادات") } }, dismissButton = { TextButton(onClick = { backgroundExplanation = false }) { Text("ليس الآن") } })
            if (importing) Confirm("استيراد نسخة محلية", "تُدمج البيانات ويُحدّث السجل ذو المعرّف نفسه. لا تُحذف السجلات غير الموجودة في الملف. يُنصح بتصدير نسخة حالية أولًا. لن تُعاد تفعيل مراقبة الخلفية دون مراجعتك.", { importing = false }, { importing = false; importBackup() })
            if (voiceExplanation) AlertDialog(onDismissRequest = { voiceExplanation = false },
                title = { Text("إملاء طلب بالعربية") },
                text = { Text("سيُفتح تطبيق التعرف على الكلام في هاتفك. نطلب التعرّف دون اتصال، لكن الخدمة قد تستخدم الإنترنت وترسل الصوت إلى مزودها حسب إعداداتها. مشواري لا يسجل الصوت ولا يحتفظ به. راجع النص واختر الوجهة قبل الحفظ؛ الكتابة متاحة دائمًا.") },
                confirmButton = { TextButton(onClick = { voiceExplanation = false; requestVoice() }) { Text("متابعة إلى الإملاء") } },
                dismissButton = { TextButton(onClick = { voiceExplanation = false }) { Text("إلغاء") } })
            operationError?.let { message -> AlertDialog(onDismissRequest = { model.error.value = null }, title = { Text("لم تُكتمل العملية") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { model.error.value = null }) { Text("حسنًا") } }) }
        }
    }
}

@Composable private fun SettingsSection(title: String, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(if (expanded) "إخفاء" else "عرض")
        }
        if (expanded) Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable private fun EmptyState(title: String, body: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && actionLabel != null) TextButton(onClick = action) { Text(actionLabel) }
    } }
}
@Composable private fun Confirm(title: String, body: String, dismiss: () -> Unit, confirm: () -> Unit) { AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) }, confirmButton = { TextButton(onClick = confirm) { Text("متابعة") } }, dismissButton = { TextButton(onClick = dismiss) { Text("إلغاء") } }) }
@Composable private fun CompactStatus(revision: Int, travel: String?, onClick: (() -> Unit)?) {
    val context = LocalContext.current
    val state = remember(revision) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(LocationManager::class.java)
        val location = if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val channel = context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel("mishwari_geo_alerts")
        val notificationPermission = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val notifications = notificationPermission && NotificationManagerCompat.from(context).areNotificationsEnabled() && channel?.importance != android.app.NotificationManager.IMPORTANCE_NONE
        listOf(when { !fine -> "اسمح بالموقع الدقيق أثناء الاستخدام"; !background -> "فعّل الموقع طوال الوقت من الأذونات"; !location -> "شغّل موقع الهاتف"; else -> "الموقع جاهز" }, if (notifications) "الإشعارات متاحة" else "اسمح بالإشعارات من الإعدادات")
    }
    Surface(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(state.joinToString(" · "), style = MaterialTheme.typography.labelLarge); Text(if (travel != null) "وضع السفر: $travel" else "وضع السفر غير مفعّل", style = MaterialTheme.typography.bodySmall); if (onClick != null) Text("الخطوة التالية: مراجعة أذونات التنبيهات ←", style = MaterialTheme.typography.labelMedium) }
    }
}
@Composable private fun ErrandCard(e: Errand, place: Place?, complete: () -> Unit, edit: () -> Unit, delete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(e.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Checkbox(e.status == "مكتمل", { complete() }, modifier = Modifier.semantics { contentDescription = if (e.status == "مكتمل") "إعادة فتح الطلب: ${e.title}" else "إكمال الطلب: ${e.title}" }) }
        Text(place?.let(::placeLabel) ?: "دون وجهة", style = MaterialTheme.typography.bodyMedium)
        Text("${e.category} · ${e.status} · ${listOf("منخفضة", "عادية", "عالية").getOrElse(e.priority) { "عادية" }}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        var details by rememberSaveable(e.id) { mutableStateOf(false) }
        if (details) {
        if (e.description.isNotBlank()) Text(e.description)
        if (e.quantity.isNotBlank() || e.expectedPrice.isNotBlank()) Text("الكمية: ${e.quantity.ifBlank { "—" }} · المتوقع: ${e.expectedPrice.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
        if (e.dueDate.isNotBlank()) Text("الاستحقاق: ${e.dueDate}", style = MaterialTheme.typography.bodySmall)
        if (e.notes.isNotBlank()) Text(e.notes, style = MaterialTheme.typography.bodySmall)
        if (e.photoPath != null) Text("صورة مرفقة — افتح التعديل لعرضها", style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            if (e.description.isNotBlank() || e.notes.isNotBlank() || e.quantity.isNotBlank() || e.expectedPrice.isNotBlank() || e.dueDate.isNotBlank() || e.photoPath != null) TextButton(onClick = { details = !details }) { Text(if (details) "أقل" else "التفاصيل") }
            TextButton(onClick = edit) { Text("تعديل") }; TextButton(onClick = delete) { Text("حذف") } }
    } }
}
private fun geoStatusText(value: String?): String = when {
    value == null -> "يجري التحقق"
    value.startsWith("active:") -> "تم تسجيل ${value.substringAfter(':')} نطاقات وصول"
    value.startsWith("permission_required:fine_location") -> "يلزم السماح بالموقع الدقيق"
    value.startsWith("permission_required:background_location") -> "يلزم السماح بالموقع طوال الوقت"
    value.startsWith("permission_required:") -> "صلاحية الموقع غير متاحة؛ راجع الأذونات"
    value == "location_disabled" -> "موقع الهاتف مغلق"
    value.startsWith("play_services_unavailable:") -> "خدمات Google Play غير متاحة أو تحتاج تحديثًا"
    value.startsWith("event_error:") -> "خدمة الموقع غير جاهزة؛ تجري محاولة الاستعادة"
    value.startsWith("error:") -> "تعذّر تسجيل النطاقات؛ راجع الموقع والأذونات وخدمات Google Play"
    value.startsWith("event_") || value == "receiver_error" -> "تعذّر معالجة حدث موقع؛ أعد التحقق من الإعدادات"
    else -> "يجري تحديث حالة المراقبة"
}
