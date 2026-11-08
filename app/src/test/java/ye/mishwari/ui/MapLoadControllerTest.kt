package ye.mishwari.ui

import android.app.Application
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MapLoadControllerTest {
    @Test
    fun styleReadyIsDistinctFromFullMapLoadAndSuccessCancelsTimeout() {
        val controller = MapLoadController(timeoutMillis = 100)
        try {
            val attempt = controller.attempt
            controller.styleLoaded(attempt)
            assertEquals(MapLoadPhase.LOADING_TILES, controller.phase)

            controller.mapLoaded(attempt)
            assertEquals(MapLoadPhase.LOADED, controller.phase)
            advanceBy(150)
            assertEquals("a completed map must not later time out", MapLoadPhase.LOADED, controller.phase)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun timeoutReportsWhetherStyleHadLoaded() {
        val controller = MapLoadController(timeoutMillis = 100)
        try {
            val attempt = controller.attempt
            controller.styleLoaded(attempt)
            advanceBy(100)
            assertEquals(MapLoadPhase.TIMED_OUT, controller.phase)
            assertTrue(controller.timedOutAfterStyle)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun mapFailureKeepsDiagnosticAndCancelsTimeout() {
        val controller = MapLoadController(timeoutMillis = 100)
        try {
            controller.mapFailed(controller.attempt, "HTTP 503")
            assertEquals(MapLoadPhase.FAILED, controller.phase)
            assertEquals("HTTP 503", controller.failureDetail)
            advanceBy(150)
            assertEquals(MapLoadPhase.FAILED, controller.phase)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun retryCreatesNewAttemptResetsStateAndIgnoresOldCallbacksAndTimeout() {
        val controller = MapLoadController(timeoutMillis = 100)
        try {
            val oldAttempt = controller.attempt
            advanceBy(70)
            controller.retry()
            val newAttempt = controller.attempt

            assertNotEquals(oldAttempt, newAttempt)
            assertEquals(MapLoadPhase.LOADING_STYLE, controller.phase)
            assertNull(controller.failureDetail)
            controller.styleLoaded(oldAttempt)
            controller.mapFailed(oldAttempt, "stale failure")
            assertEquals("callbacks from the old MapView must be ignored", MapLoadPhase.LOADING_STYLE, controller.phase)

            advanceBy(40)
            assertEquals("the previous attempt's timeout must not affect the retry", MapLoadPhase.LOADING_STYLE, controller.phase)
            controller.mapLoaded(newAttempt)
            assertEquals(MapLoadPhase.LOADED, controller.phase)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun disposeCancelsTimeoutAndIgnoresCallbacks() {
        val controller = MapLoadController(timeoutMillis = 100)
        val attempt = controller.attempt
        advanceBy(40)
        controller.dispose()
        advanceBy(100)
        controller.mapLoaded(attempt)
        assertEquals(MapLoadPhase.DISPOSED, controller.phase)
        assertFalse(controller.phase == MapLoadPhase.TIMED_OUT)
    }

    private fun advanceBy(milliseconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(milliseconds, TimeUnit.MILLISECONDS)
    }
}
