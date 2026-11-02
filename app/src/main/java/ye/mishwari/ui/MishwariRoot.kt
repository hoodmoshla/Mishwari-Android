@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ye.mishwari.ui

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
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ye.mishwari.MishwariViewModel
import ye.mishwari.GeoEnvironmentSignature
import ye.mishwari.data.*
import ye.mishwari.location.CurrentLocationRequest
import ye.mishwari.location.LocationCaptureFailure
import ye.mishwari.location.LocationCaptureResult

internal val categories = listOf("مشتريات", "قطع غيار", "أدوية", "معاملات", "استلام", "صيانة", "أخرى")
internal val statuses = listOf("جديد", "مخطط له", "مكتمل", "مؤجل", "ملغى")
internal fun active(e: Errand) = e.status != "مكتمل" && e.status != "ملغى"
internal val errandDisplayOrder = compareByDescending<Errand> { active(it) }
    .thenByDescending { it.priority }.thenBy { it.dueDate.ifBlank { "9999" } }.thenByDescending { it.createdAt }
internal fun subtree(root: String, places: List<Place>): Set<String> {
    val result = mutableSetOf(root)
    var changed = true
    while (changed) { changed = false; places.forEach { if (it.parentId in result && result.add(it.id)) changed = true } }
    return result
}
internal fun placeLabel(p: Place) = listOf(p.governorate, p.district, p.area, p.name).filter { it.isNotBlank() }.distinct().joinToString(" / ")
internal fun matchesArabicSearch(text: String, query: String): Boolean {
    val needle = ye.mishwari.ai.ArabicPlaceMatcher.normalize(query).lowercase(java.util.Locale.ROOT)
    if (needle.isBlank()) return true
    return ye.mishwari.ai.ArabicPlaceMatcher.normalize(text).lowercase(java.util.Locale.ROOT).contains(needle)
}

// Keep the editor identity across Activity recreation; its individual fields use rememberSaveable.
private val errandEditorSaver = Saver<Errand?, Bundle>(
    save = { e -> Bundle().apply { if (e != null) {
        putString("id", e.id); putString("title", e.title); putString("description", e.description)
        putString("place", e.placeId); putString("category", e.category); putString("quantity", e.quantity)
        putString("price", e.expectedPrice); putString("actualPrice", e.actualPrice); putString("notes", e.notes); putInt("priority", e.priority)
        putString("due", e.dueDate); putString("photo", e.photoPath); putString("status", e.status); putLong("created", e.createdAt)
    } } },
    restore = { b -> if (!b.containsKey("id")) null else Errand(
        id = b.getString("id")!!, title = b.getString("title").orEmpty(), description = b.getString("description").orEmpty(),
        placeId = b.getString("place"), category = b.getString("category")!!, quantity = b.getString("quantity").orEmpty(),
        expectedPrice = b.getString("price").orEmpty(), actualPrice = b.getString("actualPrice").orEmpty(), notes = b.getString("notes").orEmpty(), priority = b.getInt("priority"),
        dueDate = b.getString("due").orEmpty(), photoPath = b.getString("photo"), status = b.getString("status")!!, createdAt = b.getLong("created")
    ) }
)

@Composable
fun MishwariRoot(
    model: MishwariViewModel, permissionRevision: Int, deepLinkPlace: String?, consumeDeepLink: () -> Unit,
    requestLocation: () -> Unit, requestNotifications: () -> Unit, openAppSettings: () -> Unit,
    openLocationSettings: () -> Unit, exportBackup: () -> Unit, importBackup: () -> Unit,
    importDirectory: () -> Unit, selectPhoto: ((String) -> Unit) -> Unit,
    incomingDraft: String? = null, consumeIncomingDraft: () -> Unit = {}, requestVoice: () -> Unit = {},
    requestBackgroundLocation: () -> Unit = openAppSettings
) {
    val dataReady by model.dataReady.collectAsStateWithLifecycle()
    val errands by model.errands.collectAsStateWithLifecycle()
    val places by model.places.collectAsStateWithLifecycle()
    val settingsList by model.settings.collectAsStateWithLifecycle()
    val operationError by model.error.collectAsStateWithLifecycle()
    val bulkSaveBusy by model.bulkSaveBusy.collectAsStateWithLifecycle()
    val settings = settingsList.associate { it.key to it.value }
    val context = LocalContext.current
    val permissionState = remember(permissionRevision) { GeoEnvironmentSignature.capture(context) }
    val alertPlaces = ye.mishwari.geo.GeoPolicy.select(
        ye.mishwari.geo.GeoPolicy.fromData(places, errands, settings["travel_place"]?.takeIf { it.isNotBlank() }),
        settings["travel_place"]?.takeIf { it.isNotBlank() }
    )
    val alertsNeeded = alertPlaces.isNotEmpty()
    var tripCurrentLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var tripLocationMessage by rememberSaveable { mutableStateOf("موقعك لا يُقرأ تلقائيًا؛ اطلب تحديثه عند الحاجة.") }
    var tripLocationRequested by rememberSaveable { mutableStateOf(false) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MishwariTheme(themeMode = settings["theme_mode"]) {
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
            var permissionExplanation by rememberSaveable { mutableStateOf<String?>(null) }
            var importing by remember { mutableStateOf(false) }
            var voiceExplanation by rememberSaveable { mutableStateOf(false) }
            var bulkInputOpen by remember { mutableStateOf(false) }
            var showAssistantConfiguration by rememberSaveable { mutableStateOf(false) }
            var reopeningPlace by remember { mutableStateOf<Place?>(null) }
            LaunchedEffect(tab) {
                if (tab != 2) {
                    tripCurrentLocation = null
                    tripLocationRequested = false
                    tripLocationMessage = "موقعك لا يُقرأ تلقائيًا؛ اطلب تحديثه عند الحاجة."
                }
            }
            DisposableEffect(tab, permissionRevision, tripLocationRequested) {
                var locationRequest: CurrentLocationRequest? = null
                if (tab == 2 && tripLocationRequested && (permissionState.fineLocationGranted || permissionState.coarseLocationGranted)) {
                    tripLocationMessage = "جارٍ طلب قراءة موقع حديثة لمرة واحدة…"
                    locationRequest = CurrentLocationRequest(context, { result ->
                        when (result) {
                            is LocationCaptureResult.Success -> {
                                tripCurrentLocation = result.latitude to result.longitude
                                tripLocationMessage = "تم تحديث الموقع الآن؛ هذه قراءة أمامية مؤقتة وليست تتبعًا مستمرًا."
                            }
                            is LocationCaptureResult.Failure -> {
                                tripCurrentLocation = null
                                tripLocationMessage = when (result.reason) {
                                    LocationCaptureFailure.PERMISSION_REQUIRED -> "إذن الموقع غير متاح؛ لا يمكن عرض موقع حالي."
                                    LocationCaptureFailure.PROVIDER_DISABLED -> "خدمات الموقع متوقفة؛ فعّل الموقع ثم حاول مجددًا."
                                    LocationCaptureFailure.TIMEOUT -> "لم تصل قراءة حديثة ضمن المهلة؛ لم نعرض موقعًا قديمًا."
                                    LocationCaptureFailure.UNAVAILABLE -> "تعذر الحصول على قراءة موقع من هذا الجهاز."
                                }
                            }
                        }
                        tripLocationRequested = false
                    }).start()
                }
                onDispose { locationRequest?.cancel() }
            }
            LaunchedEffect(incomingDraft, editingErrand, editingPlace) {
                if (incomingDraft != null && editingErrand == null && editingPlace == null) {
                    val text = incomingDraft.trim()
                    if (text.isNotBlank()) {
                        editingErrand = Errand(title = text.lineSequence().first().take(200),
                            description = if (text.length > 200 || '\n' in text) text else "")
                        tab = 1
                    }
                    consumeIncomingDraft()
                }
            }
            LaunchedEffect(deepLinkPlace) { if (deepLinkPlace != null) { placeFilter = deepLinkPlace; tab = 1; filter = "غير المنجزة"; consumeDeepLink() } }
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (tab == 0) MishwariBrandMark()
                            Text(
                                when (tab) { 0 -> "مشواري"; 1 -> "طلباتي"; 2 -> "المشوار"; 3 -> "المساعد الذكي"; 5 -> "الأماكن"; else -> "الإعدادات" },
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            }
                        },
                        navigationIcon = {
                            if (tab == 5) TextButton(onClick = { tab = 4 }) { Text("رجوع", color = Color.White) }
                            else if (tab == 0) IconButton(onClick = { tab = 4 }) { Text("☰", color = Color.White, style = MaterialTheme.typography.titleLarge) }
                        },
                        actions = {
                            if (tab == 0 || tab == 1) TextButton(onClick = { voiceExplanation = true }) {
                                Text("إضافة بالصوت", color = Color.White, style = MaterialTheme.typography.labelLarge)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color(0xFF075B40),
                            titleContentColor = Color.White,
                            navigationIconContentColor = Color.White,
                            actionIconContentColor = Color.White,
                        ),
                    )
                },
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                        val selectedTab = if (tab == 5) 4 else tab
                        listOf("الرئيسية", "طلباتي", "المشوار", "المساعد الذكي", "الإعدادات").forEachIndexed { i, label ->
                            NavigationBarItem(
                                selected = selectedTab == i,
                                onClick = { if (i == 4) showAssistantConfiguration = false; tab = i },
                                icon = { NavigationIcon(i) },
                                label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                modifier = Modifier.testTag("navigation_$i"),
                            )
                        }
                    }
                },
                floatingActionButton = {
                    if (tab == 1 || tab == 5) ExtendedFloatingActionButton(
                        onClick = { if (tab == 5) editingPlace = Place(name = "") else editingErrand = Errand(title = "", placeId = placeFilter) },
                        containerColor = MishwariBrightGreen,
                        contentColor = Color.White,
                    ) { Text(if (tab == 5) "+ إضافة مكان" else "+ إضافة طلب", fontWeight = FontWeight.Bold) }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    if (!dataReady) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("جارٍ تحميل قوائمك…")
                    } else when (tab) {
                        0 -> HomeDashboard(
                            errands = errands,
                            places = places,
                            activeTripId = settings["travel_place"]?.takeIf { it.isNotBlank() },
                            tripReadiness = when {
                                alertPlaces.isEmpty() -> "لا توجد نقطة تنبيه صالحة"
                                !permissionState.locationEnabled -> "موقع الهاتف مغلق"
                                !permissionState.fineLocationGranted -> "إذن الموقع الدقيق مطلوب"
                                !permissionState.notificationsReady() -> "الإشعارات غير مفعلة"
                                !permissionState.backgroundLocationGranted -> "تنبيه الخلفية غير جاهز"
                                else -> geoStatusText(settings["geo_status"])
                            },
                            onOpenRequests = { tab = 1; filter = "غير المنجزة"; placeFilter = null },
                            onOpenTrip = { tab = 2 },
                            onOpenPlaces = { tab = 5 },
                            onOpenAssistant = { tab = 3 },
                            onAddRequest = { placeFilter = null; editingErrand = Errand(title = "") },
                            onAddPlace = { editingPlace = Place(name = "") },
                            onBulkInput = { bulkInputOpen = true },
                            onOpenPlace = { id -> tab = 1; placeFilter = id.takeIf { it.isNotBlank() }; filter = "غير المنجزة" },
                        )
                        1 -> {
                            var advancedFilters by rememberSaveable { mutableStateOf(false) }
                            val shown = errands.filter { e ->
                                val p = places.find { it.id == e.placeId }
                                val withinPlace = placeFilter == null || e.placeId in subtree(placeFilter!!, places)
                                val matchStatus = when (filter) { "غير المنجزة" -> active(e); "الكل" -> true; else -> e.status == filter }
                                withinPlace && matchStatus && listOf(e.title, e.description, e.notes, e.category, e.quantity, e.expectedPrice, e.actualPrice, p?.let(::placeLabel).orEmpty()).any { matchesArabicSearch(it, query) }
                            }.sortedWith(errandDisplayOrder)
                            LazyColumn(Modifier.fillMaxSize().testTag("errandList"), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                item { OutlinedTextField(query, { query = it }, label = { Text("ابحث عن طلب أو وجهة") }, singleLine = true, shape = MaterialTheme.shapes.large, leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }, modifier = Modifier.fillMaxWidth(), trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("مسح") } }) }
                                item {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SummaryPill("${errands.count(::active)} معلّقة", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                                        SummaryPill("${errands.count { it.priority == 2 && active(it) }} عاجلة", Color(0xFFFFE8D8), Color(0xFF9A3E12), Modifier.weight(1f))
                                        SummaryPill("${errands.count { it.status == "مكتمل" }} مكتملة", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                                    }
                                }
                                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { bulkInputOpen = true }, modifier = Modifier.weight(1f)) { Text("إدخال قائمة متعددة الأسطر") }
                                    OutlinedButton(onClick = { tab = 3 }, modifier = Modifier.weight(1f)) { Text("المساعد الذكي") }
                                } }
                                if (!settings["travel_place"].isNullOrBlank()) item { AssistChip(onClick = { tab = 2 }, label = { Text("مشوار نشط: ${places.find { it.id == settings["travel_place"] }?.name ?: "راجع الوجهة"}") }) }
                                item {
                                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        FilterChip(selected = filter == "الكل", onClick = { filter = "الكل" }, label = { Text("الكل") })
                                        FilterChip(selected = filter == "غير المنجزة", onClick = { filter = "غير المنجزة" }, label = { Text("معلّق") })
                                        FilterChip(selected = filter == "مكتمل", onClick = { filter = "مكتمل" }, label = { Text("مكتمل") })
                                        FilterChip(selected = advancedFilters, onClick = { advancedFilters = !advancedFilters }, label = { Text("حالات أخرى") })
                                    }
                                }
                                if (advancedFilters) item {
                                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        statuses.filter { it != "مكتمل" }.forEach { value -> FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value) }) }
                                    }
                                }
                                if (placeFilter != null) item { AssistChip(onClick = { placeFilter = null }, label = { Text("الوجهة: ${places.find { it.id == placeFilter }?.name ?: "غير متاحة"} — عرض الجميع ×") }) }
                                if (shown.isEmpty()) item {
                                    EmptyState("لا توجد طلبات هنا", if (errands.isEmpty()) "ابدأ بطلب واحد؛ يمكنك اختيار وجهته الآن أو لاحقًا. قوائمك تعمل دون إنترنت." else "لا توجد نتائج مطابقة. جرّب كلمة أخرى أو اعرض كل الطلبات.", if (errands.isEmpty()) "إضافة أول طلب" else "مسح البحث والمرشّحات", { if (errands.isEmpty()) editingErrand = Errand(title = "", placeId = placeFilter) else { query = ""; filter = "الكل"; placeFilter = null } })
                                }
                                items(shown, key = { it.id }) { e -> ErrandCard(e, places.find { it.id == e.placeId }, { model.toggleErrandCompletion(e.id) }, { editingErrand = e }, { deletingErrand = e }) }
                                item { BudgetSummary(errands) }
                            }
                        }
                        5 -> {
                            var placeQuery by rememberSaveable { mutableStateOf("") }
                            var customOnly by rememberSaveable { mutableStateOf(true) }
                            var parent by rememberSaveable { mutableStateOf<String?>(null) }
                            val current = places.find { it.id == parent }
                            val scopedPlaces = places.filter {
                                if (customOnly) it.kind == "custom" else it.kind != "custom" && it.kind != "country"
                            }
                            val shown = when {
                                placeQuery.isNotBlank() -> scopedPlaces.filter { matchesArabicSearch(placeLabel(it), placeQuery) }
                                customOnly -> scopedPlaces
                                parent == null -> scopedPlaces.filter { it.kind == "governorate" || it.parentId == null }
                                else -> scopedPlaces.filter { it.parentId == parent }
                            }.sortedBy(::placeLabel)
                            LazyColumn(Modifier.fillMaxSize().testTag("placesList"), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { OutlinedTextField(placeQuery, { placeQuery = it }, label = { Text("ابحث في الأماكن") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(customOnly, { customOnly = true }, label = { Text("أماكني") })
                                    FilterChip(!customOnly, { customOnly = false }, label = { Text("دليل المحافظات") })
                                } }
                                if (alertsNeeded) item { AlertReadiness(permissionState, { permissionExplanation = it }, openLocationSettings) }
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
                                            if (!customOnly && placeQuery.isBlank()) TextButton(onClick = { parent = place.id }, modifier = Modifier.testTag("browsePlace_${place.id}")) { Text("تصفح") }
                                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "أقل" else "التفاصيل") }
                                        }
                                        if (expanded) {
                                            Text(if (place.latitude == null) "بلا نقطة وصول؛ أضف إحداثيات من التعديل لتفعيل التنبيه." else "نطاق التنبيه: ${place.radiusMeters.toInt()} متر", style = MaterialTheme.typography.bodySmall)
                                            val completedHere = errands.count { it.placeId == place.id && it.status == "مكتمل" }
                                            if (completedHere > 0) OutlinedButton(onClick = { reopeningPlace = place }, modifier = Modifier.fillMaxWidth().testTag("reopenPlace_${place.id}")) {
                                                Text("إعادة فتح المكتملة هنا ($completedHere)")
                                            }
                                            val validPoint = place.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
                                                place.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true
                                            Row(verticalAlignment = Alignment.CenterVertically) { Switch(place.monitored && validPoint, { model.save(place.copy(monitored = it)) }, enabled = validPoint, modifier = Modifier.semantics { contentDescription = "مراقبة الوصول: ${place.name}" }); Text("مراقبة الوصول", Modifier.weight(1f)) }
                                            if (place.source != "يدوي") Text("${place.source} · ${place.sourceUpdated}", style = MaterialTheme.typography.labelSmall)
                                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                                TextButton(onClick = { placeFilter = place.id; tab = 1 }) { Text("الطلبات") }
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
                            val relevant = if (chosen.isBlank()) emptyList() else errands.filter { it.placeId in subtree(chosen, places) && active(it) }.sortedWith(errandDisplayOrder)
                            val groupedRelevant = relevant.groupBy { it.placeId.orEmpty() }.toSortedMap(compareBy { id -> places.find { it.id == id }?.let(::placeLabel).orEmpty() })
                            val tripAlertCandidates = if (chosen.isBlank()) emptyList() else ye.mishwari.geo.GeoPolicy.select(
                                ye.mishwari.geo.GeoPolicy.fromData(places, errands, chosen), chosen
                            )
                            val chosenTarget = places.find { it.id == chosen }?.takeIf { place ->
                                place.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
                                    place.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true
                            }
                            val mapTargets = tripAlertCandidates.mapNotNull { candidate -> places.find { it.id == candidate.id } }
                                .ifEmpty { listOfNotNull(chosenTarget) }
                            LazyColumn(Modifier.fillMaxSize().testTag("travelList"), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("مشوارك على الخريطة", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                        Text("اختر الوجهة، راجع نقاط الوصول والطلبات، ثم فعّل المشوار. لا نسجّل مسارك.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (destination != null) item {
                                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Column(Modifier.weight(1f)) {
                                                Text("مشوار نشط", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                                Text(destination.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                            }
                                            TextButton(onClick = { model.setting("travel_place", "") }) { Text("إنهاء المشوار") }
                                        }
                                    }
                                }
                                item {
                                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("المكان ونقاط الوصول", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                                Text("${tripAlertCandidates.size} نطاق", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                            }
                                            TripMapPreview(mapTargets, tripCurrentLocation, tripAlertCandidates.map { it.id }.toSet(), Modifier.fillMaxWidth().height(228.dp))
                                            Text(tripLocationMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text("عند عرض موقعك، تُطلب بلاطات OpenStreetMap المحيطة به؛ قد يطّلع مزوّد البلاطات على عنوان IP ومنطقة الخريطة التقريبية، وليس على طلباتك. النقطة قراءة واحدة ولا تتحرك تلقائيًا.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            OutlinedButton(
                                                onClick = {
                                                    tripLocationRequested = true
                                                    if (permissionState.fineLocationGranted || permissionState.coarseLocationGranted) {
                                                        tripLocationMessage = "جارٍ طلب قراءة موقع حديثة لمرة واحدة…"
                                                    } else {
                                                        permissionExplanation = "foreground"
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                            ) { Text(if (permissionState.fineLocationGranted || permissionState.coarseLocationGranted) "تحديث موقعي على الخريطة" else "السماح بالموقع ثم التحديث") }
                                            if (!permissionState.locationEnabled) TextButton(onClick = openLocationSettings) { Text("تشغيل خدمات موقع الهاتف") }
                                        }
                                    }
                                }
                                item { Text("١ · اختر الوجهة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); PlaceSelector("وجهة المشوار", chosen.ifBlank { null }, places, { chosen = it.orEmpty() }, allowNone = true, showAlertReadiness = false) }
                                item { Text("٢ · راجع الطلبات · ${relevant.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); if (chosen.isNotBlank()) Text("طلبات ${places.find { it.id == chosen }?.name.orEmpty()} والأماكن التابعة لها · ${relevant.count { it.priority == 2 }} مهمة", style = MaterialTheme.typography.bodySmall) }
                                item {
                                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("حالة GPS وتنبيهات الوصول", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        Text(when {
                                            chosen.isBlank() -> "اختر وجهة أولاً لعرض نقاط الوصول الممكنة."
                                            tripAlertCandidates.isEmpty() -> "غير جاهزة: لا يوجد طلب نشط مرتبط بمكان ذي إحداثيات صالحة داخل هذه الوجهة. أضف إحداثيات للمكان من شاشة الأماكن."
                                            !permissionState.locationEnabled -> "توجد ${tripAlertCandidates.size} نقطة صالحة، لكن موقع الهاتف مغلق."
                                            !permissionState.notificationsReady() -> "توجد ${tripAlertCandidates.size} نقطة صالحة، لكن إشعارات الوصول غير مفعلة."
                                            else -> "توجد ${tripAlertCandidates.size} نقطة صالحة؛ راجع أذونات الموقع والخلفية أدناه قبل الاعتماد على التنبيه."
                                        }, style = MaterialTheme.typography.bodySmall)
                                    } }
                                }
                                if (relevant.isEmpty()) item { EmptyState("قائمة المشوار فارغة", "اختر وجهة لها طلبات غير منجزة، أو أضف طلبًا مرتبطًا بها.", "إضافة طلب للوجهة", { editingErrand = Errand(title = "", placeId = chosen.ifBlank { null }) }) }
                                items(groupedRelevant.entries.toList(), key = { "trip-place-${it.key}" }) { group ->
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("الوجهة · ${places.find { it.id == group.key }?.let(::placeLabel) ?: "مكان محفوظ"}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        group.value.forEach { e -> ErrandCard(e, places.find { it.id == e.placeId }, { model.toggleErrandCompletion(e.id) }, { editingErrand = e }, { deletingErrand = e }) }
                                    }
                                }
                                item {
                                    Text("٣ · تفعيل المشوار والتنبيهات", style = MaterialTheme.typography.titleMedium)
                                    Text("يُفعّل المشوار نقاط الطلبات المرتبطة بالوجهة فقط. أسماء المحافظات والمديريات وحدها ليست نقاط وصول.", style = MaterialTheme.typography.bodySmall)
                                    Button(onClick = { model.setting("travel_place", chosen) }, enabled = chosen.isNotBlank() && chosen != trip, modifier = Modifier.fillMaxWidth()) { Text("تجهيز المشوار والتنبيهات") }
                                }
                                if (tripAlertCandidates.isNotEmpty()) item { AlertReadiness(permissionState, { permissionExplanation = it }, openLocationSettings) }
                                else item { Text("القائمة تعمل دون أذونات. التنبيه يتطلب طلبًا غير منجز مرتبطًا بمكان ذي إحداثيات صالحة داخل الوجهة، مع إذن الموقع والإشعارات.", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        3 -> AssistantScreen(
                            model = model,
                            errands = errands,
                            places = places,
                            requestVoice = requestVoice,
                            showConfiguration = false,
                            openAiSettings = { showAssistantConfiguration = true; tab = 4 },
                        )
                        4 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("الإعدادات", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("إدارة المساعد والأماكن والخصوصية والمظهر من مكان واحد.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AssistantSettingsSection(model, Modifier.fillMaxWidth(), initiallyExpanded = showAssistantConfiguration)
                            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    FeatureBadge(6, MaterialTheme.colorScheme.secondary)
                                    Text("الأماكن والموقع", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                                Text("افصل أماكنك المحفوظة عن دليل المحافظات والمديريات، وأدر إحداثيات التنبيه من شاشة واحدة.", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { tab = 5 }, modifier = Modifier.fillMaxWidth()) { Text("إدارة الأماكن والدليل") }
                            } }
                            SettingsSection("المظهر والوضع الليلي", initiallyExpanded = true) {
                                Text("اختر مظهر التطبيق أو اتركه يتبع إعداد الهاتف.", style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = settings["theme_mode"].isNullOrBlank() || settings["theme_mode"] == "system", onClick = { model.setting("theme_mode", "system") }, label = { Text("حسب الجهاز") })
                                    FilterChip(selected = settings["theme_mode"] == "light", onClick = { model.setting("theme_mode", "light") }, label = { Text("فاتح") })
                                    FilterChip(selected = settings["theme_mode"] == "dark", onClick = { model.setting("theme_mode", "dark") }, label = { Text("داكن") })
                                }
                            }
                            PermissionSummary(permissionState)
                            Text("الأذونات اختيارية؛ قوائمك تعمل دونها.", style = MaterialTheme.typography.bodySmall)
                            SettingsSection("نسخة احتياطية محلية", badge = "على جهازك") {
                                Text("ملف JSON غير مشفّر، وقد يحتوي بيانات الطلبات وأسماء الأماكن وإحداثيات GPS وصور الطلبات. احفظه بأمان؛ الاستيراد يدمج ولا يحذف سجلات غير موجودة فيه.", modifier = Modifier.testTag("backupDisclosure"), style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = exportBackup, modifier = Modifier.fillMaxWidth()) { Text("تصدير نسخة احتياطية") }
                                OutlinedButton(onClick = { importing = true }, modifier = Modifier.fillMaxWidth()) { Text("استيراد نسخة احتياطية") }
                            }
                            SettingsSection("الأذونات والخصوصية", badge = "متقدم") {
                                if (!permissionState.fineLocationGranted) PermissionAction("الموقع أثناء الاستخدام", if (permissionState.coarseLocationGranted) "الموقع التقريبي متاح؛ تنبيه الوصول يحتاج الدقة." else "غير مسموح؛ لن تتاح الميزات التي تحتاج موقعك.", "مراجعة إذن الموقع") { permissionExplanation = "foreground" }
                                if (!permissionState.notificationsReady()) PermissionAction("الإشعارات غير مفعلة", "لن يظهر تنبيه الوصول حتى تسمح بها.", "مراجعة الإشعارات") { permissionExplanation = "notifications" }
                                if (alertsNeeded && permissionState.fineLocationGranted && !permissionState.backgroundLocationGranted) PermissionAction("تنبيهات الخلفية غير جاهزة", "يمكنك المتابعة بالقوائم دون تنبيه عند إغلاق التطبيق.", "إعداد تنبيهات الخلفية") { permissionExplanation = "background" }
                                if (alertsNeeded && !permissionState.locationEnabled) TextButton(onClick = openLocationSettings) { Text("تشغيل موقع الهاتف") }
                                TextButton(onClick = openAppSettings) { Text("أذونات التطبيق وإعدادات البطارية") }
                                Text(if (alertsNeeded) "حالة التنبيهات: ${geoStatusText(settings["geo_status"])}" else "لا توجد نقاط تنبيه مطلوبة حاليًا.", style = MaterialTheme.typography.bodyMedium)
                                OutlinedButton(onClick = model::refreshAlerts, modifier = Modifier.fillMaxWidth()) { Text("إعادة تهيئة تنبيهات الوصول") }
                                Text("استخدمها بعد توقف غير معتاد أو تغيير أذونات؛ يبقى فاصل منع التكرار 30 دقيقة.", style = MaterialTheme.typography.bodySmall)
                                Text("قد تتأخر التنبيهات بسبب توفير البطارية أو ضعف تحديد الموقع، وتعتمد على خدمات Google Play ومزود الموقع الشبكي. قد لا تتولد أحداث وصول دون اتصال بيانات موثوق. لا يوجد GPS مستمر ولا سجل لتحركاتك.", style = MaterialTheme.typography.bodySmall)
                            }
                            SettingsSection("دليل اليمن") {
                                Text("الدليل المضمن يعمل دون إنترنت ويضم 22 محافظة و335 مديرية من OCHA / HDX (COD-AB)، المصدر الأصلي CSO، بترخيص CC BY-IGO 3.0. تاريخ المراجعة 2024-12-23؛ لا يؤكد حداثته حتى 2026، ولا يتضمن إحداثيات أو حدودًا. التفاصيل في docs/directory.md.", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = importDirectory) { Text("استيراد تحديث دليل من JSON") }
                            }
                            SettingsSection("إعدادات عامة") {
                                Text("الخرائط: MapLibre Native وOpenStreetMap؛ تحتاج اتصالًا بالإنترنت، ولا نرسل طلباتك إلى مزود البلاطات.", style = MaterialTheme.typography.bodySmall)
                                Text("الطلب الصوتي يفتح خدمة التعرف على الكلام في الهاتف، ويظل النص مسودة حتى تراجعه.", style = MaterialTheme.typography.bodySmall)
                            }
                            SettingsSection("حول التطبيق") {
                                Text("مشواري · إصدار ${ye.mishwari.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text("إصدار اختبار أولي. اختبر الوصول والتنبيهات في الخلفية على هاتفك قبل الاعتماد عليها لمهام مهمة.", style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
            editingErrand?.let { value -> ErrandEditor(value, places, { editingErrand = null; model.finishPhotoEditing() }, { model.save(it) { editingErrand = null; model.finishPhotoEditing() } }, { editingPlace = Place(name = "") }, selectPhoto) }
            editingPlace?.let { value -> PlaceEditor(value, places, { editingPlace = null }, { place -> model.save(place) { editingPlace = null; editingErrand = editingErrand?.copy(placeId = place.id) } }) }
            if (bulkInputOpen) BulkErrandInputDialog(
                places = places,
                onDismiss = { bulkInputOpen = false },
                onSave = { drafts -> model.saveErrandsBatch(drafts) { bulkInputOpen = false } },
                enabled = !bulkSaveBusy,
            )
            deletingErrand?.let { e -> Confirm("حذف الطلب؟", "سيُحذف «${e.title}» من هذا الجهاز.", { deletingErrand = null }, { model.remove(e); deletingErrand = null }) }
            deletingPlace?.let { p -> Confirm("حذف المكان؟", "تبقى طلباته المباشرة محفوظة دون وجهة، وتبقى الأماكن التابعة وطلباتها محفوظة. ستُفصل الأماكن التابعة مباشرةً عن هذا الأب فقط.", { deletingPlace = null }, { model.remove(p); deletingPlace = null }) }
            reopeningPlace?.let { place ->
                val count = errands.count { it.placeId == place.id && it.status == "مكتمل" }
                Confirm(
                    "إعادة فتح الطلبات المكتملة؟",
                    "سيُعاد فتح $count طلبًا مرتبطًا بـ«${place.name}» بتحديث سجلاته الأصلية فقط، دون إنشاء نسخ مكررة.",
                    { reopeningPlace = null },
                    { reopeningPlace = null; model.reopenCompletedAtPlace(place.id) },
                )
            }
            permissionExplanation?.let { kind -> PermissionExplanation(kind, { permissionExplanation = null; if (kind == "foreground") tripLocationRequested = false }) {
                permissionExplanation = null
                when (kind) {
                    "foreground" -> if (!permissionState.fineLocationGranted) requestLocation()
                    "notifications" -> if (!permissionState.notificationsReady()) requestNotifications()
                    "background" -> if (alertsNeeded && permissionState.fineLocationGranted && !permissionState.backgroundLocationGranted) requestBackgroundLocation()
                }
            } }
            if (importing) Confirm("استيراد نسخة محلية", "تُدمج البيانات ويُحدّث السجل ذو المعرّف نفسه. لا تُحذف السجلات غير الموجودة في الملف. يُنصح بتصدير نسخة حالية أولًا. لن تُعاد تفعيل مراقبة الخلفية دون مراجعتك.", { importing = false }, { importing = false; importBackup() })
            if (voiceExplanation) AlertDialog(onDismissRequest = { voiceExplanation = false },
                title = { Text("إملاء طلب بالعربية") },
                text = { Text("سيُفتح تطبيق التعرف على الكلام في هاتفك. نطلب التعرّف دون اتصال، لكن الخدمة قد تستخدم الإنترنت وترسل الصوت إلى مزودها حسب إعداداتها. طلباتي للمشوار لا يسجل الصوت ولا يحتفظ به. راجع النص واختر الوجهة قبل الحفظ؛ الكتابة متاحة دائمًا.") },
                confirmButton = { TextButton(onClick = { voiceExplanation = false; requestVoice() }) { Text("متابعة إلى الإملاء") } },
                dismissButton = { TextButton(onClick = { voiceExplanation = false }) { Text("إلغاء") } })
            operationError?.let { message -> AlertDialog(onDismissRequest = { model.error.value = null }, title = { Text("لم تُكتمل العملية") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { model.error.value = null }) { Text("حسنًا") } }) }
        }
    }
}

@Composable private fun SummaryPill(text: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Surface(modifier, color = background, shape = RoundedCornerShape(16.dp)) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 7.dp), color = foreground, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun SettingsSection(title: String, initiallyExpanded: Boolean = false, badge: String? = null, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val icon = when (title) { "نسخة احتياطية محلية" -> 5; "الأذونات والخصوصية" -> 6; "دليل اليمن" -> 2; "المظهر" -> 8; "حول التطبيق" -> 9; else -> 4 }
    val accent = when (icon) { 5 -> MaterialTheme.colorScheme.tertiary; 6, 8 -> MaterialTheme.colorScheme.secondary; 9 -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.primary }
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            FeatureBadge(icon, accent)
            Spacer(Modifier.width(10.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (badge != null) Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (expanded) "إخفاء" else "عرض")
        }
        if (expanded) Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable private fun EmptyState(title: String, body: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && actionLabel != null) TextButton(onClick = action) { Text(actionLabel) }
    } }
}
@Composable private fun Confirm(title: String, body: String, dismiss: () -> Unit, confirm: () -> Unit) { AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) }, confirmButton = { TextButton(onClick = confirm) { Text("متابعة") } }, dismissButton = { TextButton(onClick = dismiss) { Text("إلغاء") } }) }
internal fun GeoEnvironmentSignature.notificationsReady() = notificationPermissionGranted && appNotificationsEnabled &&
    geoChannelImportance != android.app.NotificationManager.IMPORTANCE_NONE

internal fun nextAlertPermission(state: GeoEnvironmentSignature): String? = when {
    !state.fineLocationGranted -> "foreground"
    !state.notificationsReady() -> "notifications"
    !state.backgroundLocationGranted -> "background"
    else -> null
}

@Composable internal fun PermissionSummary(state: GeoEnvironmentSignature) {
    Surface(Modifier.fillMaxWidth().testTag("permissionSummary"), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when {
                state.fineLocationGranted && state.locationEnabled -> "الموقع جاهز"
                state.fineLocationGranted -> "إذن الموقع ممنوح · موقع الهاتف مغلق"
                state.coarseLocationGranted -> "الموقع التقريبي متاح"
                else -> "الموقع اختياري · غير مسموح"
            }, style = MaterialTheme.typography.bodySmall)
            Text(if (state.notificationsReady()) "الإشعارات مفعلة" else "الإشعارات غير مفعلة · القوائم تعمل كالمعتاد", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun PermissionAction(title: String, body: String, action: String, onClick: () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(action) }
}

/** Only shown where there is an actual arrival-alert candidate, never as a gate to lists. */
@Composable internal fun AlertReadiness(state: GeoEnvironmentSignature, explain: (String) -> Unit, openLocationSettings: () -> Unit) {
    val missing = nextAlertPermission(state)
    OutlinedCard(Modifier.fillMaxWidth().testTag("alertReadiness")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (missing == null && state.locationEnabled) "أذونات تنبيهات الوصول جاهزة" else "تنبيهات الوصول تحتاج إعدادًا", style = MaterialTheme.typography.titleSmall)
            if (missing != null || !state.locationEnabled) Text("لن يصلك تنبيه موثوق قبل إكمال الإعداد. يمكنك استخدام قائمة المشوار دون تنبيهات.", style = MaterialTheme.typography.bodySmall)
            if (missing != null) OutlinedButton(onClick = { explain(missing) }) { Text(when (missing) {
                "foreground" -> "إعداد الموقع أثناء الاستخدام"
                "notifications" -> "إعداد إشعارات الوصول"
                else -> "إعداد تنبيهات الخلفية"
            }) }
            else if (!state.locationEnabled) TextButton(onClick = openLocationSettings) { Text("تشغيل موقع الهاتف") }
        }
    }
}

@Composable private fun PermissionExplanation(kind: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(when (kind) {
            "foreground" -> "الموقع أثناء الاستخدام"
            "notifications" -> "إشعارات الوصول"
            else -> "التذكير حتى والتطبيق مغلق"
        }) },
        text = { Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) { Text(when (kind) {
            "foreground" -> "يفيد الموقع الدقيق في إعداد تنبيه وصول صحيح لأماكنك. سنطلب الموقع أثناء الاستخدام فقط، وليس في الخلفية. إذا رفضت، تبقى الطلبات والأماكن والنسخ الاحتياطي متاحة. إذا سبق رفض الإذن نهائيًا، ستفتح إعدادات التطبيق بدل تكرار الطلب."
            "notifications" -> "اسمح بالإشعارات كي يظهر تذكير طلباتك عند الوصول. إذا رفضت، لن تظهر تنبيهات الوصول؛ تبقى قوائمك متاحة. قد تُفتح إعدادات الإشعارات إذا كانت محظورة من النظام."
            else -> "لتلقي تنبيه وصول للمكان المرتبط بطلبك حتى والتطبيق مغلق، يحتاج طلباتي للمشوار إذن الموقع في الخلفية. اختر «السماح طوال الوقت» إن ظهر في الإعدادات. تُرسل نقاط التنبيه المختارة إلى خدمة السياجات على الجهاز؛ لا نخزن سجل تنقلاتك ولا نستخدم خادمًا خاصًا. يمكنك الرفض واستخدام قوائمك دون تنبيهات الخلفية."
        }, modifier = Modifier.fillMaxWidth().testTag("permissionExplanationBody")) } },
        confirmButton = { TextButton(onClick = confirm) { Text("متابعة") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("ليس الآن") } })
}

@Composable private fun ErrandCard(e: Errand, place: Place?, complete: () -> Unit, edit: () -> Unit, delete: () -> Unit) {
    val priorityLabel = listOf("منخفضة", "عادية", "عالية").getOrElse(e.priority) { "عادية" }
    val priorityColor = when (e.priority) {
        2 -> MaterialTheme.colorScheme.error
        1 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) { Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(place?.let(::placeLabel) ?: "دون وجهة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(e.status == "مكتمل", { complete() }, modifier = Modifier.testTag("errandCheckbox_${e.id}").semantics { contentDescription = if (e.status == "مكتمل") "إعادة فتح الطلب: ${e.title}" else "إكمال الطلب: ${e.title}" })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            SummaryPill(priorityLabel, priorityColor.copy(alpha = 0.12f), priorityColor)
            SummaryPill(e.status, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            Text(e.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        var details by rememberSaveable(e.id) { mutableStateOf(false) }
        if (details) {
        if (e.description.isNotBlank()) Text(e.description)
        if (e.quantity.isNotBlank() || e.expectedPrice.isNotBlank() || e.actualPrice.isNotBlank()) Text("الكمية: ${e.quantity.ifBlank { "—" }} · المتوقع: ${e.expectedPrice.ifBlank { "—" }} · الفعلي: ${e.actualPrice.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
        if (e.dueDate.isNotBlank()) Text("الاستحقاق: ${e.dueDate}", style = MaterialTheme.typography.bodySmall)
        if (e.notes.isNotBlank()) Text(e.notes, style = MaterialTheme.typography.bodySmall)
        if (e.photoPath != null) Text("صورة مرفقة — افتح التعديل لعرضها", style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            if (e.description.isNotBlank() || e.notes.isNotBlank() || e.quantity.isNotBlank() || e.expectedPrice.isNotBlank() || e.actualPrice.isNotBlank() || e.dueDate.isNotBlank() || e.photoPath != null) TextButton(onClick = { details = !details }) { Text(if (details) "أقل" else "التفاصيل") }
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
