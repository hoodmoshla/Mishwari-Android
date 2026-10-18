package ye.mishwari.geo

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import ye.mishwari.geo.GeoWorker.Companion.ACTION_GEOFENCE_EVENT
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The small transport boundary used by production registration and test fakes. */
internal interface GeoFenceTransport {
    suspend fun removeAll()
    suspend fun addAll(places: List<GeoPolicy.Candidate>)
}

/** Keeps Google Play services as the production engine while making its boundary replaceable. */
internal object GeoFenceTransportProvider {
    @Volatile private var testFactory: ((Context) -> GeoFenceTransport)? = null
    @Volatile private var testPlayServicesStatus: Int? = null

    fun create(context: Context): GeoFenceTransport =
        testFactory?.invoke(context) ?: GoogleGeoFenceTransport(context)

    fun playServicesStatus(context: Context): Int = testPlayServicesStatus
        ?: GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)

    /** Test-only seam; always reset in a finally block. */
    fun setForTests(factory: ((Context) -> GeoFenceTransport)?, playServicesStatus: Int? = null) {
        testFactory = factory
        testPlayServicesStatus = playServicesStatus
    }
}

internal class GeoFenceTransportException(
    val statusCode: Int,
    val statusName: String,
    cause: Throwable,
) : Exception("Google geofencing failed ($statusCode: $statusName)", cause)

private class GoogleGeoFenceTransport(private val context: Context) : GeoFenceTransport {
    override suspend fun removeAll() {
        LocationServices.getGeofencingClient(context)
            .removeGeofences(pendingIntent(context))
            .awaitGoogleTask()
    }

    override suspend fun addAll(places: List<GeoPolicy.Candidate>) {
        if (places.isEmpty()) return
        // Permission may change after the worker checked it; verify again at the API boundary.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            throw SecurityException("Geofencing requires precise and background location access")
        }
        val fences = places.map { place ->
            Geofence.Builder()
                .setRequestId(GeoFenceIdentity.requestId(place))
                .setCircularRegion(place.latitude!!, place.longitude!!, place.radiusMeters)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .setNotificationResponsiveness(NOTIFICATION_RESPONSIVENESS_MILLIS)
                .build()
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(fences)
            .build()
        LocationServices.getGeofencingClient(context)
            .addGeofences(request, pendingIntent(context))
            .awaitGoogleTask()
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeoReceiver::class.java).setAction(ACTION_GEOFENCE_EVENT)
        val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            GEOFENCE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutability,
        )
    }

    private suspend fun Task<Void>.awaitGoogleTask() = suspendCancellableCoroutine<Unit> { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            if (task.isSuccessful) {
                continuation.resume(Unit)
            } else {
                val cause = task.exception ?: IllegalStateException("Google Play services task failed")
                val api = cause as? ApiException
                if (api != null) {
                    val statusName = runCatching { GeofenceStatusCodes.getStatusCodeString(api.statusCode) }
                        .getOrDefault(api.statusCode.toString())
                    continuation.resumeWithException(GeoFenceTransportException(api.statusCode, statusName, cause))
                } else {
                    continuation.resumeWithException(cause)
                }
            }
        }
    }

    private companion object {
        const val GEOFENCE_REQUEST_CODE = 7301
        const val NOTIFICATION_RESPONSIVENESS_MILLIS = 120_000
    }
}
