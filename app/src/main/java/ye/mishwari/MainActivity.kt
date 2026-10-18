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

class MishwariApp : Application() {
    override fun onCreate() { super.onCreate(); GeoScheduler.schedule(this, force = true) }
}

class MainActivity : ComponentActivity() {
    private companion object { const val STATE_INCOMING_DRAFT = "incoming_draft" }
    private val model: MishwariViewModel by viewModels()
    private var permissionRevision by mutableIntStateOf(0)
    private var selectedPlace by mutableStateOf<String?>(null)
    private var incomingDraft by mutableStateOf<String?>(null)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionRevision++; GeoScheduler.schedule(this) }
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
                requestNotifications = { if (Build.VERSION.SDK_INT >= 33) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else openAppSettings() },
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
    override fun onResume() { super.onResume(); permissionRevision++; GeoScheduler.schedule(this) }
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
    private fun openAppSettings() { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
}
