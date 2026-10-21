package ye.mishwari.geo

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import java.util.concurrent.ExecutionException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Durable, ordered hand-off for geofence broadcasts. */
internal object GeoEventWork {
    const val UNIQUE_WORK_NAME = "geo_transition_events"
    const val INPUT_TRANSITION = "geo_event_transition"
    const val INPUT_REQUEST_IDS = "geo_event_request_ids"
    const val INPUT_RECEIVED_ELAPSED_MILLIS = "geo_event_received_elapsed_millis"
    const val INPUT_RECEIVED_WALL_MILLIS = "geo_event_received_wall_millis"
    const val MAX_REQUEST_IDS = 100
    const val MAX_REQUEST_ID_LENGTH = 100
    const val MAX_RETRY_ATTEMPTS = 5
    const val INITIAL_BACKOFF_MILLIS = 10_000L

    /** Enqueue with APPEND_OR_REPLACE so transition order is retained without a failed predecessor blocking new events. */
    fun enqueue(context: Context, transition: Int, requestIds: List<String>): Operation {
        require(transition == com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_ENTER ||
            transition == com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_EXIT)
        val ids = requestIds.distinct()
        require(ids.isNotEmpty()) { "A geofence event must contain at least one request ID" }
        val request = request(transition, ids)
        return WorkManager.getInstance(context.applicationContext)
            .beginUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            .enqueue()
    }

    internal fun request(
        transition: Int,
        requestIds: List<String>,
        receivedElapsedMillis: Long = SystemClock.elapsedRealtime(),
        receivedWallMillis: Long = System.currentTimeMillis(),
    ): OneTimeWorkRequest {
        val ids = requestIds.distinct()
        require(ids.isNotEmpty() && ids.size <= MAX_REQUEST_IDS)
        require(ids.all { it.isNotBlank() && it.length <= MAX_REQUEST_ID_LENGTH && '\n' !in it && '\r' !in it })
        require(receivedElapsedMillis >= 0L)
        require(receivedWallMillis >= 0L)
        val data = Data.Builder()
            .putInt(INPUT_TRANSITION, transition)
            .putString(INPUT_REQUEST_IDS, encodeRequestIds(ids))
            .putLong(INPUT_RECEIVED_ELAPSED_MILLIS, receivedElapsedMillis)
            .putLong(INPUT_RECEIVED_WALL_MILLIS, receivedWallMillis)
            .build()
        return OneTimeWorkRequestBuilder<GeoEventWorker>()
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    internal fun decodeRequestIds(input: Data): List<String>? = runCatching {
        val packed = input.getString(INPUT_REQUEST_IDS) ?: return null
        val ids = GZIPInputStream(ByteArrayInputStream(Base64.decode(packed, Base64.NO_WRAP or Base64.URL_SAFE)))
            .bufferedReader(Charsets.UTF_8).useLines { lines -> lines.toList() }
        ids.takeIf { it.isNotEmpty() && it.size <= MAX_REQUEST_IDS && it.all { id -> id.isNotBlank() && id.length <= MAX_REQUEST_ID_LENGTH } }
    }.getOrNull()

    private fun encodeRequestIds(ids: List<String>): String {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).bufferedWriter(Charsets.UTF_8).use { writer ->
            ids.forEach { id -> writer.append(id).append('\n') }
        }
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    /** The receiver only remains alive while WorkManager confirms the event was persisted. */
    suspend fun <T> withHandoffTimeout(timeoutMillis: Long = GeoReceiver.EVENT_TIMEOUT_MILLIS, block: suspend () -> T): T =
        withTimeout(timeoutMillis) { block() }

    internal suspend fun awaitEnqueue(operation: Operation) {
        val future = operation.result
        suspendCancellableCoroutine<Unit> { continuation ->
            future.addListener({
                try {
                    future.get()
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (failure: ExecutionException) {
                    if (continuation.isActive) continuation.resumeWithException(failure.cause ?: failure)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }, DIRECT_EXECUTOR)
        }
    }

    private val DIRECT_EXECUTOR = Executor { command -> command.run() }
}
