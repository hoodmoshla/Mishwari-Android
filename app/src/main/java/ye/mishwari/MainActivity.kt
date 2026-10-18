package ye.mishwari

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import ye.mishwari.geo.GeoScheduler
import ye.mishwari.input.InputIntents
import ye.mishwari.ui.MishwariRoot
import java.util.Locale
import androidx.core.content.ContextCompat

class MishwariApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val changed = GeoEnvironmentSignature.updateAndDetectChange(this)
        GeoScheduler.schedule(this, force = true, rearmSelected = changed)
    }
}

/** Persisted environmental state used to avoid rearming on every ordinary resume. */
internal data class GeoEnvironmentSignature(
    val fineLocationGranted: Boolean,
    val coarseLocationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val notificationPermissionGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val geoChannelImportance: Int,
    val locationEnabled: Boolean,
) {
    fun serialize(): String = listOf(
        fineLocationGranted, coarseLocationGranted, backgroundLocationGranted,
        notificationPermissionGranted, appNotificationsEnabled, geoChannelImportance, locationEnabled,
    ).joinToString("|")

    companion object {
        private const val PREFS = "mishwari_geo_environment"
        private const val LAST_SIGNATURE = "last_signature"
        private const val GEO_CHANNEL_ID = "mishwari_geo_alerts"

        fun capture(context: Context): GeoEnvironmentSignature {
            fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            val manager = context.getSystemService(NotificationManager::class.java)
            val channelImportance = if (Build.VERSION.SDK_INT >= 26) {
                manager?.getNotificationChannel(GEO_CHANNEL_ID)?.importance ?: -1
            } else -1
            val locationEnabled = runCatching {
                val locationManager = context.getSystemService(android.location.LocationManager::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locationManager?.isLocationEnabled == true
                else locationManager?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true ||
                    locationManager?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true
            }.getOrDefault(false)
            return GeoEnvironmentSignature(
                fineLocationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION),
                coarseLocationGranted = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
                backgroundLocationGranted = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                notificationPermissionGranted = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
                appNotificationsEnabled = Build.VERSION.SDK_INT < 24 || manager?.areNotificationsEnabled() != false,
                geoChannelImportance = channelImportance,
                locationEnabled = locationEnabled,
            )
        }

        fun updateAndDetectChange(context: Context): Boolean {
            val signature = capture(context).serialize()
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val changed = preferences.getString(LAST_SIGNATURE, null) != signature
            if (changed) preferences.edit().putString(LAST_SIGNATURE, signature).commit()
            return changed
        }
    }
}

internal fun notificationSettingsIntent(context: Context, channelClosed: Boolean): Intent =
    if (Build.VERSION.SDK_INT >= 26 && channelClosed) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, "mishwari_geo_alerts")
    } else if (Build.VERSION.SDK_INT >= 26) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }

class MainActivity : ComponentActivity() {
    private companion object {
        const val STATE_INCOMING_DRAFT = "incoming_draft"
        const val GEO_CHANNEL_ID = "mishwari_geo_alerts"
    }
    private val model: MishwariViewModel by viewModels()
    private var permissionRevision by mutableIntStateOf(0)
    private var selectedPlace by mutableStateOf<String?>(null)
    private var incomingDraft by mutableStateOf<String?>(null)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRevision++
        scheduleGeoForEnvironmentChange()
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(model::export) }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importBackup) }
    private val importDirectory = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importDirectory) }
    private val photo = registerForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) model.importPhoto(it) else model.pendingPhoto = null }
    private val voiceInput = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleInputResult(InputIntents.readSpeechResult(result.resultCode, result.data))
    }
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        val arabic = Locale.forLanguageTag("ar")
        config.setLocale(arabic); config.setLayoutDirection(arabic)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedPlace = intent?.getStringExtra("place_id")
        if (savedInstanceState != null) {
            // Restored null is meaningful: the original share may already have been consumed.
            incomingDraft = savedInstanceState.getString(STATE_INCOMING_DRAFT)
        } else {
            handleIncomingIntent(intent)
        }
        setContent {
            MishwariRoot(model, permissionRevision, selectedPlace, { selectedPlace = null },
                requestLocation = { permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                requestNotifications = ::requestNotificationAccess,
                openAppSettings = ::openAppSettings,
                openLocationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                exportBackup = { createBackup.launch("mishwari-backup-${java.time.LocalDate.now()}.json") },
                importBackup = { importBackup.launch(arrayOf("application/json", "text/plain")) },
                importDirectory = { importDirectory.launch(arrayOf("application/json", "text/plain")) },
                selectPhoto = { callback -> model.pendingPhoto = callback; photo.launch("image/*") },
                incomingDraft = incomingDraft,
                consumeIncomingDraft = { incomingDraft = null },
                requestVoice = ::requestVoiceInput)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_INCOMING_DRAFT, incomingDraft)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        permissionRevision++
        scheduleGeoForEnvironmentChange()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); selectedPlace = intent.getStringExtra("place_id")
        handleIncomingIntent(intent)
    }
    private fun handleIncomingIntent(source: Intent?) {
        if (source?.action != Intent.ACTION_SEND) return
        handleInputResult(InputIntents.readSharedText(source))
    }
    private fun handleInputResult(result: InputIntents.Result) {
        val offered = InputIntents.offerDraft(incomingDraft, result)
        if (offered.accepted) incomingDraft = offered.text
        offered.message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    }
    private fun requestVoiceInput() {
        val speechIntent = InputIntents.createArabicSpeechIntent()
        if (packageManager.resolveActivity(speechIntent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            Toast.makeText(this, "لا تتوفر خدمة إدخال صوتي على هذا الجهاز", Toast.LENGTH_LONG).show()
            return
        }
        try {
            voiceInput.launch(speechIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "تعذر فتح خدمة الإدخال الصوتي", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "لا تسمح إعدادات الجهاز بتشغيل خدمة الإدخال الصوتي", Toast.LENGTH_LONG).show()
        }
    }
    private fun scheduleGeoForEnvironmentChange() {
        val changed = GeoEnvironmentSignature.updateAndDetectChange(this)
        GeoScheduler.schedule(this, force = changed, rearmSelected = changed)
    }
    private fun requestNotificationAccess() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager?.getNotificationChannel(GEO_CHANNEL_ID) == null) {
            manager?.createNotificationChannel(NotificationChannel(GEO_CHANNEL_ID, "تنبيهات الأماكن", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val channelClosed = Build.VERSION.SDK_INT >= 26 &&
            manager?.getNotificationChannel(GEO_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        val appNotificationsClosed = Build.VERSION.SDK_INT >= 24 && manager?.areNotificationsEnabled() == false
        if (Build.VERSION.SDK_INT < 33 || channelClosed || appNotificationsClosed) {
            openNotificationSettings(channelClosed)
        }
    }
    private fun openNotificationSettings(channelClosed: Boolean) {
        try {
            startActivity(notificationSettingsIntent(this, channelClosed))
        } catch (_: ActivityNotFoundException) {
            openAppSettings()
        }
    }
    private fun openAppSettings() { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
}
