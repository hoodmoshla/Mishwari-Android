package ye.mishwari.geo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.common.ConnectionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import ye.mishwari.data.AppSetting
import ye.mishwari.data.MishwariDatabase
import java.security.MessageDigest

class GeoWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = reconcileLock.withLock { reconcile() }

    private suspend fun reconcile(): Result {
        val context = applicationContext
        return try {
            val dao = MishwariDatabase.get(context).dao()
            val missingPermission = permissionProblem(context)
            if (missingPermission != null) {
                removeExistingBestEffort(context)
                GeoReceiver.rearmUnselected(context, emptySet())
                dao.putSetting(AppSetting(key = GEO_FINGERPRINT_SETTING, value = ""))
                dao.putSetting(AppSetting(key = GEO_STATUS_SETTING, value = "permission_required:$missingPermission"))
                return Result.success()
            }
            if (!isLocationEnabled(context)) {
                removeExistingBestEffort(context)
                GeoReceiver.rearmUnselected(context, emptySet())
                dao.putSetting(AppSetting(key = GEO_FINGERPRINT_SETTING, value = ""))
                dao.putSetting(AppSetting(key = GEO_STATUS_SETTING, value = "location_disabled"))
                return Result.success()
            }
            val playServices = GeoFenceTransportProvider.playServicesStatus(context)
            if (playServices != ConnectionResult.SUCCESS) {
                // The Geofencing client may itself be unavailable, so removal is best effort.
                removeExistingBestEffort(context)
                GeoReceiver.rearmUnselected(context, emptySet())
                dao.putSetting(AppSetting(key = GEO_FINGERPRINT_SETTING, value = ""))
                dao.putSetting(AppSetting(key = GEO_STATUS_SETTING, value = "play_services_unavailable:$playServices"))
                return Result.success()
            }

            val snapshot = dao.snapshot()
            val settings = snapshot.settings.associate { it.key to it.value }
            val travelPlaceId = settings[TRAVEL_PLACE_SETTING]?.takeIf { it.isNotBlank() }
            val selected = GeoPolicy.select(GeoPolicy.fromData(snapshot.places, snapshot.errands, travelPlaceId), travelPlaceId)
            val currentFingerprint = fingerprint(selected)
            val forceReconcile = inputData.getBoolean(INPUT_FORCE_RECONCILE, false)
            if (!forceReconcile && settings[GEO_FINGERPRINT_SETTING] == currentFingerprint) {
                dao.putSetting(AppSetting(key = GEO_STATUS_SETTING, value = "active:${selected.size}"))
                return Result.success()
            }

            val transport = GeoFenceTransportProvider.create(context)
            // Invalidate before mutation; if remove/add fails the next attempt cannot trust the old fingerprint.
            dao.putSetting(AppSetting(key = GEO_FINGERPRINT_SETTING, value = ""))
            // Replace the complete selected set so deleted or unselected places cannot remain registered.
            withTimeout(GOOGLE_TASK_TIMEOUT_MILLIS) { transport.removeAll() }
            val selectedIds = selected.mapTo(HashSet()) { it.id }
            GeoReceiver.rearmUnselected(context, selectedIds)
            if (inputData.getBoolean(INPUT_REARM_SELECTED, false)) {
                GeoReceiver.rearmSelected(context, selectedIds)
            }
            if (selected.isNotEmpty()) {
                withTimeout(GOOGLE_TASK_TIMEOUT_MILLIS) { transport.addAll(selected) }
            }
            dao.putSetting(AppSetting(key = GEO_FINGERPRINT_SETTING, value = currentFingerprint))
            dao.putSetting(AppSetting(key = GEO_STATUS_SETTING, value = "active:${selected.size}"))
            Result.success()
        } catch (timeout: TimeoutCancellationException) {
            runCatching {
                MishwariDatabase.get(context).dao().putSetting(AppSetting(key = GEO_STATUS_SETTING, value = "error:timeout"))
            }
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (security: SecurityException) {
            removeExistingBestEffort(context)
            GeoReceiver.rearmUnselected(context, emptySet())
            runCatching { MishwariDatabase.get(context).dao().putSetting(AppSetting(key = GEO_FINGERPRINT_SETTING, value = "")) }
            runCatching { MishwariDatabase.get(context).dao().putSetting(AppSetting(key = GEO_STATUS_SETTING, value = "permission_required:security_exception")) }
            Result.success()
        } catch (failure: GeoFenceTransportException) {
            val status = "error:google:${failure.statusCode}:${failure.statusName}"
            runCatching { MishwariDatabase.get(context).dao().putSetting(AppSetting(key = GEO_STATUS_SETTING, value = status)) }
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (failure: Exception) {
            runCatching {
                MishwariDatabase.get(context).dao().putSetting(AppSetting(key = GEO_STATUS_SETTING, value = "error:${failure.javaClass.simpleName}"))
            }
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun permissionProblem(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "fine_location"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "background_location"
        }
        return null
    }

    private fun isLocationEnabled(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled
            else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { false }
    }

    private suspend fun removeExistingBestEffort(context: Context) {
        try {
            if (GeoFenceTransportProvider.playServicesStatus(context) != ConnectionResult.SUCCESS) return
            withTimeout(GOOGLE_TASK_TIMEOUT_MILLIS) { GeoFenceTransportProvider.create(context).removeAll() }
        } catch (_: TimeoutCancellationException) {
            // Missing service response must not prevent reporting a revoked permission.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Permission loss or unavailable Play services can prevent explicit removal.
        }
    }

    private fun fingerprint(selected: List<GeoPolicy.Candidate>): String {
        val canonical = selected.sortedBy { it.id }.joinToString("\n") { GeoFenceIdentity.requestId(it) }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val reconcileLock = kotlinx.coroutines.sync.Mutex()
        const val GEO_STATUS_SETTING = "geo_status"
        const val TRAVEL_PLACE_SETTING = "travel_place"
        const val GEO_FINGERPRINT_SETTING = "geo_fence_fingerprint"
        const val ACTION_GEOFENCE_EVENT = "ye.mishwari.geo.GEOFENCE_EVENT"
        const val INPUT_FORCE_RECONCILE = "force_geo_reconcile"
        const val INPUT_REARM_SELECTED = "rearm_selected_geo_state"
        private const val GOOGLE_TASK_TIMEOUT_MILLIS = 12_000L
    }
}
