package ye.mishwari

import android.app.Application
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.Geofence
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ye.mishwari.geo.AlertPolicy
import ye.mishwari.geo.GeoEventWork
import ye.mishwari.geo.GeoFenceIdentity
import ye.mishwari.geo.GeoPolicy
import ye.mishwari.geo.GeoReceiver
import ye.mishwari.geo.GeoEventWorker
import ye.mishwari.geo.runGeoEventDelivery

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GeoEventDeliveryRobolectricTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun initializeWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @Test fun eventEnqueuePersistsAUniqueWorkRecordAndPayload() = runBlocking {
        GeoEventWork.awaitEnqueue(
            GeoEventWork.enqueue(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("persisted-fence")),
        )
        val work = WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(GeoEventWork.UNIQUE_WORK_NAME)
            .get()
        assertTrue(work.any {
            val spec = (WorkManager.getInstance(app) as WorkManagerImpl).workDatabase.workSpecDao().getWorkSpec(it.id.toString())
            spec != null && GeoEventWork.decodeRequestIds(spec.input)?.contains("persisted-fence") == true
        })
    }

    @Test fun workRequestRetainsTransitionAndDeduplicatesRequestIds() {
        val request = GeoEventWork.request(
            Geofence.GEOFENCE_TRANSITION_ENTER,
            listOf("fence-a", "fence-a", "fence-b"),
        )
        assertEquals(Geofence.GEOFENCE_TRANSITION_ENTER, request.workSpec.input.getInt(GeoEventWork.INPUT_TRANSITION, 0))
        assertEquals(listOf("fence-a", "fence-b"), GeoEventWork.decodeRequestIds(request.workSpec.input))
    }

    @Test fun terminallyInvalidEventIsDiagnosedWithoutFailingQueuedSuccessors() = runBlocking {
        val worker = TestListenableWorkerBuilder.from(app, GeoEventWorker::class.java)
            .setInputData(Data.EMPTY)
            .build()
        val result = worker.doWork()
        assertTrue("terminal failure must not propagate to an already-queued EXIT", result is ListenableWorker.Result.Success)
        assertTrue((result as ListenableWorker.Result.Success).outputData.getBoolean("event_delivery_dropped", false))
    }

    @Test fun maximumHundredBoundedIdsFitUnderWorkManagerDataLimit() {
        val ids = (0 until GeoEventWork.MAX_REQUEST_IDS).map { index ->
            GeoFenceIdentity.requestId(GeoPolicy.Candidate("place-$index", null, 15.0 + index / 1000.0, 44.0))
        }
        val input = GeoEventWork.request(Geofence.GEOFENCE_TRANSITION_ENTER, ids, receivedElapsedMillis = 1L).workSpec.input
        assertEquals(ids, GeoEventWork.decodeRequestIds(input))
        assertTrue("serialized input must be below WorkManager's 10 KiB limit", input.toByteArray().size <= 10 * 1024)
    }

    @Test fun shortHandoffCompletesAndLongHandoffTimesOut() = runBlocking {
        assertEquals(8_000L, GeoReceiver.EVENT_TIMEOUT_MILLIS)
        assertEquals("durably-enqueued", GeoEventWork.withHandoffTimeout(100) { "durably-enqueued" })
        var timedOut = false
        try {
            GeoEventWork.withHandoffTimeout(20) {
                delay(200)
                "too-late"
            }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
        }
        assertTrue("goAsync hand-off must be bounded by its timeout", timedOut)
    }

    @Test fun workerDeliveryPassesValidatedEventAndReturnsSuccess() = runBlocking {
        val input = GeoEventWork.request(Geofence.GEOFENCE_TRANSITION_EXIT, listOf("fence-a")).workSpec.input
        var deliveredTransition = 0
        var deliveredIds = emptyList<String>()
        val result = runGeoEventDelivery(input) { transition, ids ->
            deliveredTransition = transition
            deliveredIds = ids
        }
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(Geofence.GEOFENCE_TRANSITION_EXIT, deliveredTransition)
        assertEquals(listOf("fence-a"), deliveredIds)
    }

    @Test fun workerRequestsRetryAfterTransientProcessingFailure() = runBlocking {
        val input = GeoEventWork.request(Geofence.GEOFENCE_TRANSITION_ENTER, listOf("fence-a")).workSpec.input
        var attempts = 0
        val first = runGeoEventDelivery(input) { _, _ ->
            attempts++
            error("temporary database failure")
        }
        assertTrue(first is ListenableWorker.Result.Retry)
        val second = runGeoEventDelivery(input) { _, _ -> attempts++ }
        assertTrue(second is ListenableWorker.Result.Success)
        assertEquals("the same durable payload is retried until processing succeeds", 2, attempts)

        val final = runGeoEventDelivery(input, runAttemptCount = GeoEventWork.MAX_RETRY_ATTEMPTS - 1) { _, _ ->
            error("still failing at retry limit")
        }
        assertTrue("retry exhaustion must be terminal so queued transitions can proceed", final is ListenableWorker.Result.Failure)
    }

    @Test fun staleEnterIsDroppedButOldExitStillRearmsAndAgeIsBounded() = runBlocking {
        val receivedAt = 10_000L
        val staleEnter = GeoEventWork.request(
            Geofence.GEOFENCE_TRANSITION_ENTER,
            listOf("fence-a"),
            receivedElapsedMillis = receivedAt,
        ).workSpec.input
        var processed = false
        val expired = runGeoEventDelivery(
            staleEnter,
            nowElapsedMillis = receivedAt + AlertPolicy.MIN_COOLDOWN_MILLIS + 1L,
        ) { _, _ -> processed = true }
        assertTrue(expired is ListenableWorker.Result.Success)
        assertFalse("expired arrival must not produce a late alert", processed)

        val oldExit = GeoEventWork.request(
            Geofence.GEOFENCE_TRANSITION_EXIT,
            listOf("fence-a"),
            receivedElapsedMillis = receivedAt,
        ).workSpec.input
        runGeoEventDelivery(oldExit, nowElapsedMillis = receivedAt + AlertPolicy.MIN_COOLDOWN_MILLIS + 1L) { _, _ ->
            processed = true
        }
        assertTrue("stale EXIT must still clear occupancy state", processed)
    }

    @Test fun repeatedEnterIsIdempotentAndOldGeometryDoesNotMatchCurrentFence() {
        val now = 1_000_000L
        val first = AlertPolicy.onEnter(AlertPolicy.State(), now, hasActiveTasks = true, notificationsAllowed = true)
        assertTrue(first.shouldNotify)
        val duplicate = AlertPolicy.onEnter(first.state, now + 1, hasActiveTasks = true, notificationsAllowed = true)
        assertFalse(duplicate.shouldNotify)
        assertEquals(first.state, duplicate.state)

        val old = GeoPolicy.Candidate("market", null, 15.0, 44.0)
        val moved = old.copy(latitude = 15.1)
        assertFalse(GeoFenceIdentity.matches(GeoFenceIdentity.requestId(old), moved))
    }

    @Test fun invalidEventPayloadIsRejectedAndCoordinateBoundsAreChecked() = runBlocking {
        val invalid = Data.Builder()
            .putInt(GeoEventWork.INPUT_TRANSITION, 123)
            .putString(GeoEventWork.INPUT_REQUEST_IDS, "invalid-compressed-payload")
            .putLong(GeoEventWork.INPUT_RECEIVED_ELAPSED_MILLIS, 1L)
            .build()
        val result = runGeoEventDelivery(invalid) { _, _ -> error("invalid event must not be delivered") }
        assertTrue(result is ListenableWorker.Result.Failure)

        assertTrue(GeoPolicy.validCoordinates(-90.0, 180.0))
        assertFalse(GeoPolicy.validCoordinates(Double.NaN, 0.0))
        assertFalse(GeoPolicy.validCoordinates(0.0, Double.POSITIVE_INFINITY))
        assertFalse(GeoPolicy.validCoordinates(91.0, 0.0))
    }
}
