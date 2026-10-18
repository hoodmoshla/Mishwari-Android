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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ye.mishwari.data.AppSetting
import ye.mishwari.data.Errand
import ye.mishwari.data.MishwariDatabase
import ye.mishwari.data.Place
import ye.mishwari.data.Repository
import ye.mishwari.geo.GeoFenceIdentity
import ye.mishwari.geo.GeoFenceTransport
import ye.mishwari.geo.GeoFenceTransportException
import ye.mishwari.geo.GeoFenceTransportProvider
import ye.mishwari.geo.GeoPolicy
import ye.mishwari.geo.GeoWorker

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GeoWorkerRegistrationCycleRobolectricTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val dao get() = MishwariDatabase.get(app).dao()
    private lateinit var transport: RecordingGeoFenceTransport

    @Before fun prepare() = runBlocking {
        dao.allErrands().forEach { dao.deleteErrand(it.id) }
        dao.allPlaces().forEach { dao.deletePlace(it.id) }
        dao.putSetting(AppSetting(GeoWorker.TRAVEL_PLACE_SETTING, ""))
        dao.putSetting(AppSetting(GeoWorker.GEO_FINGERPRINT_SETTING, ""))
        dao.putSetting(AppSetting(GeoWorker.GEO_STATUS_SETTING, ""))
        app.getSharedPreferences("mishwari_geo_alert_policy", 0).edit().clear().commit()
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        transport = RecordingGeoFenceTransport()
        GeoFenceTransportProvider.setForTests({ transport }, ConnectionResult.SUCCESS)
    }

    @After fun cleanHooks() {
        GeoFenceTransportProvider.setForTests(null)
    }

    @Test fun addsThenSkipsStableFingerprintUpdatesGeometryAndHonorsForce() = runBlocking {
        val place = place("market")
        addErrand(place.id)

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(1, transport.removeCalls)
        assertEquals(1, transport.addCalls)
        val originalRequestId = transport.registered.single()
        val firstFingerprint = dao.getSetting(GeoWorker.GEO_FINGERPRINT_SETTING)
        assertEquals("active:1", dao.getSetting(GeoWorker.GEO_STATUS_SETTING))
        assertTrue(originalRequestId.length <= 100)

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals("unchanged fingerprint avoids needless transport calls", 1, transport.removeCalls)
        assertEquals(1, transport.addCalls)

        dao.upsertPlace(place.copy(latitude = 15.001))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        val movedRequestId = transport.registered.single()
        assertNotEquals(originalRequestId, movedRequestId)
        assertNotEquals(firstFingerprint, dao.getSetting(GeoWorker.GEO_FINGERPRINT_SETTING))
        assertEquals(2, transport.removeCalls)
        assertEquals(2, transport.addCalls)

        assertEquals(ListenableWorker.Result.success(), worker(force = true).doWork())
        assertEquals(3, transport.removeCalls)
        assertEquals(3, transport.addCalls)
    }

    @Test fun emptySelectionRemovesOldFencesWithoutCallingAdd() = runBlocking {
        val selected = place("market")
        addErrand(selected.id)
        worker().doWork()
        assertEquals(setOf(GeoFenceIdentity.requestId(candidate(selected))), transport.registered)

        dao.upsertPlace(selected.copy(monitored = false))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertTrue(transport.registered.isEmpty())
        assertEquals(2, transport.removeCalls)
        assertEquals(1, transport.addCalls)
        assertEquals("active:0", dao.getSetting(GeoWorker.GEO_STATUS_SETTING))
        assertNotEquals("", dao.getSetting(GeoWorker.GEO_FINGERPRINT_SETTING))
    }

    @Test fun missingPermissionRemovesExistingRegistrationAndReportsPermission() = runBlocking {
        val selected = place("market")
        addErrand(selected.id)
        worker().doWork()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        assertEquals(ListenableWorker.Result.success(), worker(force = true).doWork())
        assertTrue(transport.registered.isEmpty())
        assertEquals(2, transport.removeCalls)
        assertEquals(1, transport.addCalls)
        assertEquals("permission_required:fine_location", dao.getSetting(GeoWorker.GEO_STATUS_SETTING))
        assertEquals("", dao.getSetting(GeoWorker.GEO_FINGERPRINT_SETTING))
    }

    @Test fun failedGoogleAddReportsActualStatusCodeAndLeavesFingerprintInvalidated() = runBlocking {
        val selected = place("market")
        addErrand(selected.id)
        transport.failNextAdd = GeoFenceTransportException(1001, "GEOFENCE_TOO_MANY_GEOFENCES", IllegalStateException("simulated"))

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals("error:google:1001:GEOFENCE_TOO_MANY_GEOFENCES", dao.getSetting(GeoWorker.GEO_STATUS_SETTING))
        assertEquals("", dao.getSetting(GeoWorker.GEO_FINGERPRINT_SETTING))
        assertTrue(transport.registered.isEmpty())

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(2, transport.addCalls)
        assertEquals("active:1", dao.getSetting(GeoWorker.GEO_STATUS_SETTING))
    }

    @Test fun failedGoogleRemoveReportsStatusAndDoesNotAttemptAdd() = runBlocking {
        val selected = place("market")
        addErrand(selected.id)
        transport.failNextRemove = GeoFenceTransportException(1000, "GEOFENCE_NOT_AVAILABLE", IllegalStateException("simulated"))

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals("error:google:1000:GEOFENCE_NOT_AVAILABLE", dao.getSetting(GeoWorker.GEO_STATUS_SETTING))
        assertEquals(1, transport.removeCalls)
        assertEquals(0, transport.addCalls)
        assertEquals("", dao.getSetting(GeoWorker.GEO_FINGERPRINT_SETTING))
    }

    @Test fun importingBackupIntoWorkersDatabaseDisablesImportedMonitoringAndReconcileRemovesIt() = runBlocking {
        val imported = place("backup-place")
        addErrand(imported.id)
        worker().doWork()
        assertEquals(1, transport.registered.size)

        val repository = Repository(MishwariDatabase.get(app))
        val backup = repository.exportBackup(app)
        repository.importBackup(app, backup)
        assertFalse(dao.allPlaces().single { it.id == imported.id }.monitored)

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertTrue("unmonitored imported place must be removed", transport.registered.isEmpty())
        assertEquals(2, transport.removeCalls)
        assertEquals(1, transport.addCalls)
        assertEquals("active:0", dao.getSetting(GeoWorker.GEO_STATUS_SETTING))
    }

    @Test fun finishingTripFallsBackToMonitoredPlacesInsteadOfLeavingEverythingRemoved() = runBlocking {
        val destination = place("trip-destination", monitored = false)
        val regular = place("regular-place", latitude = 16.0, monitored = true)
        addErrand(destination.id)
        addErrand(regular.id)
        dao.putSetting(AppSetting(GeoWorker.TRAVEL_PLACE_SETTING, destination.id))

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(setOf(GeoFenceIdentity.requestId(candidate(destination))), transport.registered)

        // Ending a trip clears the trip selector; the user's independently monitored places remain selected.
        dao.putSetting(AppSetting(GeoWorker.TRAVEL_PLACE_SETTING, ""))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(setOf(GeoFenceIdentity.requestId(candidate(regular))), transport.registered)
        assertEquals(2, transport.removeCalls)
        assertEquals(2, transport.addCalls)
        assertEquals("active:1", dao.getSetting(GeoWorker.GEO_STATUS_SETTING))
    }

    @Test fun completingLastErrandRemovesItsFenceWithoutDeletingThePlace() = runBlocking {
        val market = place("completed-market")
        addErrand(market.id)
        worker().doWork()
        val task = dao.allErrands().single()
        Repository(MishwariDatabase.get(app)).saveErrand(task.copy(status = "مكتمل"))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertTrue(transport.registered.isEmpty())
        assertEquals(market, dao.allPlaces().single())
        assertEquals("مكتمل", dao.allErrands().single().status)
    }

    @Test fun repeatedRegistrationFailureStopsThisWorkAfterTheRetryBudget() = runBlocking {
        val market = place("failing-market")
        addErrand(market.id)
        transport.failNextAdd = GeoFenceTransportException(1000, "GEOFENCE_NOT_AVAILABLE", IllegalStateException("simulated"))
        val exhaustedWorker = TestListenableWorkerBuilder<GeoWorker>(app).setRunAttemptCount(3).build()
        assertEquals(ListenableWorker.Result.failure(), exhaustedWorker.doWork())
        assertEquals("", dao.getSetting(GeoWorker.GEO_FINGERPRINT_SETTING))
        assertEquals("error:google:1000:GEOFENCE_NOT_AVAILABLE", dao.getSetting(GeoWorker.GEO_STATUS_SETTING))
    }

    @Test fun productionTransportRejectsMissingPermissionBeforeCallingGoogle() = runBlocking {
        val market = place("guarded-market")
        GeoFenceTransportProvider.setForTests(null)
        val productionTransport = GeoFenceTransportProvider.create(app)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        try {
            productionTransport.addAll(listOf(candidate(market)))
            fail("The production API boundary must reject missing precise location")
        } catch (_: SecurityException) { }
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        try {
            productionTransport.addAll(listOf(candidate(market)))
            fail("The production API boundary must reject missing background location")
        } catch (_: SecurityException) { }
    }

    @Test fun longArabicPlaceIdStillProducesRequestIdWithinGoogleLimit() {
        val id = "م".repeat(128)
        val requestId = GeoFenceIdentity.requestId(id, 15.0, 44.0, 500f)
        assertTrue("requestId length=${requestId.length}", requestId.length <= 100)
        assertTrue(requestId.startsWith("mwgeo:v1:"))
    }

    private suspend fun place(
        id: String,
        latitude: Double = 15.0,
        monitored: Boolean = true,
    ): Place = Place(
        id = id,
        name = id,
        latitude = latitude,
        longitude = 44.0,
        radiusMeters = 500f,
        monitored = monitored,
    ).also { dao.upsertPlace(it) }

    private suspend fun addErrand(placeId: String) {
        dao.upsertErrand(Errand(id = "task-$placeId", title = "مهمة", placeId = placeId))
    }

    private fun candidate(place: Place) = GeoPolicy.Candidate(
        id = place.id,
        parentId = place.parentId,
        latitude = place.latitude,
        longitude = place.longitude,
        radiusMeters = place.radiusMeters,
        monitored = place.monitored,
        activeTaskCount = 1,
    )

    private fun worker(force: Boolean = false) = TestListenableWorkerBuilder<GeoWorker>(app)
        .setInputData(androidx.work.workDataOf(GeoWorker.INPUT_FORCE_RECONCILE to force))
        .build()

    private class RecordingGeoFenceTransport : GeoFenceTransport {
        var removeCalls = 0
        var addCalls = 0
        var failNextRemove: GeoFenceTransportException? = null
        var failNextAdd: GeoFenceTransportException? = null
        val registered = linkedSetOf<String>()

        override suspend fun removeAll() {
            removeCalls++
            failNextRemove?.let { failNextRemove = null; throw it }
            registered.clear()
        }

        override suspend fun addAll(places: List<GeoPolicy.Candidate>) {
            addCalls++
            failNextAdd?.let { failNextAdd = null; throw it }
            registered.addAll(places.map(GeoFenceIdentity::requestId))
        }
    }
}
