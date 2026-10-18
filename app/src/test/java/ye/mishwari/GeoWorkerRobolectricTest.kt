package ye.mishwari

import android.Manifest
import android.app.Application
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.android.gms.common.ConnectionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ye.mishwari.data.AppSetting
import ye.mishwari.data.MishwariDatabase
import ye.mishwari.geo.GeoFenceTransportProvider
import ye.mishwari.geo.GeoWorker

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GeoWorkerRobolectricTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun isolateDatabaseAndServices() = runBlocking {
        val dao = MishwariDatabase.get(app).dao()
        dao.allErrands().forEach { dao.deleteErrand(it.id) }
        dao.allPlaces().forEach { dao.deletePlace(it.id) }
        dao.putSetting(AppSetting(GeoWorker.GEO_FINGERPRINT_SETTING, ""))
        dao.putSetting(AppSetting(GeoWorker.GEO_STATUS_SETTING, ""))
        GeoFenceTransportProvider.setForTests(null, ConnectionResult.SERVICE_MISSING)
    }

    @After fun resetServices() {
        GeoFenceTransportProvider.setForTests(null)
    }

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
