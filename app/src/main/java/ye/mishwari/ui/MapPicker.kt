package ye.mishwari.ui

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Locale

/**
 * An OSM-backed point picker. MapLibre requests only tiles needed for the visible map;
 * this screen does not download tiles in bulk or promise offline availability.
 */
@Composable
fun MapPicker(
    initialLatitude: Double?,
    initialLongitude: Double?,
    onPick: (Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val savedStateRegistry = LocalSavedStateRegistryOwner.current.savedStateRegistry
    val uriHandler = LocalUriHandler.current

    val initialPoint = validPoint(initialLatitude, initialLongitude)
    var selectedPoint by remember(initialLatitude, initialLongitude) { mutableStateOf(initialPoint) }
    var mapFailed by remember { mutableStateOf(false) }

    // Consume MapView's saved bundle before creating the native view. A provider below
    // writes it back into the host's SavedStateRegistry during Activity state saving.
    val restoredMapState = remember(savedStateRegistry) {
        savedStateRegistry.consumeRestoredStateForKey(MAP_VIEW_STATE_KEY)
    }
    val mapViewResult = remember(context, restoredMapState) {
        runCatching {
            MapLibreOsmInitialization.initialize(context.applicationContext)
            MapView(context).apply {
                onCreate(restoredMapState)
                addOnDidFailLoadingMapListener { mapFailed = true }
                getMapAsync { map ->
                    val startPoint = initialPoint ?: YEMEN_OVERVIEW
                    map.cameraPosition = CameraPosition.Builder()
                        .target(startPoint)
                        .zoom(if (initialPoint != null) 13.0 else 5.4)
                        .build()

                    var styleReady = false
                    var marker: Marker? = null
                    fun showSelection(point: LatLng) {
                        if (!styleReady) return
                        marker?.let(map::removeMarker)
                        marker = map.addMarker(MarkerOptions().position(point).title("الموقع المحدد"))
                    }

                    map.addOnMapClickListener { point ->
                        selectedPoint = point
                        showSelection(point)
                        true
                    }
                    map.setStyle(Style.Builder().fromJson(OSM_RASTER_STYLE)) {
                        styleReady = true
                        selectedPoint?.let(::showSelection)
                    }
                }
            }
        }
    }
    val mapView = mapViewResult.getOrNull()

    DisposableEffect(lifecycleOwner, mapView, savedStateRegistry, context) {
        if (mapView == null) {
            onDispose { }
        } else {
        var started = false
        var resumed = false
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    mapView.onStart()
                    started = true
                }
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    resumed = true
                }
                Lifecycle.Event.ON_PAUSE -> if (resumed) {
                    mapView.onPause()
                    resumed = false
                }
                Lifecycle.Event.ON_STOP -> if (started) {
                    mapView.onStop()
                    started = false
                }
                Lifecycle.Event.ON_DESTROY -> if (!destroyed) {
                    if (resumed) mapView.onPause()
                    if (started) mapView.onStop()
                    mapView.onDestroy()
                    resumed = false
                    started = false
                    destroyed = true
                }
                else -> Unit
            }
        }
        val savedStateProvider = SavedStateRegistry.SavedStateProvider {
            Bundle().also(mapView::onSaveInstanceState)
        }
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = mapView.onLowMemory()
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mapView.onLowMemory()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        savedStateRegistry.registerSavedStateProvider(MAP_VIEW_STATE_KEY, savedStateProvider)
        context.applicationContext.registerComponentCallbacks(callbacks)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            savedStateRegistry.unregisterSavedStateProvider(MAP_VIEW_STATE_KEY)
            context.applicationContext.unregisterComponentCallbacks(callbacks)
            if (!destroyed) {
                if (resumed) mapView.onPause()
                if (started) mapView.onStop()
                mapView.onDestroy()
            }
        }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("اختيار الموقع", style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = onDismiss) { Text("إغلاق") }
                    }
                    Text(
                        text = "انقر على الخريطة لتحديد النقطة. تحتاج الخريطة إلى اتصال بالإنترنت.",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        if (mapView != null) {
                            AndroidView(
                                factory = { mapView },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        if (mapFailed || mapView == null) {
                            Surface(
                                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    "تعذّر تحميل الخريطة. تحقق من اتصال الإنترنت أو استخدم الإدخال اليدوي للموقع.",
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            selectedPoint?.let { point ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        "خط العرض ${coordinate(point.latitude)}  |  خط الطول ${coordinate(point.longitude)}",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = "© OpenStreetMap contributors",
                                    modifier = Modifier
                                        .clickable { runCatching { uriHandler.openUri(OSM_COPYRIGHT_URL) } }
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text("إلغاء")
                        }
                        Button(
                            onClick = { selectedPoint?.let { onPick(it.latitude, it.longitude) } },
                            enabled = selectedPoint != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("اعتماد الموقع")
                        }
                    }
                }
            }
        }
    }
}

private fun validPoint(latitude: Double?, longitude: Double?): LatLng? {
    if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite()) return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return LatLng(latitude, longitude)
}

private fun coordinate(value: Double): String = String.format(Locale.US, "%.5f", value)

private const val MAP_VIEW_STATE_KEY = "mishwari.map-picker.map-view"
private const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
private const val OSM_USER_AGENT = "Mishwari/0.1.0 (Android; ye.mishwari)"

private object MapLibreOsmInitialization {
    @Volatile private var initialized = false
    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            MapLibre.getInstance(context)
            org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(
                okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    val outgoing = if (request.url.host == "tile.openstreetmap.org")
                        request.newBuilder().header("User-Agent", OSM_USER_AGENT).build() else request
                    chain.proceed(outgoing)
                }.build()
            )
            initialized = true
        }
    }
}

// Approximate Yemen overview camera only; it is not a destination or selected location.
private val YEMEN_OVERVIEW = LatLng(15.5, 47.5)

private val OSM_RASTER_STYLE = """
    {
      "version": 8,
      "sources": {
        "openstreetmap": {
          "type": "raster",
          "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
          "tileSize": 256,
          "attribution": "© OpenStreetMap contributors"
        }
      },
      "layers": [
        {
          "id": "openstreetmap-raster",
          "type": "raster",
          "source": "openstreetmap",
          "minzoom": 0,
          "maxzoom": 19
        }
      ]
    }
""".trimIndent()
