package ye.mishwari

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permission state, including whether a denied permission can still be requested. */
internal enum class PermissionStatus {
    GRANTED,
    NOT_REQUESTED,
    DENIED_CAN_ASK,
    PERMANENTLY_DENIED,
    SETTINGS_REQUIRED,
    NOT_APPLICABLE,
}

internal enum class AlertRequirement {
    FOREGROUND_LOCATION,
    LOCATION_SERVICES,
    NOTIFICATIONS,
    BACKGROUND_LOCATION,
}

/** Snapshot of the permissions and system switches needed for location alerts. */
internal data class PermissionState(
    val fineLocation: PermissionStatus,
    val coarseLocation: PermissionStatus,
    val backgroundLocation: PermissionStatus,
    val notifications: PermissionStatus,
    val appNotificationsEnabled: Boolean,
    val geoChannelEnabled: Boolean,
    val systemLocationEnabled: Boolean,
    val nextMissingAlertRequirement: AlertRequirement?,
) {
    val foregroundLocationGranted: Boolean
        get() = fineLocation == PermissionStatus.GRANTED || coarseLocation == PermissionStatus.GRANTED

    companion object {
        internal const val ASKED_PREFERENCES = "mishwari_permission_requests"
        internal const val GEO_CHANNEL_ID = "mishwari_geo_alerts"

        /**
         * [shouldShowRationale] is supplied by an Activity so this snapshot remains usable
         * from non-Activity contexts and tests. Asked flags are written before launching prompts.
         */
        fun capture(
            context: Context,
            shouldShowRationale: (String) -> Boolean = { false },
        ): PermissionState {
            val fine = permissionStatus(context, Manifest.permission.ACCESS_FINE_LOCATION, shouldShowRationale)
            val coarse = permissionStatus(context, Manifest.permission.ACCESS_COARSE_LOCATION, shouldShowRationale)
            val background = when {
                Build.VERSION.SDK_INT < 29 -> PermissionStatus.NOT_APPLICABLE
                Build.VERSION.SDK_INT >= 30 && isGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> PermissionStatus.GRANTED
                Build.VERSION.SDK_INT >= 30 && wasAsked(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> PermissionStatus.SETTINGS_REQUIRED
                else -> permissionStatus(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION, shouldShowRationale)
            }
            val notifications = if (Build.VERSION.SDK_INT < 33) PermissionStatus.NOT_APPLICABLE
            else permissionStatus(context, Manifest.permission.POST_NOTIFICATIONS, shouldShowRationale)
            val manager = context.getSystemService(NotificationManager::class.java)
            val appNotificationsEnabled = Build.VERSION.SDK_INT < 24 || manager?.areNotificationsEnabled() != false
            val geoChannelEnabled = Build.VERSION.SDK_INT < 26 ||
                manager?.getNotificationChannel(GEO_CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
            val systemLocationEnabled = runCatching {
                val locationManager = context.getSystemService(LocationManager::class.java)
                if (Build.VERSION.SDK_INT >= 28) locationManager?.isLocationEnabled == true
                else locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                    locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            }.getOrDefault(false)
            val next = when {
                fine != PermissionStatus.GRANTED -> AlertRequirement.FOREGROUND_LOCATION
                !systemLocationEnabled -> AlertRequirement.LOCATION_SERVICES
                notifications != PermissionStatus.NOT_APPLICABLE && notifications != PermissionStatus.GRANTED ||
                    !appNotificationsEnabled || !geoChannelEnabled -> AlertRequirement.NOTIFICATIONS
                Build.VERSION.SDK_INT >= 29 && background != PermissionStatus.GRANTED -> AlertRequirement.BACKGROUND_LOCATION
                else -> null
            }
            return PermissionState(
                fineLocation = fine,
                coarseLocation = coarse,
                backgroundLocation = background,
                notifications = notifications,
                appNotificationsEnabled = appNotificationsEnabled,
                geoChannelEnabled = geoChannelEnabled,
                systemLocationEnabled = systemLocationEnabled,
                nextMissingAlertRequirement = next,
            )
        }

        fun permissionStatus(
            context: Context,
            permission: String,
            shouldShowRationale: (String) -> Boolean = { false },
        ): PermissionStatus {
            if (isGranted(context, permission)) return PermissionStatus.GRANTED
            val asked = wasAsked(context, permission)
            if (!asked) return PermissionStatus.NOT_REQUESTED
            return if (runCatching { shouldShowRationale(permission) }.getOrDefault(false)) {
                PermissionStatus.DENIED_CAN_ASK
            } else {
                PermissionStatus.PERMANENTLY_DENIED
            }
        }

        fun markAsked(context: Context, permission: String) {
            context.getSharedPreferences(ASKED_PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(permission, true).apply()
        }

        fun wasAsked(context: Context, permission: String): Boolean =
            context.getSharedPreferences(ASKED_PREFERENCES, Context.MODE_PRIVATE).getBoolean(permission, false)

        fun isGranted(context: Context, permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
