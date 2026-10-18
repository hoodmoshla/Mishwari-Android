package ye.mishwari

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import ye.mishwari.geo.GeoScheduler
import ye.mishwari.ui.MishwariRoot
import java.util.Locale

class MishwariApp : Application() {
    override fun onCreate() { super.onCreate(); GeoScheduler.schedule(this, force = true) }
}

class MainActivity : ComponentActivity() {
    private val model: MishwariViewModel by viewModels()
    private var permissionRevision by mutableIntStateOf(0)
    private var selectedPlace by mutableStateOf<String?>(null)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionRevision++; GeoScheduler.schedule(this) }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(model::export) }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importBackup) }
    private val importDirectory = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::importDirectory) }
    private val photo = registerForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) model.importPhoto(it) else model.pendingPhoto = null }
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        val arabic = Locale.forLanguageTag("ar")
        config.setLocale(arabic); config.setLayoutDirection(arabic)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedPlace = intent?.getStringExtra("place_id")
        setContent {
            MishwariRoot(model, permissionRevision, selectedPlace, { selectedPlace = null },
                requestLocation = { permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                requestNotifications = { if (Build.VERSION.SDK_INT >= 33) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else openAppSettings() },
                openAppSettings = ::openAppSettings,
                openLocationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                exportBackup = { createBackup.launch("mishwari-backup-${java.time.LocalDate.now()}.json") },
                importBackup = { importBackup.launch(arrayOf("application/json", "text/plain")) },
                importDirectory = { importDirectory.launch(arrayOf("application/json", "text/plain")) },
                selectPhoto = { callback -> model.pendingPhoto = callback; photo.launch("image/*") })
        }
    }
    override fun onResume() { super.onResume(); permissionRevision++; GeoScheduler.schedule(this) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); selectedPlace = intent.getStringExtra("place_id") }
    private fun openAppSettings() { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
}
