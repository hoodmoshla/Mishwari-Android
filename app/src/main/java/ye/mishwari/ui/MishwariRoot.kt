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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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

private val categories = listOf("مشتريات", "قطع غيار", "أدوية", "معاملات", "استلام", "صيانة", "أخرى")
private val statuses = listOf("جديد", "مخطط له", "مكتمل", "مؤجل", "ملغى")
private fun active(e: Errand) = e.status != "مكتمل" && e.status != "ملغى"
private fun subtree(root: String, places: List<Place>): Set<String> {
    val result = mutableSetOf(root)
    var changed = true
    while (changed) { changed = false; places.forEach { if (it.parentId in result && result.add(it.id)) changed = true } }
    return result
}
private fun placeLabel(p: Place) = listOf(p.governorate, p.district, p.area, p.name).filter { it.isNotBlank() }.distinct().joinToString(" / ")

@Composable
fun MishwariRoot(
    model: MishwariViewModel, permissionRevision: Int, deepLinkPlace: String?, consumeDeepLink: () -> Unit,
    requestLocation: () -> Unit, requestNotifications: () -> Unit, openAppSettings: () -> Unit,
    openLocationSettings: () -> Unit, exportBackup: () -> Unit, importBackup: () -> Unit,
    importDirectory: () -> Unit, selectPhoto: ((String) -> Unit) -> Unit
) {
    val errands by model.errands.collectAsStateWithLifecycle()
    val places by model.places.collectAsStateWithLifecycle()
    val settingsList by model.settings.collectAsStateWithLifecycle()
    val operationError by model.error.collectAsStateWithLifecycle()
    val settings = settingsList.associate { it.key to it.value }
    val dark = when (settings["theme"]) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val light = lightColorScheme(primary = Color(0xFF006B58), secondary = Color(0xFF53655E), background = Color(0xFFF5F8F6), surface = Color(0xFFFAFDF9))
    val night = darkColorScheme(primary = Color(0xFF79D9BF), background = Color(0xFF101613), surface = Color(0xFF18201B))
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = if (dark) night else light) {
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }
            var tab by rememberSaveable { mutableIntStateOf(0) }
            var query by rememberSaveable { mutableStateOf("") }
            var filter by rememberSaveable { mutableStateOf("غير المنجزة") }
            var placeFilter by rememberSaveable { mutableStateOf<String?>(null) }
            var editingErrand by remember { mutableStateOf<Errand?>(null) }
            var editingPlace by remember { mutableStateOf<Place?>(null) }
            var deletingErrand by remember { mutableStateOf<Errand?>(null) }
            var deletingPlace by remember { mutableStateOf<Place?>(null) }
            var backgroundExplanation by remember { mutableStateOf(false) }
            var importing by remember { mutableStateOf(false) }
            LaunchedEffect(deepLinkPlace) { if (deepLinkPlace != null) { placeFilter = deepLinkPlace; tab = 0; filter = "غير المنجزة"; consumeDeepLink() } }
            Scaffold(
                topBar = { TopAppBar(title = { Column { Text("مشواري", fontWeight = FontWeight.Bold); Text("طلباتك معك… في المكان المناسب", style = MaterialTheme.typography.labelMedium) } }) },
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar {
                        listOf("طلباتي", "الأماكن", "السفر", "الإعدادات").forEachIndexed { i, label ->
                            NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(listOf("≡", "⌖", "↗", "⚙")[i], style = MaterialTheme.typography.headlineSmall) }, label = { Text(label) })
                        }
                    }
                },
                floatingActionButton = {
                    if (tab == 0 || tab == 1) ExtendedFloatingActionButton(
                        onClick = { if (tab == 0) editingErrand = Errand(title = "", placeId = placeFilter) else editingPlace = Place(name = "") },
                        text = { Text(if (tab == 0) "+ إضافة طلب" else "+ إضافة مكان") }, icon = {})
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (tab) {
                        0 -> {
                            val shown = errands.filter { e ->
                                val p = places.find { it.id == e.placeId }
                                val withinPlace = placeFilter == null || e.placeId in subtree(placeFilter!!, places)
                                val matchStatus = when (filter) { "غير المنجزة" -> active(e); "الكل" -> true; else -> e.status == filter }
                                withinPlace && matchStatus && listOf(e.title, e.description, e.notes, p?.let(::placeLabel).orEmpty()).any { it.contains(query.trim(), ignoreCase = true) }
                            }.sortedWith(compareByDescending<Errand> { it.priority }.thenBy { it.dueDate.ifBlank { "9999" } }.thenByDescending { it.createdAt })
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                item {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Metric("غير منجزة", errands.count(::active).toString(), Modifier.weight(1f))
                                        Metric("وجهة", places.count { it.kind == "custom" || it.latitude != null }.toString(), Modifier.weight(1f))
                                        Metric("مكتملة", errands.count { it.status == "مكتمل" }.toString(), Modifier.weight(1f))
                                    }
                                }
                                item { CompactStatus(permissionRevision, settings["travel_place"]?.let { id -> places.find { it.id == id }?.name }, { tab = 3 }) }
                                item { OutlinedTextField(query, { query = it }, label = { Text("ابحث عن طلب أو وجهة") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { (listOf("غير المنجزة", "الكل") + statuses).forEach { value -> FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value) }) } } }
                                if (placeFilter != null) item { AssistChip(onClick = { placeFilter = null }, label = { Text("الوجهة: ${places.find { it.id == placeFilter }?.name ?: "غير متاحة"} — عرض الجميع ×") }) }
                                if (shown.isEmpty()) item { EmptyState("لا توجد طلبات هنا", if (query.isBlank()) "أضف ما تحتاجه، واربطه بمكان لتتذكره عند الوصول. إضافة الطلبات وحفظها لا تحتاج إنترنت." else "جرّب كلمة أخرى أو غيّر مرشّح الحالة.") }
                                items(shown, key = { it.id }) { e -> ErrandCard(e, places.find { it.id == e.placeId }, { model.save(e.copy(status = if (e.status == "مكتمل") "جديد" else "مكتمل")) }, { editingErrand = e }, { deletingErrand = e }) }
                            }
                        }
                        1 -> {
                            var placeQuery by rememberSaveable { mutableStateOf("") }
                            var customOnly by rememberSaveable { mutableStateOf(true) }
                            val shown = places.filter { (!customOnly || it.kind == "custom" || it.monitored || it.latitude != null) && placeLabel(it).contains(placeQuery.trim(), true) }.sortedBy(::placeLabel)
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                item { Text("دليل الوجهات", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                                item { Text("سجّل أي محل أو سوق يدويًا، مع إحداثيات أو بدونها. أسماء الدليل الإداري ليست نقاط وصول جغرافية.") }
                                item { OutlinedTextField(placeQuery, { placeQuery = it }, label = { Text("ابحث في الأماكن") }, modifier = Modifier.fillMaxWidth()) }
                                item { Row(verticalAlignment = Alignment.CenterVertically) { Switch(customOnly, { customOnly = it }); Spacer(Modifier.width(8.dp)); Text("وجهاتي فقط") } }
                                if (shown.isEmpty()) item { EmptyState("ابدأ بأول وجهة", "أضف مكانًا بنفسك، أو استورد دليل المحافظات والمديريات من الإعدادات.") }
                                items(shown, key = { it.id }) { p ->
                                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(placeLabel(p), style = MaterialTheme.typography.bodySmall)
                                        Text(if (p.latitude == null) "دون إحداثيات — لا يمكن تفعيل تنبيه وصول" else "نطاق التنبيه: ${p.radiusMeters.toInt()} متر", style = MaterialTheme.typography.bodySmall)
                                        Text("${errands.count { it.placeId == p.id && active(it) }} طلب غير منجز", color = MaterialTheme.colorScheme.primary)
                                        if (p.source != "يدوي") Text("${p.source}\n${p.sourceUpdated}", style = MaterialTheme.typography.labelSmall)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Switch(checked = p.monitored, enabled = p.latitude != null, onCheckedChange = { model.save(p.copy(monitored = it)) })
                                            Text("مراقبة الوصول", Modifier.weight(1f))
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(onClick = { placeFilter = p.id; tab = 0 }) { Text("الطلبات") }
                                            TextButton(onClick = { editingPlace = p }) { Text("تعديل") }
                                            TextButton(onClick = { deletingPlace = p }) { Text("حذف") }
                                        }
                                    } }
                                }
                            }
                        }
                        2 -> {
                            val trip = settings["travel_place"].orEmpty()
                            val destination = places.find { it.id == trip }
                            var chosen by remember(trip) { mutableStateOf(trip) }
                            val relevant = if (chosen.isBlank()) emptyList() else errands.filter { it.placeId in subtree(chosen, places) && active(it) }
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("وضع السفر", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                Text("اختر محافظة أو وجهة قبل الانطلاق. نفعّل نقاط الطلبات المرتبطة بها فقط؛ لا نخمن وجهتك التالية ولا نسجل مسارك.")
                                PlaceSelector("وجهة المشوار", chosen.ifBlank { null }, places, { chosen = it.orEmpty() }, allowNone = true)
                                if (destination != null) Card { Column(Modifier.padding(16.dp)) { Text("مشوار نشط: ${destination.name}", fontWeight = FontWeight.Bold); TextButton(onClick = { model.setting("travel_place", "") }) { Text("إنهاء وضع السفر") } } }
                                Button(onClick = { model.setting("travel_place", chosen) }, enabled = chosen.isNotBlank() && chosen != trip, modifier = Modifier.fillMaxWidth()) { Text("تجهيز المشوار وتفعيل السفر") }
                                CompactStatus(permissionRevision, destination?.name, { tab = 3 })
                                Text("قائمة المشوار · ${relevant.size} طلب", style = MaterialTheme.typography.titleLarge)
                                if (relevant.isEmpty()) EmptyState("قائمة المشوار فارغة", "اختر الوجهة وأضف طلبات مرتبطة بها أو بالأماكن التابعة لها.")
                                relevant.forEach { e -> ErrandCard(e, places.find { it.id == e.placeId }, { model.save(e.copy(status = "مكتمل")) }, { editingErrand = e }, { deletingErrand = e }) }
                                Text("المحافظات والمديريات بلا إحداثيات لا تُفعّل كسياجات. أضف نقطة دقيقة لكل سوق أو محل تحتاج تنبيهه.", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("الأذونات والخصوصية", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
                            HorizontalDivider()
                            Text("المظهر", style = MaterialTheme.typography.titleLarge)
                            Choice("اختر المظهر", settings["theme"] ?: "system", listOf("system", "light", "dark"), { model.setting("theme", it) }, mapOf("system" to "حسب النظام", "light" to "فاتح", "dark" to "داكن"))
                            HorizontalDivider()
                            Text("نسخة احتياطية محلية", style = MaterialTheme.typography.titleLarge)
                            Text("الملف يحتوي طلباتك وأماكنك وإحداثياتها وإعداداتك والصور المدعومة. ليس مشفرًا؛ احفظه في مكان موثوق. لا تُرفع البيانات تلقائيًا إلى أي خادم.")
                            OutlinedButton(onClick = exportBackup, modifier = Modifier.fillMaxWidth()) { Text("تصدير إلى ملف JSON") }
                            OutlinedButton(onClick = { importing = true }, modifier = Modifier.fillMaxWidth()) { Text("استيراد نسخة احتياطية") }
                            HorizontalDivider()
                            Text("دليل اليمن", style = MaterialTheme.typography.titleLarge)
                            Text("دليل أسماء: 22 محافظة و335 مديرية من OCHA Yemen / HDX (COD-AB)، عن الجهاز المركزي للإحصاء. CC BY-IGO 3.0. صلاحية المصدر تبدأ 2019-11-22، ومراجعته المسجلة 2024-12-23؛ ليس تأكيدًا لتغييرات 2026. بلا حدود أو إحداثيات.")
                            OutlinedButton(onClick = model::installBundledDirectory, modifier = Modifier.fillMaxWidth()) { Text("إضافة الدليل المرفق دون إنترنت") }
                            TextButton(onClick = importDirectory) { Text("استيراد تحديث دليل من JSON") }
                            Text("الخرائط: MapLibre Native / © OpenStreetMap contributors. تحتاج اتصالًا وتحمل بلاطات العرض من tile.openstreetmap.org؛ قد يكشف مزود الخريطة نطاق العرض وعنوان الشبكة. لا نرسل طلباتك. لا يوجد دعم مضمون للخرائط غير المتصلة.", style = MaterialTheme.typography.bodySmall)
                            Text("مشواري 0.1.0 · إصدار اختبار أولي. اختبر الوصول والخلفية على هاتفك قبل الاعتماد على التنبيهات لمهام مهمة.", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
            editingErrand?.let { value -> ErrandEditor(value, places, { editingErrand = null; model.pendingPhoto = null }, { model.save(it) { editingErrand = null; model.pendingPhoto = null } }, { editingPlace = Place(name = "") }, selectPhoto) }
            editingPlace?.let { value -> PlaceEditor(value, places, { editingPlace = null }, { model.save(it) { editingPlace = null } }) }
            deletingErrand?.let { e -> Confirm("حذف الطلب؟", "سيُحذف «${e.title}» من هذا الجهاز.", { deletingErrand = null }, { model.remove(e); deletingErrand = null }) }
            deletingPlace?.let { p -> Confirm("حذف المكان؟", "تبقى طلباته محفوظة دون وجهة. إذا كانت له أماكن تابعة، انقلها أو احذفها أولًا.", { deletingPlace = null }, { model.remove(p); deletingPlace = null }) }
            if (backgroundExplanation) AlertDialog(onDismissRequest = { backgroundExplanation = false }, title = { Text("التذكير حتى والتطبيق مغلق") }, text = { Text("لتلقي تنبيه الوصول إلى أماكنك المختارة، اسمح لمشواري بالوصول إلى الموقع دائمًا من إعدادات الأذونات، بعد السماح بالموقع الدقيق أثناء الاستخدام. تُرسل نقاط التنبيه المختارة إلى خدمة السياجات على الجهاز؛ لا نخزن سجل تنقلاتك ولا نستخدم خادمًا خاصًا. يمكنك رفض هذا الإذن والاستمرار باستخدام قوائمك.") }, confirmButton = { TextButton(onClick = { backgroundExplanation = false; openAppSettings() }) { Text("أوافق، افتح الإعدادات") } }, dismissButton = { TextButton(onClick = { backgroundExplanation = false }) { Text("ليس الآن") } })
            if (importing) Confirm("استيراد نسخة محلية", "تُدمج البيانات ويُحدّث السجل ذو المعرّف نفسه. لا تُحذف السجلات غير الموجودة في الملف. يُنصح بتصدير نسخة حالية أولًا. لن تُعاد تفعيل مراقبة الخلفية دون مراجعتك.", { importing = false }, { importing = false; importBackup() })
            operationError?.let { message -> AlertDialog(onDismissRequest = { model.error.value = null }, title = { Text("لم تُكتمل العملية") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { model.error.value = null }) { Text("حسنًا") } }) }
        }
    }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) { Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.labelMedium) } }
}
@Composable private fun EmptyState(title: String, body: String) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); Text(body) } } }
@Composable private fun Confirm(title: String, body: String, dismiss: () -> Unit, confirm: () -> Unit) { AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) }, confirmButton = { TextButton(onClick = confirm) { Text("متابعة") } }, dismissButton = { TextButton(onClick = dismiss) { Text("إلغاء") } }) }
@Composable private fun CompactStatus(revision: Int, travel: String?, onClick: (() -> Unit)?) {
    val context = LocalContext.current
    val state = remember(revision) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(LocationManager::class.java)
        val location = if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val channel = context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel("mishwari_geo_alerts")
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled() && channel?.importance != android.app.NotificationManager.IMPORTANCE_NONE
        listOf(if (fine && background && location) "الموقع جاهز" else "الموقع يحتاج إعدادًا", if (notifications) "الإشعارات متاحة" else "الإشعارات مغلقة")
    }
    Surface(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(state.joinToString(" · "), style = MaterialTheme.typography.labelLarge); Text(if (travel != null) "وضع السفر: $travel" else "وضع السفر غير مفعّل", style = MaterialTheme.typography.bodySmall); if (onClick != null) Text("مراجعة الإعدادات ←", style = MaterialTheme.typography.labelSmall) }
    }
}
@Composable private fun ErrandCard(e: Errand, place: Place?, complete: () -> Unit, edit: () -> Unit, delete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(e.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Checkbox(e.status == "مكتمل", { complete() }) }
        Text(place?.let(::placeLabel) ?: "دون وجهة", style = MaterialTheme.typography.bodyMedium)
        Text("${e.category} · ${e.status} · ${listOf("منخفضة", "عادية", "عالية").getOrElse(e.priority) { "عادية" }}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (e.description.isNotBlank()) Text(e.description)
        if (e.quantity.isNotBlank() || e.expectedPrice.isNotBlank()) Text("الكمية: ${e.quantity.ifBlank { "—" }} · المتوقع: ${e.expectedPrice.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
        if (e.dueDate.isNotBlank()) Text("الاستحقاق: ${e.dueDate}", style = MaterialTheme.typography.bodySmall)
        if (e.notes.isNotBlank()) Text(e.notes, style = MaterialTheme.typography.bodySmall)
        if (e.photoPath != null) Text("صورة مرفقة — افتح التعديل لعرضها", style = MaterialTheme.typography.labelSmall)
        Row { TextButton(onClick = edit) { Text("تعديل") }; TextButton(onClick = delete) { Text("حذف") } }
    } }
}
@Composable private fun Editor(title: String, dismiss: () -> Unit, save: () -> Unit, enabled: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal = 12.dp), shape = RoundedCornerShape(24.dp)) {
            Column {
                Text(title, Modifier.padding(20.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) { TextButton(onClick = dismiss) { Text("إلغاء") }; Button(onClick = save, enabled = enabled) { Text("حفظ") } }
            }
        }
    }
}
@Composable private fun Field(label: String, value: String, change: (String) -> Unit, numeric: Boolean = false, single: Boolean = true) { OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = single, keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text)) }
@Composable private fun Choice(label: String, value: String, options: List<String>, change: (String) -> Unit, labels: Map<String, String> = emptyMap()) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
        OutlinedTextField(labels[value] ?: value, {}, readOnly = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
        ExposedDropdownMenu(expanded, { expanded = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(labels[option] ?: option) }, onClick = { change(option); expanded = false }) } }
    }
}
@Composable private fun PlaceSelector(label: String, selected: String?, places: List<Place>, change: (String?) -> Unit, allowNone: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: ${places.find { it.id == selected }?.let(::placeLabel) ?: "دون وجهة"}") }
    if (open) {
        var query by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { open = false }) { Surface(shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp).heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleLarge)
            Field("ابحث في الدليل", query, { query = it })
            if (allowNone) TextButton(onClick = { change(null); open = false }) { Text("دون وجهة / إلغاء الاختيار") }
            LazyColumn(Modifier.weight(1f, fill = false)) { items(places.filter { placeLabel(it).contains(query.trim(), true) }.sortedBy(::placeLabel), key = { it.id }) { p -> TextButton(onClick = { change(p.id); open = false }, modifier = Modifier.fillMaxWidth()) { Text(placeLabel(p), Modifier.fillMaxWidth()) } } }
            TextButton(onClick = { open = false }) { Text("إغلاق") }
        } } }
    }
}
@Composable private fun ErrandEditor(original: Errand, places: List<Place>, dismiss: () -> Unit, save: (Errand) -> Unit, addPlace: () -> Unit, selectPhoto: ((String) -> Unit) -> Unit) {
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
    val normalizedDue = westernDigits(due).trim()
    val validDate = due.isBlank() || runCatching { java.time.LocalDate.parse(normalizedDue); true }.getOrDefault(false)
    Editor(if (original.title.isBlank()) "إضافة طلب" else "تعديل الطلب", dismiss, { save(original.copy(title = title.trim(), description = description.trim(), placeId = place, category = category, quantity = quantity, expectedPrice = price, notes = notes, priority = priority, dueDate = normalizedDue, photoPath = photo, status = status)) }, title.isNotBlank() && validDate) {
        Field("اسم الغرض أو المهمة *", title, { title = it })
        PlaceSelector("المحافظة / المديرية / المكان", place, places, { place = it })
        TextButton(onClick = addPlace) { Text("إضافة مكان غير موجود في الدليل") }
        Field("الوصف (اختياري)", description, { description = it }, single = false)
        Choice("الفئة", category, categories, { category = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Box(Modifier.weight(1f)) { Field("الكمية", quantity, { quantity = it }) }; Box(Modifier.weight(1f)) { Field("السعر المتوقع", price, { price = it }) } }
        Choice("الأولوية", priority.toString(), listOf("0", "1", "2"), { priority = it.toInt() }, mapOf("0" to "منخفضة", "1" to "عادية", "2" to "عالية"))
        Field("الاستحقاق: YYYY-MM-DD", due, { due = it })
        if (!validDate) Text("اكتب تاريخًا صحيحًا مثل 2026-12-31؛ تُقبل الأرقام العربية أيضًا", color = MaterialTheme.colorScheme.error)
        Choice("الحالة", status, statuses, { status = it })
        Field("ملاحظات", notes, { notes = it }, single = false)
        if (photo != null) {
            PhotoPreview(photo!!)
            TextButton(onClick = { photo = null }) { Text("إزالة إرفاق الصورة") }
        }
        OutlinedButton(onClick = { selectPhoto { photo = it } }) { Text(if (photo == null) "إرفاق صورة من الجهاز" else "تغيير الصورة") }
        Text("لا تحتاج إذنًا للوصول إلى جميع صورك؛ تختار صورة واحدة فقط.", style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun PhotoPreview(path: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, options)
            var sample = 1
            while (options.outWidth / sample > 800 || options.outHeight / sample > 800) sample *= 2
            android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    bitmap?.let { androidx.compose.foundation.Image(it.asImageBitmap(), contentDescription = "صورة الغرض", modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)) }
}

@Composable private fun PlaceEditor(original: Place, places: List<Place>, dismiss: () -> Unit, save: (Place) -> Unit) {
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
    var map by remember { mutableStateOf(false) }
    val lat = westernDigits(latitude).trim().toDoubleOrNull(); val lon = westernDigits(longitude).trim().toDoubleOrNull(); val r = westernDigits(radius).trim().toFloatOrNull()
    val noCoordinates = latitude.isBlank() && longitude.isBlank()
    val validCoordinates = lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    val validRadius = r != null && r.isFinite() && r in 100f..20000f
    Editor("${if (original.name.isBlank()) "إضافة" else "تعديل"} مكان", dismiss, { save(original.copy(name = name.trim(), parentId = parent, governorate = governorate.trim(), district = district.trim(), area = area.trim(), detail = detail.trim(), latitude = lat, longitude = lon, radiusMeters = r ?: 500f, monitored = monitored && validCoordinates)) }, name.isNotBlank() && (noCoordinates || validCoordinates) && validRadius) {
        Field("اسم السوق / المحل / المكان *", name, { name = it })
        PlaceSelector("يتبع إداريًا", parent, places.filter { it.id !in subtree(original.id, places) }, { id ->
            parent = id
            places.find { it.id == id }?.let { p ->
                if (p.kind == "governorate") { governorate = p.name; district = "" }
                else { governorate = p.governorate; district = if (p.kind == "district") p.name else p.district }
            }
        })
        Field("المحافظة (يمكن إدخالها يدويًا)", governorate, { governorate = it })
        Field("المديرية", district, { district = it })
        Field("المدينة / المنطقة", area, { area = it })
        Field("تفاصيل الموقع", detail, { detail = it }, single = false)
        OutlinedButton(onClick = { map = true }, modifier = Modifier.fillMaxWidth()) { Text("تحديد نقطة من خريطة OpenStreetMap") }
        Field("خط العرض — اختياري", latitude, { latitude = it }, numeric = true)
        Field("خط الطول — اختياري", longitude, { longitude = it }, numeric = true)
        if (!noCoordinates && !validCoordinates) Text("أدخل الإحداثيين بصورة صحيحة، أو اترك كليهما فارغًا.", color = MaterialTheme.colorScheme.error)
        Field("نصف قطر التنبيه بالمتر (100–20000)", radius, { radius = it }, numeric = true)
        Text("500 متر بداية عملية. النطاقات الصغيرة ودقة GPS الضعيفة قد تؤخر التنبيه.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(monitored && validCoordinates, { monitored = it }, enabled = validCoordinates); Text("تفعيل مراقبة الوصول", Modifier.padding(start = 8.dp)) }
        Text("من دون إحداثيات يبقى المكان صالحًا لتنظيم الطلبات فقط.", style = MaterialTheme.typography.bodySmall)
    }
    if (map) MapPicker(lat, lon, { a, b -> latitude = a.toString(); longitude = b.toString(); map = false }, { map = false })
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
