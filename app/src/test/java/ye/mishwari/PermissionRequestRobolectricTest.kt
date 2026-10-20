package ye.mishwari

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Reflection-level tests for MainActivity's private permission callbacks. */
private object PermissionRequestTestSupport {
    private val controllers = mutableListOf<org.robolectric.android.controller.ActivityController<MainActivity>>()

    fun cleanup() {
        controllers.asReversed().forEach { it.pause().stop().destroy() }
        controllers.clear()
    }
    val app: Application
        get() = ApplicationProvider.getApplicationContext()

    fun reset() {
        cleanup()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        app.getSharedPreferences(PermissionState.ASKED_PREFERENCES, 0).edit().clear().commit()
        app.getSharedPreferences("mishwari_geo_environment", 0).edit().clear().commit()
        shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        if (Build.VERSION.SDK_INT >= 26) {
            app.getSystemService(NotificationManager::class.java)
                .deleteNotificationChannel(PermissionState.GEO_CHANNEL_ID)
        }
    }

    fun activity(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controllers += controller
        return controller.get()
    }

    fun invoke(activity: MainActivity, callbackName: String) {
        MainActivity::class.java.getDeclaredMethod(callbackName).apply { isAccessible = true }
            .invoke(activity)
    }

    fun requestedPermissions(activity: MainActivity): Array<String>? =
        shadowOf(activity).lastRequestedPermission?.requestedPermissions

    fun noActivityStarted(activity: MainActivity) {
        val started = shadowOf(activity).peekNextStartedActivity()
        // AndroidX implements runtime requests via the permission-controller activity.
        // That is the expected prompt, not an unsolicited redirect to app settings.
        if (started != null) assertEquals(
            "callback must not open a settings activity",
            "android.content.pm.action.REQUEST_PERMISSIONS",
            started.action,
        )
    }
}

abstract class PermissionRequestTestBase {
    @After fun destroyPermissionTestActivities() = PermissionRequestTestSupport.cleanup()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PermissionRequestRobolectricTest : PermissionRequestTestBase() {
    @Test
    fun grantedLocationDoesNotRequestAgainOrOpenSettings() {
        PermissionRequestTestSupport.reset()
        shadowOf(PermissionRequestTestSupport.app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val activity = PermissionRequestTestSupport.activity()

        PermissionRequestTestSupport.invoke(activity, "requestLocationAccess")

        assertNull(shadowOf(activity).lastRequestedPermission)
        PermissionRequestTestSupport.noActivityStarted(activity)
    }

    @Test
    fun permanentlyDeniedLocationOpensSettingsOnlyAfterExplicitRequest() {
        PermissionRequestTestSupport.reset()
        PermissionState.markAsked(PermissionRequestTestSupport.app, Manifest.permission.ACCESS_FINE_LOCATION)
        val activity = PermissionRequestTestSupport.activity()
        assertNull("resume must not redirect to settings", shadowOf(activity).lastRequestedPermission)
        PermissionRequestTestSupport.noActivityStarted(activity)

        // Resuming again refreshes the permission snapshot but is not an implicit request.
        PermissionRequestTestSupport.invoke(activity, "onResume")
        assertNull(shadowOf(activity).lastRequestedPermission)
        PermissionRequestTestSupport.noActivityStarted(activity)

        PermissionRequestTestSupport.invoke(activity, "requestLocationAccess")

        assertNull(shadowOf(activity).lastRequestedPermission)
        val settingsIntent = shadowOf(activity).getNextStartedActivity()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settingsIntent.action)
        assertEquals(Uri.parse("package:${activity.packageName}"), settingsIntent.data)
    }

    @Test
    fun grantedNotificationsAndEnabledChannelDoNotOpenSettings() {
        PermissionRequestTestSupport.reset()
        shadowOf(PermissionRequestTestSupport.app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(PermissionRequestTestSupport.app.getSystemService(NotificationManager::class.java))
            .setNotificationsEnabled(true)
        val activity = PermissionRequestTestSupport.activity()

        PermissionRequestTestSupport.invoke(activity, "requestNotificationAccess")

        assertNull(shadowOf(activity).lastRequestedPermission)
        PermissionRequestTestSupport.noActivityStarted(activity)
        val channel = activity.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(PermissionState.GEO_CHANNEL_ID)
        assertNotNull("callback creates the channel when it is missing", channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel!!.importance)
    }

    @Test
    fun locationCallbackCapturesCurrentPermissionStateAfterRevocation() {
        PermissionRequestTestSupport.reset()
        shadowOf(PermissionRequestTestSupport.app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val activity = PermissionRequestTestSupport.activity()
        shadowOf(PermissionRequestTestSupport.app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        // The callback must inspect current platform state rather than the granted snapshot from onCreate.
        PermissionRequestTestSupport.invoke(activity, "requestLocationAccess")

        assertArrayEquals(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            PermissionRequestTestSupport.requestedPermissions(activity),
        )
        PermissionRequestTestSupport.noActivityStarted(activity)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class PermissionRequestSdk31RobolectricTest : PermissionRequestTestBase() {
    @Test
    fun upgradingFromCoarseRequestsFineAndCoarseTogether() {
        PermissionRequestTestSupport.reset()
        shadowOf(PermissionRequestTestSupport.app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val activity = PermissionRequestTestSupport.activity()

        PermissionRequestTestSupport.invoke(activity, "requestLocationAccess")

        assertArrayEquals(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            PermissionRequestTestSupport.requestedPermissions(activity),
        )
        PermissionRequestTestSupport.noActivityStarted(activity)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class PermissionRequestSdk29RobolectricTest : PermissionRequestTestBase() {
    @Test
    fun backgroundPermissionUsesRuntimeRequestWhenForegroundIsGranted() {
        PermissionRequestTestSupport.reset()
        shadowOf(PermissionRequestTestSupport.app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val activity = PermissionRequestTestSupport.activity()

        PermissionRequestTestSupport.invoke(activity, "requestBackgroundLocation")

        assertArrayEquals(
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            PermissionRequestTestSupport.requestedPermissions(activity),
        )
        PermissionRequestTestSupport.noActivityStarted(activity)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PermissionRequestSdk30RobolectricTest : PermissionRequestTestBase() {
    @Test
    fun backgroundRequestRefusesWithoutForegroundThenOpensSettingsWithForeground() {
        PermissionRequestTestSupport.reset()
        val activity = PermissionRequestTestSupport.activity()

        PermissionRequestTestSupport.invoke(activity, "requestBackgroundLocation")

        assertNull(shadowOf(activity).lastRequestedPermission)
        PermissionRequestTestSupport.noActivityStarted(activity)

        shadowOf(PermissionRequestTestSupport.app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        PermissionRequestTestSupport.invoke(activity, "requestBackgroundLocation")

        assertNull(shadowOf(activity).lastRequestedPermission)
        val settingsIntent = shadowOf(activity).getNextStartedActivity()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settingsIntent.action)
        assertEquals(Uri.parse("package:${activity.packageName}"), settingsIntent.data)
    }
}
