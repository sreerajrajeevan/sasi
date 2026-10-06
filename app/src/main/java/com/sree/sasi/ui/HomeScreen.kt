package com.sree.sasi.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sree.sasi.SasiApp
import com.sree.sasi.R
import com.sree.sasi.data.History
import com.sree.sasi.overlay.CompanionService
import com.sree.sasi.screentime.ScreenTimeTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
private fun visibilityMessage(state: String, hiddenUntil: Long): String {
    return when (state) {
        "HIDDEN_TEMPORARILY" -> {
            val mins = ((hiddenUntil - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1L)
            stringResource(R.string.visibility_return_minutes, mins)
        }
        "HIDDEN_UNTIL_SCREEN_LOCK" -> stringResource(R.string.visibility_until_lock)
        "HIDDEN_UNTIL_APP_OPEN" -> stringResource(R.string.visibility_until_app)
        "HIDDEN_UNTIL_REMINDER" -> stringResource(R.string.visibility_until_reminder)
        "DISABLED" -> stringResource(R.string.visibility_disabled)
        else -> stringResource(R.string.visibility_until_reminder)
    }
}

/** yyyy-MM-dd keys for today and the 6 days before it. */
private fun last7DateKeys(): List<String> {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val cal = Calendar.getInstance()
    return (6 downTo 0).map { offset ->
        cal.timeInMillis = System.currentTimeMillis()
        cal.add(Calendar.DAY_OF_YEAR, -offset)
        fmt.format(cal.time)
    }
}

private fun dayLetter(dateKey: String): String = try {
    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateKey) ?: return "·"
    SimpleDateFormat("EE", Locale.US).format(parsed).take(1)
} catch (e: Exception) {
    "·"
}

private fun prettyDay(dateKey: String): String = try {
    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateKey) ?: return dateKey
    SimpleDateFormat("MMM d", Locale.US).format(parsed)
} catch (e: Exception) {
    dateKey
}

@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SasiApp
    val scope = rememberCoroutineScope()

    val name by app.prefs.companionName.collectAsState(initial = "Sasi")
    val enabled by app.prefs.companionEnabled.collectAsState(initial = false)
    val goal by app.prefs.dailyGoalMinutes.collectAsState(initial = 240)
    val sessionMinutes by app.prefs.continuousMinutes.collectAsState(initial = 0)
    val visibilityName by app.prefs.visibilityState.collectAsState(initial = "VISIBLE")
    val hiddenUntil by app.prefs.hiddenUntilMillis.collectAsState(initial = 0L)
    val focusActive by app.prefs.focusActive.collectAsState(initial = false)
    val focusEndsAt by app.prefs.focusEndsAt.collectAsState(initial = 0L)
    val focusPomodoro by app.prefs.focusPomodoro.collectAsState(initial = false)
    val focusCycle by app.prefs.focusCycle.collectAsState(initial = 1)
    val breakActive by app.prefs.breakActive.collectAsState(initial = false)
    val breakEndsAt by app.prefs.breakEndsAt.collectAsState(initial = 0L)
    val historyJson by app.prefs.historyJson.collectAsState(initial = "[]")

    var poll by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            poll++
        }
    }

    // Live countdown while a timer runs.
    val modeActive = focusActive || breakActive
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(modeActive) {
        while (modeActive) {
            delay(1_000)
            nowMillis = System.currentTimeMillis()
        }
    }

    var showFocusDialog by remember { mutableStateOf(false) }
    var showBreakDialog by remember { mutableStateOf(false) }

    val tracker = remember { ScreenTimeTracker() }
    val todayMinutes = remember(poll) { tracker.getTodayScreenMinutes(context) }
    val usageGranted = remember(poll) { ScreenTimeTracker.hasUsageAccess(context) }
    val overlayGranted = remember(poll) { Settings.canDrawOverlays(context) }
    val running = remember(poll, enabled) { CompanionService.isRunning(context) }

    val todayRecord = remember(historyJson, poll) {
        History.load(historyJson).find { it.date == SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date()) }
    }

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
                    app.prefs.setVisibilityState("VISIBLE")
                    CompanionService.start(context)
                }
            } else {
                CompanionService.stop(context)
            }
            poll++
        }
    }

    /** Makes sure the service is up, then starts a focus session. */
    fun startFocus(minutes: Int, pomodoro: Boolean) {
        scope.launch {
            if (!Settings.canDrawOverlays(context)) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return@launch
            }
            app.prefs.setCompanionEnabled(true)
            app.prefs.setVisibilityState("VISIBLE")
            val now = System.currentTimeMillis()
            app.prefs.setFocusActive(true)
            app.prefs.setFocusEndsAt(now + minutes * 60_000L)
            app.prefs.setFocusTotalMin(minutes)
            app.prefs.setFocusPomodoro(pomodoro)
            app.prefs.setFocusCycle(1)
            app.prefs.setFocusStartedAt(now)
            app.prefs.setBreakActive(false) // focus cancels break
            CompanionService.start(context)
            poll++
        }
        showFocusDialog = false
    }

    /** Makes sure the service is up, then starts a break (cancels focus). */
    fun startBreak(totalSeconds: Int) {
        scope.launch {
            if (!Settings.canDrawOverlays(context)) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return@launch
            }
            app.prefs.setCompanionEnabled(true)
            app.prefs.setVisibilityState("VISIBLE")
            val now = System.currentTimeMillis()
            app.prefs.setFocusActive(false) // break cancels focus
            app.prefs.setBreakActive(true)
            app.prefs.setBreakEndsAt(now + totalSeconds * 1_000L)
            app.prefs.setBreakTotalSec(totalSeconds)
            app.prefs.setBreakIsPomodoro(false)
            CompanionService.start(context)
            poll++
        }
        showBreakDialog = false
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

        // --- Current mode: idle / focus / break ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    focusActive -> {
                        Text(
                            text = "🎯 Focusing" + if (focusPomodoro) " · cycle $focusCycle" else "",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = ScreenTimeTracker.formatCountdown(
                                (focusEndsAt - nowMillis).coerceAtLeast(0L),
                            ),
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Button(onClick = { CompanionService.cancelModes(context) }) {
                            Text(stringResource(R.string.action_cancel_mode))
                        }
                    }
                    breakActive -> {
                        Text(
                            text = "🌱 On a break",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = ScreenTimeTracker.formatCountdown(
                                (breakEndsAt - nowMillis).coerceAtLeast(0L),
                            ),
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Button(onClick = { CompanionService.cancelModes(context) }) {
                            Text(stringResource(R.string.action_cancel_mode))
                        }
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.mode_idle_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { showFocusDialog = true },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.action_start_focus))
                            }
                            OutlinedButton(
                                onClick = { showBreakDialog = true },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.action_take_break))
                            }
                        }
                    }
                }
            }
        }

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

        if (enabled && visibilityName != "VISIBLE") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = visibilityMessage(visibilityName, hiddenUntil),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { CompanionService.bringBack(context) }) {
                        Text(stringResource(R.string.action_bring_back))
                    }
                }
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

        // --- Today ---
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
                    Text(
                        text = stringResource(
                            R.string.today_sessions_label,
                            todayRecord?.sessions ?: 0,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.today_focus_label,
                            ScreenTimeTracker.formatMinutes((todayRecord?.focusMin ?: 0).toLong()),
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

        WeekCard(historyJson = historyJson)

        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_open_settings))
        }
    }

    if (showFocusDialog) {
        FocusDialog(
            onDismiss = { showFocusDialog = false },
            onStart = ::startFocus,
        )
    }
    if (showBreakDialog) {
        BreakDialog(
            onDismiss = { showBreakDialog = false },
            onStart = ::startBreak,
        )
    }
}

@Composable
private fun FocusDialog(onDismiss: () -> Unit, onStart: (minutes: Int, pomodoro: Boolean) -> Unit) {
    var selected by remember { mutableIntStateOf(25) }
    var custom by remember { mutableStateOf("") }
    var pomodoro by remember { mutableStateOf(false) }
    val customMin = custom.toIntOrNull()
    val customValid = custom.isBlank() || (customMin != null && customMin in 1..240)
    val minutes = if (custom.isNotBlank()) customMin ?: 0 else selected

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.focus_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.focus_duration_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 25, 45, 60).forEach { m ->
                        FilterChip(
                            selected = custom.isBlank() && selected == m,
                            onClick = {
                                selected = m
                                custom = ""
                            },
                            label = { Text("$m") },
                        )
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text(stringResource(R.string.focus_custom_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = !customValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = pomodoro,
                        onCheckedChange = { pomodoro = it },
                    )
                    Text(
                        text = stringResource(R.string.focus_pomodoro_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onStart(minutes, pomodoro) },
                enabled = customValid && minutes in 1..240,
            ) {
                Text(stringResource(R.string.action_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun BreakDialog(onDismiss: () -> Unit, onStart: (totalSeconds: Int) -> Unit) {
    val presets = listOf(20 to "20s", 120 to "2 min", 300 to "5 min")
    var selected by remember { mutableIntStateOf(120) }
    var custom by remember { mutableStateOf("") }
    val customMin = custom.toIntOrNull()
    val customValid = custom.isBlank() || (customMin != null && customMin in 1..120)
    val totalSeconds = if (custom.isNotBlank()) (customMin ?: 0) * 60 else selected

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.break_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { (seconds, label) ->
                        FilterChip(
                            selected = custom.isBlank() && selected == seconds,
                            onClick = {
                                selected = seconds
                                custom = ""
                            },
                            label = { Text(label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text(stringResource(R.string.break_custom_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = !customValid,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onStart(totalSeconds) },
                enabled = customValid && totalSeconds in 5..7200,
            ) {
                Text(stringResource(R.string.action_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun WeekCard(historyJson: String) {
    val records = remember(historyJson) { History.load(historyJson) }
    val days = remember { last7DateKeys() }
    val byDate = remember(records) { records.associateBy { it.date } }
    val maxMin = (days.maxOfOrNull { byDate[it]?.screenMin ?: 0 } ?: 0).coerceAtLeast(1)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.week_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEach { date ->
                    val mins = byDate[date]?.screenMin ?: 0
                    val frac = mins.toFloat() / maxMin
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier.height(72.dp),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(20.dp)
                                    .height((8 + frac * 64).dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(4.dp),
                                    ),
                            )
                        }
                        Text(
                            text = dayLetter(date),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            val totalScreen = days.sumOf { byDate[it]?.screenMin ?: 0 }
            val totalFocus = days.sumOf { byDate[it]?.focusMin ?: 0 }
            val totalFocusSessions = days.sumOf { byDate[it]?.focusSessions ?: 0 }
            if (totalScreen == 0 && totalFocus == 0) {
                Text(
                    text = stringResource(R.string.week_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.week_totals_screen,
                        ScreenTimeTracker.formatMinutes(totalScreen.toLong()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.week_totals_focus,
                        ScreenTimeTracker.formatMinutes(totalFocus.toLong()),
                        totalFocusSessions,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val best = days.maxByOrNull { byDate[it]?.focusMin ?: 0 }
                val bestMin = best?.let { byDate[it]?.focusMin ?: 0 } ?: 0
                if (best != null && bestMin > 0) {
                    Text(
                        text = stringResource(
                            R.string.week_best_day,
                            prettyDay(best),
                            ScreenTimeTracker.formatMinutes(bestMin.toLong()),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
