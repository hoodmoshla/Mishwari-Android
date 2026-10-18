package ye.mishwari.geo

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Entry point for reconciliation; it does not continuously track device location. */
object GeoScheduler {
    private const val ONE_TIME_WORK = "mishwari-geo-reconcile"
    private const val PERIODIC_WORK = "mishwari-geo-recovery"

    @JvmStatic
    fun schedule(context: Context) {
        schedule(context, force = false, rearmSelected = false)
    }

    /** Force recovery after boot/provider changes; routine UI changes use the fingerprint. */
    @JvmStatic
    fun schedule(context: Context, force: Boolean) {
        schedule(context, force, rearmSelected = false)
    }

    @JvmStatic
    fun schedule(context: Context, force: Boolean, rearmSelected: Boolean) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val immediate = OneTimeWorkRequestBuilder<GeoWorker>()
            .setInputData(workDataOf(
                GeoWorker.INPUT_FORCE_RECONCILE to force,
                GeoWorker.INPUT_REARM_SELECTED to rearmSelected,
            ))
            .build()
        // Do not let a routine resume cancel a queued forced boot/provider recovery.
        workManager.enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, immediate)
        val recovery = PeriodicWorkRequestBuilder<GeoWorker>(6, TimeUnit.HOURS)
            .setInputData(workDataOf(GeoWorker.INPUT_FORCE_RECONCILE to true))
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, recovery)
    }
}
