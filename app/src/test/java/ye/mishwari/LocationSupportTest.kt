package ye.mishwari

import android.Manifest
import android.location.Location
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import ye.mishwari.data.Place
import ye.mishwari.location.CurrentLocationRequest
import ye.mishwari.location.LocationCaptureFailure
import ye.mishwari.location.LocationCaptureResult
import ye.mishwari.location.checkedCurrentFix
import ye.mishwari.location.placeAlertReadiness

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocationSupportTest {
    @Test fun acceptsOnlyRecentValidFixesWithUsableAccuracy() {
        val now = 900_000_000_000L
        val good = fix(latitude = 15.37, longitude = 44.19, accuracy = 85f, elapsed = now - 8_000_000_000L)
        assertNotNull(checkedCurrentFix(good, now))
        assertNull(checkedCurrentFix(fix(15.0, 44.0, 15f, now - 31_000_000_000L), now))
        assertNull(checkedCurrentFix(fix(15.0, 44.0, 1_001f, now), now))
        assertNull(checkedCurrentFix(fix(91.0, 44.0, 15f, now), now))
        assertNull(checkedCurrentFix(fix(15.0, 44.0, 15f, now + 1L), now))
        val withoutAccuracy = Location("gps").apply {
            latitude = 15.0
            longitude = 44.0
            elapsedRealtimeNanos = now
        }
        assertNull(checkedCurrentFix(withoutAccuracy, now))
    }

    @Test fun aOneShotCaptureRequiresForegroundPermissionAndCancellationIsSilent() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val results = mutableListOf<LocationCaptureResult>()
        val request = CurrentLocationRequest(app, results::add).start()
        assertEquals(1, results.size)
        assertEquals(LocationCaptureFailure.PERMISSION_REQUIRED, (results.single() as LocationCaptureResult.Failure).reason)
        request.cancel()
        assertEquals("a cancelled or completed request cannot call back again", 1, results.size)
    }

    @Test fun alertReadinessDistinguishesMissingCoordinatesDisabledMonitoringAndRegistration() {
        val base = Place(id = "manual", name = "مكان يدوي", latitude = 15.1, longitude = 44.2, monitored = true)
        fun readiness(place: Place = base, fine: Boolean = true, background: Boolean = true, notifications: Boolean = true, status: String? = "active:1", locationEnabled: Boolean = true) =
            placeAlertReadiness(place, fine, background, notifications, status, locationEnabled)

        val unmonitored = readiness(place = base.copy(monitored = false, latitude = null, longitude = null))
        assertFalse(unmonitored.configurationReady)
        assertTrue(unmonitored.registrationMessage.contains("المراقبة متوقفة"))
        assertTrue(unmonitored.registrationMessage.contains("لا توجد إحداثيات"))
        assertTrue(readiness(fine = false).registrationMessage.contains("الموقع الدقيق"))
        assertTrue(readiness(background = false).registrationMessage.contains("الموقع الدائم"))
        assertTrue(readiness(notifications = false).registrationMessage.contains("الإشعارات"))
        assertTrue(readiness(locationEnabled = false).registrationMessage.contains("خدمات الموقع متوقفة"))
        assertFalse(readiness(status = null).configurationReady)
        assertFalse(readiness(status = "active:0").configurationReady)
        val aggregate = readiness(status = "active:3")
        assertTrue(aggregate.configurationReady)
        assertFalse(aggregate.registeredForThisPlace)
        assertTrue(aggregate.registrationMessage.contains("لا يؤكد ذلك أن هذا المكان"))
    }

    private fun fix(latitude: Double, longitude: Double, accuracy: Float, elapsed: Long) = Location("gps").apply {
        this.latitude = latitude
        this.longitude = longitude
        this.accuracy = accuracy
        elapsedRealtimeNanos = elapsed
    }
}
