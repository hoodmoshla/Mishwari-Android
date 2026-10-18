package ye.mishwari

import android.Manifest
import android.app.Application
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ye.mishwari.data.MishwariDatabase
import ye.mishwari.geo.GeoWorker

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GeoWorkerRobolectricTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    @Test fun deniedPreciseLocationIsReportedWithoutCrashing() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val worker = TestListenableWorkerBuilder<GeoWorker>(app).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals("permission_required:fine_location", MishwariDatabase.get(app).dao().getSetting("geo_status"))
    }
    @Test fun missingBackgroundPermissionIsReportedSeparately() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val worker = TestListenableWorkerBuilder<GeoWorker>(app).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals("permission_required:background_location", MishwariDatabase.get(app).dao().getSetting("geo_status"))
    }
    @Test fun switchedOffLocationIsReportedBeforeRegisteringGoogleGeofences() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        val worker = TestListenableWorkerBuilder<GeoWorker>(app).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals("location_disabled", MishwariDatabase.get(app).dao().getSetting("geo_status"))
        assertEquals("", MishwariDatabase.get(app).dao().getSetting("geo_fence_fingerprint"))
    }
}
