package ye.mishwari

import android.Manifest
import android.app.Application
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ye.mishwari.geo.GeoRecoveryDispatch
import ye.mishwari.geo.GeoRecoveryReceiver
import ye.mishwari.geo.GeoWorker

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GeoRecoveryRobolectricTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun bootProviderAndPackageRecoveryUseTheirIntendedWorkerFlags() {
        val calls = mutableListOf<Pair<Boolean, Boolean>>()
        val schedule: (Boolean, Boolean) -> Unit = { force, rearm -> calls += force to rearm }

        assertTrue(GeoRecoveryDispatch.dispatch(Intent.ACTION_BOOT_COMPLETED, schedule))
        assertTrue(GeoRecoveryDispatch.dispatch(GeoRecoveryDispatch.ACTION_PROVIDERS_CHANGED, schedule))
        assertTrue(GeoRecoveryDispatch.dispatch(Intent.ACTION_MY_PACKAGE_REPLACED, schedule))
        assertFalse(GeoRecoveryDispatch.dispatch("unrelated.action", schedule))

        assertEquals(listOf(true to true, true to true, true to false), calls)
    }

    @Test fun actualBootReceiverEnqueuesForcedRearmWork() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val workManager = WorkManager.getInstance(app)
        workManager.cancelAllWork().result.get()

        GeoRecoveryReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        var workInfos = workManager.getWorkInfosForUniqueWork("mishwari-geo-reconcile").get()
        repeat(50) {
            if (workInfos.isEmpty()) {
                Thread.sleep(20)
                workInfos = workManager.getWorkInfosForUniqueWork("mishwari-geo-reconcile").get()
            }
        }
        assertFalse("receiver must enqueue its reconcile work", workInfos.isEmpty())
        val work = workInfos.last()
        val input = (workManager as androidx.work.impl.WorkManagerImpl).workDatabase
            .workSpecDao().getWorkSpec(work.id.toString())!!.input
        assertTrue(input.getBoolean(GeoWorker.INPUT_FORCE_RECONCILE, false))
        assertTrue(input.getBoolean(GeoWorker.INPUT_REARM_SELECTED, false))
        workManager.cancelAllWork().result.get()
    }

    @Test fun signatureTrackerRearmsOnlyOnAChangedEnvironment() {
        val prefs = app.getSharedPreferences("mishwari_geo_environment", 0)
        prefs.edit().clear().commit()
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )

        assertTrue(GeoEnvironmentSignature.updateAndDetectChange(app))
        assertFalse("unchanged resume must not force a rearm", GeoEnvironmentSignature.updateAndDetectChange(app))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue("notification permission change must be detected", GeoEnvironmentSignature.updateAndDetectChange(app))
        assertFalse(GeoEnvironmentSignature.updateAndDetectChange(app))

        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        assertTrue("location setting change must be detected", GeoEnvironmentSignature.updateAndDetectChange(app))
        assertFalse(GeoEnvironmentSignature.updateAndDetectChange(app))
    }

    @Test fun signatureIncludesChannelAndEveryPermissionDimension() {
        val initial = GeoEnvironmentSignature(
            fineLocationGranted = false,
            coarseLocationGranted = false,
            backgroundLocationGranted = false,
            notificationPermissionGranted = false,
            appNotificationsEnabled = true,
            geoChannelImportance = -1,
            locationEnabled = true,
        )
        val variants = listOf(
            initial.copy(fineLocationGranted = true),
            initial.copy(coarseLocationGranted = true),
            initial.copy(backgroundLocationGranted = true),
            initial.copy(notificationPermissionGranted = true),
            initial.copy(appNotificationsEnabled = false),
            initial.copy(geoChannelImportance = 0),
            initial.copy(locationEnabled = false),
        )
        variants.forEach { changed -> assertNotEquals(initial.serialize(), changed.serialize()) }
        assertEquals(initial.serialize(), initial.copy().serialize())
    }

    @Test fun notificationSettingsIntentTargetsClosedGeoChannelWhenNeeded() {
        val channelSettings = notificationSettingsIntent(app, channelClosed = true)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channelSettings.action)
        assertEquals(app.packageName, channelSettings.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals("mishwari_geo_alerts", channelSettings.getStringExtra(Settings.EXTRA_CHANNEL_ID))

        val appSettings = notificationSettingsIntent(app, channelClosed = false)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, appSettings.action)
        assertEquals(app.packageName, appSettings.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
