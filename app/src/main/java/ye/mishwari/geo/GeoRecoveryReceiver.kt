package ye.mishwari.geo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class GeoRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.location.PROVIDERS_CHANGED" -> GeoScheduler.schedule(context, force = true, rearmSelected = true)
            Intent.ACTION_MY_PACKAGE_REPLACED -> GeoScheduler.schedule(context, force = true, rearmSelected = false)
        }
    }
}
