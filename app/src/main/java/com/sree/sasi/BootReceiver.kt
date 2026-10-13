package com.sree.sasi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.sree.sasi.overlay.CompanionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as SasiApp
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val snapshot = app.prefs.snapshot()
                if (snapshot.onboardingDone && snapshot.companionEnabled &&
                    snapshot.visibilityState != "DISABLED" && Settings.canDrawOverlays(context)
                ) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, CompanionService::class.java)
                            .setAction(CompanionService.ACTION_START),
                    )
                }
            } catch (e: Exception) {
                // OEM/background restrictions must not crash the boot receiver.
                Log.w("SasiBoot", "Could not restore companion after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
