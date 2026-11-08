package ye.mishwari.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class MapLoadPhase {
    LOADING_STYLE,
    LOADING_TILES,
    LOADED,
    FAILED,
    TIMED_OUT,
    DISPOSED,
}

/** Tracks one MapView attempt; callbacks and timers from older attempts are ignored. */
internal class MapLoadController(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    var attempt by mutableIntStateOf(0)
        private set
    var phase by mutableStateOf(MapLoadPhase.LOADING_STYLE)
        private set
    var failureDetail by mutableStateOf<String?>(null)
        private set
    var timedOutAfterStyle by mutableStateOf(false)
        private set

    private var disposed = false
    private var timeoutRunnable: Runnable? = null

    init {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        scheduleTimeout(attempt)
    }

    fun styleLoaded(forAttempt: Int) {
        if (!isCurrent(forAttempt) || phase != MapLoadPhase.LOADING_STYLE) return
        phase = MapLoadPhase.LOADING_TILES
    }

    fun mapLoaded(forAttempt: Int) {
        if (!isCurrent(forAttempt) || phase == MapLoadPhase.FAILED) return
        phase = MapLoadPhase.LOADED
        failureDetail = null
        timedOutAfterStyle = false
        cancelTimeout()
    }

    fun mapFailed(forAttempt: Int, detail: String? = null) {
        if (!isCurrent(forAttempt) || phase == MapLoadPhase.LOADED) return
        phase = MapLoadPhase.FAILED
        failureDetail = detail?.trim()?.take(MAX_FAILURE_DETAIL_LENGTH)?.takeIf(String::isNotEmpty)
        cancelTimeout()
    }

    /** Starts a fresh timeout; the caller must also create a new MapView for this attempt. */
    fun retry() {
        if (disposed) return
        cancelTimeout()
        attempt++
        phase = MapLoadPhase.LOADING_STYLE
        failureDetail = null
        timedOutAfterStyle = false
        scheduleTimeout(attempt)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        cancelTimeout()
        phase = MapLoadPhase.DISPOSED
    }

    private fun isCurrent(forAttempt: Int): Boolean = !disposed && forAttempt == attempt

    private fun scheduleTimeout(forAttempt: Int) {
        val runnable = Runnable {
            if (isCurrent(forAttempt) && phase != MapLoadPhase.LOADED && phase != MapLoadPhase.FAILED) {
                timedOutAfterStyle = phase == MapLoadPhase.LOADING_TILES
                phase = MapLoadPhase.TIMED_OUT
                timeoutRunnable = null
            }
        }
        timeoutRunnable = runnable
        handler.postDelayed(runnable, timeoutMillis)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let(handler::removeCallbacks)
        timeoutRunnable = null
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 25_000L
        private const val MAX_FAILURE_DETAIL_LENGTH = 240
    }
}
