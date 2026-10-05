package com.sree.sasi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val snapshot = try {
                app.prefs.snapshot()
            } catch (e: Exception) {
                return@launch
            }
            if (snapshot.onboardingDone && snapshot.companionEnabled) {
                // Allowed: SYSTEM_ALERT_WINDOW holders may start a foreground service
                // from the background.
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CompanionService::class.java)
                        .setAction(CompanionService.ACTION_START),
                )
            }
        }
    }
}
