package com.sree.sasi.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sree.sasi.SasiApp
import com.sree.sasi.R
import com.sree.sasi.overlay.CompanionService
import com.sree.sasi.screentime.ScreenTimeTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SasiApp
    val scope = rememberCoroutineScope()

    val name by app.prefs.companionName.collectAsState(initial = "Sasi")
    val enabled by app.prefs.companionEnabled.collectAsState(initial = false)
    val goal by app.prefs.dailyGoalMinutes.collectAsState(initial = 240)
    val sessionMinutes by app.prefs.continuousMinutes.collectAsState(initial = 0)

    var poll by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            poll++
        }
    }

    val tracker = remember { ScreenTimeTracker() }
    val todayMinutes = remember(poll) { tracker.getTodayScreenMinutes(context) }
    val usageGranted = remember(poll) { ScreenTimeTracker.hasUsageAccess(context) }
    val overlayGranted = remember(poll) { Settings.canDrawOverlays(context) }
    val running = remember(poll, enabled) { CompanionService.isRunning(context) }

    fun toggleCompanion(on: Boolean) {
        scope.launch {
            if (on) {
                if (!Settings.canDrawOverlays(context)) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else {
                    app.prefs.setCompanionEnabled(true)
                    CompanionService.start(context)
                }
            } else {
                CompanionService.stop(context)
            }
            poll++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SasiFace(size = 110.dp)
        }
        Text(
            text = stringResource(R.string.home_greeting, name),
            style = MaterialTheme.typography.headlineSmall,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (running) {
                            stringResource(R.string.status_companion_on)
                        } else {
                            stringResource(R.string.status_companion_off)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (running) {
                            stringResource(R.string.status_companion_on_body, name)
                        } else {
                            stringResource(R.string.status_companion_off_body, name)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(checked = enabled, onCheckedChange = ::toggleCompanion)
            }
        }

        if (!overlayGranted) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.overlay_needed_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.overlay_needed_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) {
                        Text(stringResource(R.string.action_allow_overlay))
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.stats_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (usageGranted) {
                    Text(
                        text = ScreenTimeTracker.formatMinutes(todayMinutes),
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text(
                        text = stringResource(
                            R.string.goal_label,
                            ScreenTimeTracker.formatMinutes(goal.toLong()),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = (todayMinutes.toFloat() / goal.toFloat()).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(
                            R.string.session_label,
                            ScreenTimeTracker.formatMinutes(sessionMinutes.toLong()),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.usage_needed_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) {
                        Text(stringResource(R.string.action_grant_usage))
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_open_settings))
        }
    }
}
