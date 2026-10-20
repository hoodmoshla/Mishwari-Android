package ye.mishwari

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PermissionStateRobolectricTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)

    @After fun clearAskedPreferences() {
        app.getSharedPreferences(PermissionState.ASKED_PREFERENCES, 0).edit().clear().commit()
    }

    @Test fun permissionsMoveFromUnrequestedToGrantedDeniedAndRevoked() {
        assertEquals(PermissionStatus.NOT_REQUESTED, PermissionState.permissionStatus(app, Manifest.permission.ACCESS_FINE_LOCATION))

        PermissionState.markAsked(app, Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(
            PermissionStatus.DENIED_CAN_ASK,
            PermissionState.permissionStatus(app, Manifest.permission.ACCESS_FINE_LOCATION) { true },
        )
        assertEquals(
            PermissionStatus.PERMANENTLY_DENIED,
            PermissionState.permissionStatus(app, Manifest.permission.ACCESS_FINE_LOCATION) { false },
        )

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(PermissionStatus.GRANTED, PermissionState.permissionStatus(app, Manifest.permission.ACCESS_FINE_LOCATION))
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(
            "revoking a previously granted permission is not mistaken for a first request",
            PermissionStatus.PERMANENTLY_DENIED,
            PermissionState.permissionStatus(app, Manifest.permission.ACCESS_FINE_LOCATION) { false },
        )
    }

    @Test fun snapshotsCaptureFineCoarseBackgroundNotificationAndChannelStates() {
        val initial = PermissionState.capture(app)
        assertEquals(PermissionStatus.NOT_REQUESTED, initial.fineLocation)
        assertEquals(PermissionStatus.NOT_REQUESTED, initial.coarseLocation)
        assertEquals(PermissionStatus.NOT_REQUESTED, initial.backgroundLocation)
        assertEquals(PermissionStatus.NOT_REQUESTED, initial.notifications)
        assertFalse(initial.foregroundLocationGranted)
        assertEquals(AlertRequirement.FOREGROUND_LOCATION, initial.nextMissingAlertRequirement)

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        manager.createNotificationChannel(
            NotificationChannel(PermissionState.GEO_CHANNEL_ID, "test", NotificationManager.IMPORTANCE_NONE),
        )
        val changed = PermissionState.capture(app)
        assertEquals(PermissionStatus.GRANTED, changed.coarseLocation)
        assertTrue(changed.foregroundLocationGranted)
        assertFalse(changed.geoChannelEnabled)
    }

    @Test fun sdk30BackgroundSettingHandoffIsDistinctFromRuntimePermission() {
        PermissionState.markAsked(app, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val state = PermissionState.capture(app)
        assertEquals(PermissionStatus.SETTINGS_REQUIRED, state.backgroundLocation)
        assertEquals(AlertRequirement.FOREGROUND_LOCATION, state.nextMissingAlertRequirement)

        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        assertEquals(PermissionStatus.GRANTED, PermissionState.capture(app).backgroundLocation)
    }

    @Test fun notificationRuntimePermissionIsTrackedOnlyFromSdk33() {
        assertEquals(PermissionStatus.NOT_REQUESTED, PermissionState.capture(app).notifications)
        PermissionState.markAsked(app, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(PermissionStatus.GRANTED, PermissionState.capture(app).notifications)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(PermissionStatus.PERMANENTLY_DENIED, PermissionState.capture(app).notifications)
    }

}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class)
class PermissionStateSdk26RobolectricTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun sdk26HasNoBackgroundOrNotificationRuntimePermissions() {
        val state = PermissionState.capture(app)
        assertEquals(PermissionStatus.NOT_APPLICABLE, state.backgroundLocation)
        assertEquals(PermissionStatus.NOT_APPLICABLE, state.notifications)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PermissionStateSdk28RobolectricTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun sdk28HasNoBackgroundOrNotificationRuntimePermissions() {
        val state = PermissionState.capture(app)
        assertEquals(PermissionStatus.NOT_APPLICABLE, state.backgroundLocation)
        assertEquals(PermissionStatus.NOT_APPLICABLE, state.notifications)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class PermissionStateSdk29RobolectricTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun sdk29BackgroundPermissionIsRuntimeButNotificationsAreNot() {
        val state = PermissionState.capture(app)
        assertEquals(PermissionStatus.NOT_REQUESTED, state.backgroundLocation)
        assertEquals(PermissionStatus.NOT_APPLICABLE, state.notifications)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PermissionStateSdk30RobolectricTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun sdk30BackgroundPermissionUsesSettingsWhenPreviouslyRequested() {
        PermissionState.markAsked(app, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertEquals(PermissionStatus.SETTINGS_REQUIRED, PermissionState.capture(app).backgroundLocation)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PermissionStateSdk33RobolectricTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun sdk33HasNotificationRuntimePermission() {
        assertEquals(PermissionStatus.NOT_REQUESTED, PermissionState.capture(app).notifications)
    }
}
