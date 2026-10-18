package ye.mishwari.geo

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofenceStatusCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import ye.mishwari.MainActivity
import ye.mishwari.R
import ye.mishwari.data.AppSetting
import ye.mishwari.data.Errand
import ye.mishwari.data.MishwariDatabase

class GeoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val app = context.applicationContext
        receiverScope.launch {
            try {
                withTimeout(EVENT_TIMEOUT_MILLIS) {
                    val dao = MishwariDatabase.get(app).dao()
                    val event = GeofencingEvent.fromIntent(intent)
                    if (event == null) {
                        dao.putSetting(AppSetting(key = GeoWorker.GEO_STATUS_SETTING, value = "event_invalid"))
                        return@withTimeout
                    }
                    if (event.hasError()) {
                        if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                            GeoScheduler.schedule(app, force = true, rearmSelected = true)
                        }
                        dao.putSetting(AppSetting(key = GeoWorker.GEO_STATUS_SETTING, value = "event_error:${event.errorCode}"))
                        return@withTimeout
                    }
                    val transition = event.geofenceTransition
                    if (transition != Geofence.GEOFENCE_TRANSITION_ENTER && transition != Geofence.GEOFENCE_TRANSITION_EXIT) {
                        dao.putSetting(AppSetting(key = GeoWorker.GEO_STATUS_SETTING, value = "event_transition_unsupported:$transition"))
                        return@withTimeout
                    }
                    processTransition(app, transition, event.triggeringGeofences.orEmpty().mapNotNull { it.requestId })
                }
            } catch (_: CancellationException) {
                // goAsync is completed in finally; do not launch unbounded work after timeout.
            } catch (_: Exception) {
                runCatching { MishwariDatabase.get(app).dao().putSetting(AppSetting(key = GeoWorker.GEO_STATUS_SETTING, value = "receiver_error")) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** Process a validated ENTER/EXIT transition; public for Android/Robolectric integration tests. */
    suspend fun processTransition(context: Context, transition: Int, placeIds: List<String>, nowMillis: Long = System.currentTimeMillis()) {
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER && transition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        val app = context.applicationContext
        val dao = MishwariDatabase.get(app).dao()
        val places = dao.allPlaces()
        val errands = dao.allErrands()
        val travelPlaceId = dao.getSetting(GeoWorker.TRAVEL_PLACE_SETTING)?.takeIf { it.isNotBlank() }
        val currentlySelected = GeoPolicy.select(GeoPolicy.fromData(places, errands, travelPlaceId), travelPlaceId)
            .mapTo(HashSet()) { it.id }
        val placeNames = places.associate { it.id to it.name }
        for (placeId in placeIds.distinct()) {
            processingLock.withLock {
                if (placeId !in currentlySelected) {
                    // Settings may change before the asynchronous unregister completes.
                    rearmPlace(app, placeId)
                    return@withLock
                }
                val state = readState(app, placeId)
                if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
                    val decision = AlertPolicy.onExit(state)
                    if (decision.shouldPersist) saveState(app, placeId, decision.state)
                } else {
                    val placeErrands = dao.errandsForPlace(placeId).filter { GeoPolicy.isActiveStatus(it.status) }
                    val decision = AlertPolicy.onEnter(
                        state = state,
                        nowMillis = nowMillis,
                        hasActiveTasks = placeErrands.isNotEmpty(),
                        notificationsAllowed = notificationsAllowed(app),
                    )
                    if (decision.shouldPersist && (!decision.shouldNotify || showNotification(app, placeId, placeNames[placeId] ?: "مكان قريب", placeErrands))) {
                        saveState(app, placeId, decision.state)
                    }
                }
            }
        }
    }

    private fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) return false
        ensureChannel(manager)
        return Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(CHANNEL_ID)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "تنبيهات الأماكن", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun showNotification(context: Context, placeId: String, placeName: String, errands: List<Errand>): Boolean {
        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
            ensureChannel(manager)
            val launchIntent = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_PLACE_ID, placeId)
                .setData(Uri.parse("mishwari://geo/place/${Uri.encode(placeId)}"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val contentIntent = PendingIntent.getActivity(context, placeId.hashCode(), launchIntent, piFlags)
            val names = errands.take(MAX_TASK_TITLES).joinToString("، ") { it.title }
            val remainder = errands.size - MAX_TASK_TITLES
            val summary = if (remainder > 0) "$names و$remainder أخرى" else names
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("قرب $placeName")
                .setContentText("${errands.size} مهام مرتبطة بهذا المكان")
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            manager.notify(placeId.hashCode(), notification)
            true
        } catch (_: Exception) { false }
    }

    private fun readState(context: Context, placeId: String): AlertPolicy.State {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val suffix = keySuffix(placeId)
        return AlertPolicy.State(
            inside = preferences.getBoolean("inside_$suffix", false),
            lastAlertAtMillis = preferences.getLong("last_alert_$suffix", 0L),
        )
    }

    private fun saveState(context: Context, placeId: String, state: AlertPolicy.State) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val suffix = keySuffix(placeId)
        preferences.edit().putBoolean("inside_$suffix", state.inside)
            .putLong("last_alert_$suffix", state.lastAlertAtMillis).commit()
    }

    private fun rearmPlace(context: Context, placeId: String) {
        val state = readState(context, placeId)
        if (state.inside) saveState(context, placeId, state.copy(inside = false))
    }

    private fun keySuffix(id: String): String = Uri.encode(id)

    companion object {
        const val EXTRA_PLACE_ID = "place_id"
        private const val CHANNEL_ID = "mishwari_geo_alerts"
        private const val PREFERENCES = "mishwari_geo_alert_policy"
        private const val MAX_TASK_TITLES = 4
        private const val EVENT_TIMEOUT_MILLIS = 8_000L
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val processingLock = kotlinx.coroutines.sync.Mutex()

        /** Clear stale occupied-state only for places no longer monitored; retain cooldown timestamps. */
        internal fun rearmUnselected(context: Context, selectedIds: Set<String>) {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val editor = preferences.edit()
            var changed = false
            preferences.all.keys.filter { it.startsWith("inside_") }.forEach { key ->
                val placeId = Uri.decode(key.removePrefix("inside_"))
                if (placeId !in selectedIds && preferences.getBoolean(key, false)) {
                    editor.putBoolean(key, false)
                    changed = true
                }
            }
            if (changed) editor.commit()
        }

        /** Clear stale inside flags after boot/provider loss, but retain cooldown timestamps. */
        internal fun rearmSelected(context: Context, selectedIds: Set<String>) {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val editor = preferences.edit()
            var changed = false
            preferences.all.keys.filter { it.startsWith("inside_") }.forEach { key ->
                val placeId = Uri.decode(key.removePrefix("inside_"))
                if (placeId in selectedIds && preferences.getBoolean(key, false)) {
                    editor.putBoolean(key, false)
                    changed = true
                }
            }
            if (changed) editor.commit()
        }
    }
}
