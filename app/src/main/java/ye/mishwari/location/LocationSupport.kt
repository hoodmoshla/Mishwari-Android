package ye.mishwari.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import ye.mishwari.data.Place
import java.util.concurrent.atomic.AtomicBoolean

/** A one-shot, foreground-only location capture. It never reads last-known locations. */
internal class CurrentLocationRequest(
    context: Context,
    private val callback: (LocationCaptureResult) -> Unit,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val nowElapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val finished = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var registered = false

    private val timeout = Runnable { finish(LocationCaptureResult.Failure(LocationCaptureFailure.TIMEOUT)) }
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val fix = checkedCurrentFix(location, nowElapsedNanos())
            if (fix != null) finish(LocationCaptureResult.Success(fix.latitude, fix.longitude, fix.accuracyMeters))
        }

        @Deprecated("Deprecated by Android; provider status is not used for capture decisions")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    /** Begin only in response to an explicit user action after foreground permission is granted. */
    fun start(): CurrentLocationRequest {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { startOnMainThread() }
        } else startOnMainThread()
        return this
    }

    private fun startOnMainThread() {
        if (cancelled.get() || finished.get()) return
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) {
            finish(LocationCaptureResult.Failure(LocationCaptureFailure.PERMISSION_REQUIRED))
            return
        }
        val manager = locationManager
        if (manager == null) {
            finish(LocationCaptureResult.Failure(LocationCaptureFailure.UNAVAILABLE))
            return
        }
        val providers = buildList {
            if (fine && providerEnabled(manager, LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (providerEnabled(manager, LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }.distinct()
        if (providers.isEmpty()) {
            finish(LocationCaptureResult.Failure(LocationCaptureFailure.PROVIDER_DISABLED))
            return
        }
        try {
            for (provider in providers) {
                if (cancelled.get() || finished.get()) break
                // Mark before registering: a synchronous/very fast callback can finish and remove updates immediately.
                registered = true
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
            if (!cancelled.get() && !finished.get()) handler.postDelayed(timeout, timeoutMillis)
        } catch (_: SecurityException) {
            finish(LocationCaptureResult.Failure(LocationCaptureFailure.PERMISSION_REQUIRED))
        } catch (_: RuntimeException) {
            finish(LocationCaptureResult.Failure(LocationCaptureFailure.UNAVAILABLE))
        }
    }

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        handler.removeCallbacks(timeout)
        val cleanup = Runnable { removeUpdates() }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else handler.post(cleanup)
    }

    private fun finish(result: LocationCaptureResult) {
        if (cancelled.get() || !finished.compareAndSet(false, true)) return
        handler.removeCallbacks(timeout)
        removeUpdates()
        if (!cancelled.get()) callback(result)
    }

    private fun removeUpdates() {
        if (!registered) return
        registered = false
        runCatching { locationManager?.removeUpdates(listener) }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun providerEnabled(manager: LocationManager, provider: String): Boolean =
        runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L
        const val MAX_FIX_AGE_MILLIS = 30_000L
        const val MAX_ACCEPTED_ACCURACY_METERS = 1_000f
    }
}

internal sealed interface LocationCaptureResult {
    data class Success(val latitude: Double, val longitude: Double, val accuracyMeters: Float) : LocationCaptureResult
    data class Failure(val reason: LocationCaptureFailure) : LocationCaptureResult
}

internal enum class LocationCaptureFailure { PERMISSION_REQUIRED, PROVIDER_DISABLED, TIMEOUT, UNAVAILABLE }

internal data class CurrentLocationFix(val latitude: Double, val longitude: Double, val accuracyMeters: Float)

/** Strictly checks a newly delivered fix; last-known fixes are never requested or accepted by the capture. */
internal fun checkedCurrentFix(location: Location, nowElapsedNanos: Long): CurrentLocationFix? {
    val lat = location.latitude
    val lon = location.longitude
    val accuracy = location.accuracy
    val fixNanos = location.elapsedRealtimeNanos
    if (!lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) return null
    if (!location.hasAccuracy() || !accuracy.isFinite() || accuracy <= 0f || accuracy > CurrentLocationRequest.MAX_ACCEPTED_ACCURACY_METERS) return null
    if (fixNanos <= 0L || nowElapsedNanos < fixNanos) return null
    val ageMillis = (nowElapsedNanos - fixNanos) / 1_000_000L
    if (ageMillis > CurrentLocationRequest.MAX_FIX_AGE_MILLIS) return null
    return CurrentLocationFix(lat, lon, accuracy)
}

/** UI-ready explanation; active:N is an aggregate count, never proof for this particular place. */
internal data class PlaceAlertReadiness(
    val configurationReady: Boolean,
    val registrationMessage: String,
    /** The current scheduler status is aggregate-only, so it cannot confirm any individual place. */
    val registeredForThisPlace: Boolean = false,
)

internal fun placeAlertReadiness(
    place: Place,
    fineLocationGranted: Boolean,
    backgroundLocationGranted: Boolean,
    notificationsGranted: Boolean,
    geoStatus: String?,
    locationServicesEnabled: Boolean = true,
): PlaceAlertReadiness {
    fun blocked(reason: String) = PlaceAlertReadiness(false, reason)
    val blockers = mutableListOf<String>()
    if (!place.monitored) blockers += "المراقبة متوقفة لهذا المكان؛ التنبيه غير مفعّل."
    val coordinatesValid = validCoordinates(place.latitude, place.longitude)
    if (!coordinatesValid) blockers += "لا توجد إحداثيات صالحة؛ لا يمكن تسجيل تنبيه وصول."
    if (!place.monitored || !coordinatesValid) return blocked(blockers.joinToString(" "))
    if (!locationServicesEnabled) blockers += "خدمات الموقع متوقفة على الجهاز؛ لا يمكن تسجيل التنبيه الآن."
    if (!fineLocationGranted) blockers += "يتطلب تنبيه الوصول إذن الموقع الدقيق أثناء الاستخدام."
    if (!backgroundLocationGranted) blockers += "يتطلب تنبيه الوصول إذن الموقع الدائم؛ التقاط الموقع مرة واحدة لا يحتاجه."
    if (!notificationsGranted) blockers += "إذن الإشعارات غير متاح؛ لن يصلك تنبيه."
    if (blockers.isNotEmpty()) return blocked(blockers.joinToString(" "))
    val status = geoStatus?.takeIf { it.isNotBlank() }
        ?: return blocked("لم تُعرف حالة تسجيل التنبيهات بعد؛ لم يُؤكّد التسجيل.")
    if (status.startsWith("active:")) {
        val count = status.substringAfter(':').toIntOrNull()
        return when {
            count == null -> blocked("حالة التسجيل غير مفهومة؛ لم يُؤكّد تسجيل هذا المكان.")
            count == 0 -> blocked("آخر حالة: لا توجد نطاقات مسجلة. هذا المكان غير مؤكد التسجيل.")
            else -> PlaceAlertReadiness(true, "آخر حالة: أُبلغ عن $count نطاقًا مسجلًا إجمالًا؛ لا يؤكد ذلك أن هذا المكان ضمنها أو يضمن وصول التنبيه.", registeredForThisPlace = false)
        }
    }
    val detail = when {
        status.startsWith("permission_required:") -> "تنقص صلاحية مطلوبة لتسجيل التنبيهات."
        status == "location_disabled" -> "خدمات الموقع متوقفة؛ لا يمكن تسجيل التنبيهات."
        status.startsWith("play_services_unavailable:") -> "خدمة Google Play للموقع غير متاحة؛ لا يمكن تأكيد التسجيل."
        status.startsWith("error:") -> "تعذر تسجيل التنبيهات ($status)؛ لم يُؤكّد التسجيل."
        else -> "حالة التسجيل غير مؤكدة ($status)."
    }
    return blocked(detail)
}

internal fun validCoordinates(latitude: Double?, longitude: Double?): Boolean =
    latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
