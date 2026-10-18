package ye.mishwari.geo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class GeoRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        GeoRecoveryDispatch.dispatch(intent.action) { force, rearmSelected ->
            GeoScheduler.schedule(context, force = force, rearmSelected = rearmSelected)
        }
    }
}

/** Action-to-input mapping shared with the receiver and verified without starting background work. */
internal object GeoRecoveryDispatch {
    fun dispatch(action: String?, schedule: (force: Boolean, rearmSelected: Boolean) -> Unit): Boolean {
        val flags = when (action) {
            Intent.ACTION_BOOT_COMPLETED, ACTION_PROVIDERS_CHANGED -> true to true
            Intent.ACTION_MY_PACKAGE_REPLACED -> true to false
            else -> return false
        }
        schedule(flags.first, flags.second)
        return true
    }

    const val ACTION_PROVIDERS_CHANGED = "android.location.PROVIDERS_CHANGED"
}
