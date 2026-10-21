package ye.mishwari.geo

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.Geofence
import kotlinx.coroutines.CancellationException
import ye.mishwari.data.AppSetting
import ye.mishwari.data.MishwariDatabase

/** Processes one durable geofence transition; APPEND_OR_REPLACE serializes deliveries. */
class GeoEventWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        var terminalException: Exception? = null
        val result = runGeoEventDelivery(
            inputData,
            runAttemptCount,
            SystemClock.elapsedRealtime(),
            System.currentTimeMillis(),
        ) { transition, requestIds ->
            try {
                GeoReceiver().processVersionedTransition(applicationContext, transition, requestIds)
            } catch (failure: Exception) {
                terminalException = failure
                throw failure
            }
        }
        // Use the public factory/equality contract; concrete Result subclasses are library-internal.
        if (result == Result.failure()) {
            runCatching {
                MishwariDatabase.get(applicationContext).dao().putSetting(
                    AppSetting(key = GeoWorker.GEO_STATUS_SETTING, value = "event_delivery_failed:${terminalException?.let { it::class.java.simpleName } ?: "invalid_payload"}"),
                )
            }
            runCatching { GeoScheduler.schedule(applicationContext, force = true, rearmSelected = true) }
            // Failure would propagate to already-enqueued successors, including EXITs.
            // APPEND_OR_REPLACE repairs failed prerequisites only when NEW work is enqueued.
            // Diagnose/reconcile this dropped event, then allow the ordered queue to drain.
            return Result.success(Data.Builder().putBoolean("event_delivery_dropped", true).build())
        }
        return result
    }
}

/** Shared result mapping keeps delivery validation and transient retry policy directly testable. */
internal suspend fun runGeoEventDelivery(
    input: Data,
    runAttemptCount: Int = 0,
    nowElapsedMillis: Long = SystemClock.elapsedRealtime(),
    nowWallMillis: Long = System.currentTimeMillis(),
    process: suspend (transition: Int, requestIds: List<String>) -> Unit,
): ListenableWorker.Result {
    val transition = input.getInt(GeoEventWork.INPUT_TRANSITION, 0)
    if (transition != Geofence.GEOFENCE_TRANSITION_ENTER && transition != Geofence.GEOFENCE_TRANSITION_EXIT) {
        return ListenableWorker.Result.failure()
    }
    val requestIds = GeoEventWork.decodeRequestIds(input) ?: return ListenableWorker.Result.failure()
    val receivedElapsedMillis = input.getLong(GeoEventWork.INPUT_RECEIVED_ELAPSED_MILLIS, -1L)
    val receivedWallMillis = input.getLong(GeoEventWork.INPUT_RECEIVED_WALL_MILLIS, -1L)
    if (receivedElapsedMillis < 0L || receivedWallMillis < 0L || nowElapsedMillis < 0L || nowWallMillis < 0L) {
        return ListenableWorker.Result.failure()
    }
    // Drop delayed ENTERs (including work surviving a reboot) rather than notify about stale presence;
    // process old EXITs so a stale occupancy flag cannot suppress a later visit.
    if (transition == Geofence.GEOFENCE_TRANSITION_ENTER &&
        (nowElapsedMillis < receivedElapsedMillis || nowWallMillis < receivedWallMillis ||
            nowElapsedMillis - receivedElapsedMillis > AlertPolicy.MIN_COOLDOWN_MILLIS ||
            nowWallMillis - receivedWallMillis > AlertPolicy.MIN_COOLDOWN_MILLIS)) {
        return ListenableWorker.Result.success()
    }

    return try {
        process(transition, requestIds)
        ListenableWorker.Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (runAttemptCount + 1 < GeoEventWork.MAX_RETRY_ATTEMPTS) ListenableWorker.Result.retry()
        else ListenableWorker.Result.failure()
    }
}
