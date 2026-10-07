package com.sree.sasi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sree.sasi.SasiApp
import com.sree.sasi.R
import com.sree.sasi.ui.theme.themeNames
import com.sree.sasi.ui.theme.themeSwatches
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SasiApp
    val scope = rememberCoroutineScope()

    val name by app.prefs.companionName.collectAsState(initial = "Sasi")
    val rest by app.prefs.restMinutes.collectAsState(initial = 50)
    val bedHour by app.prefs.bedtimeHour.collectAsState(initial = 23)
    val bedMinute by app.prefs.bedtimeMinute.collectAsState(initial = 0)
    val wakeHour by app.prefs.wakeHour.collectAsState(initial = 7)
    val wakeMinute by app.prefs.wakeMinute.collectAsState(initial = 0)
    val goal by app.prefs.dailyGoalMinutes.collectAsState(initial = 240)
    val size by app.prefs.overlaySize.collectAsState(initial = 1)
    val speed by app.prefs.walkSpeed.collectAsState(initial = 1)
    val themeIndex by app.prefs.colorTheme.collectAsState(initial = 0)
    val moveMode by app.prefs.movementMode.collectAsState(initial = 0)
    val moveFreq by app.prefs.movementFrequency.collectAsState(initial = 1)
    val peekMode by app.prefs.peekMode.collectAsState(initial = true)
    val peekSide by app.prefs.peekSide.collectAsState(initial = 1)
    val tapReactions by app.prefs.tapReactions.collectAsState(initial = true)
    val speechBubbles by app.prefs.speechBubbles.collectAsState(initial = true)
    val hapticFeedback by app.prefs.hapticFeedback.collectAsState(initial = true)

    var nameDraft by remember(name) { mutableStateOf(name) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.companion_name_label),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { if (it.length <= 20) nameDraft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        scope.launch {
                            app.prefs.setCompanionName(nameDraft.trim().ifEmpty { "Sasi" })
                        }
                    },
                    enabled = nameDraft.trim() != name,
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.rest_interval_label),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.rest_interval_value, rest),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = rest.toFloat(),
                    onValueChange = { newValue ->
                        val stepped = (newValue / 5).roundToInt() * 5
                        scope.launch { app.prefs.setRestMinutes(stepped) }
                    },
                    valueRange = 20f..120f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.sleep_schedule_label),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TimeSettingButton(
                        label = stringResource(R.string.bedtime_label),
                        hour = bedHour,
                        minute = bedMinute,
                        modifier = Modifier.weight(1f),
                        onConfirm = { h, m ->
                            scope.launch { app.prefs.setBedtime(h, m) }
                        },
                    )
                    TimeSettingButton(
                        label = stringResource(R.string.wake_label),
                        hour = wakeHour,
                        minute = wakeMinute,
                        modifier = Modifier.weight(1f),
                        onConfirm = { h, m ->
                            scope.launch { app.prefs.setWakeTime(h, m) }
                        },
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.daily_goal_label),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(120 to "2h", 180 to "3h", 240 to "4h", 360 to "6h").forEach { (minutes, label) ->
                        FilterChip(
                            selected = goal == minutes,
                            onClick = { scope.launch { app.prefs.setDailyGoalMinutes(minutes) } },
                            label = { Text(label) },
                        )
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
                    text = stringResource(R.string.overlay_size_label),
                    style = MaterialTheme.typography.titleMedium,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("S", "M", "L").forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = size == index,
                            onClick = { scope.launch { app.prefs.setOverlaySize(index) } },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = 3,
                            ),
                        ) {
                            Text(label)
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.walk_speed_label),
                    style = MaterialTheme.typography.titleMedium,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        stringResource(R.string.speed_slow),
                        stringResource(R.string.speed_normal),
                        stringResource(R.string.speed_zippy),
                    ).forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = speed == index,
                            onClick = { scope.launch { app.prefs.setWalkSpeed(index) } },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = 3,
                            ),
                        ) {
                            Text(label)
                        }
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
                    text = stringResource(R.string.color_theme_label),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    themeSwatches.forEachIndexed { index, color ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (themeIndex == index) 3.dp else 1.dp,
                                        color = if (themeIndex == index) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        shape = CircleShape,
                                    )
                                    .clickable {
                                        scope.launch { app.prefs.setColorTheme(index) }
                                    },
                            )
                            Text(
                                text = themeNames[index],
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
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
                    text = stringResource(R.string.movement_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.movement_mode_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        stringResource(R.string.mode_free),
                        stringResource(R.string.mode_edge),
                        stringResource(R.string.mode_calm),
                        stringResource(R.string.mode_locked),
                    ).forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = moveMode == index,
                            onClick = { scope.launch { app.prefs.setMovementMode(index) } },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = 4,
                            ),
                        ) {
                            Text(label)
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.movement_freq_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        stringResource(R.string.freq_low),
                        stringResource(R.string.freq_normal),
                        stringResource(R.string.freq_high),
                    ).forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = moveFreq == index,
                            onClick = { scope.launch { app.prefs.setMovementFrequency(index) } },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = 3,
                            ),
                        ) {
                            Text(label)
                        }
                    }
                }
                SwitchRow(
                    label = stringResource(R.string.peek_mode_label),
                    checked = peekMode,
                    onCheckedChange = { scope.launch { app.prefs.setPeekMode(it) } },
                )
                if (peekMode) {
                    Text(
                        text = stringResource(R.string.peek_side_label),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf(
                            stringResource(R.string.peek_side_left),
                            stringResource(R.string.peek_side_right),
                            stringResource(R.string.peek_side_bottom),
                        ).forEachIndexed { index, label ->
                            SegmentedButton(
                                selected = peekSide == index,
                                onClick = { scope.launch { app.prefs.setPeekSide(index) } },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = 3,
                                ),
                            ) {
                                Text(label)
                            }
                        }
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
                    text = stringResource(R.string.interactions_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                SwitchRow(
                    label = stringResource(R.string.tap_reactions_label),
                    checked = tapReactions,
                    onCheckedChange = { scope.launch { app.prefs.setTapReactions(it) } },
                )
                SwitchRow(
                    label = stringResource(R.string.speech_bubbles_label),
                    checked = speechBubbles,
                    onCheckedChange = { scope.launch { app.prefs.setSpeechBubbles(it) } },
                )
                SwitchRow(
                    label = stringResource(R.string.haptic_feedback_label),
                    checked = hapticFeedback,
                    onCheckedChange = { scope.launch { app.prefs.setHapticFeedback(it) } },
                )
            }
        }

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_back))
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSettingButton(
    label: String,
    hour: Int,
    minute: Int,
    modifier: Modifier = Modifier,
    onConfirm: (Int, Int) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val pickerState = rememberTimePickerState(
        initialHour = hour,
        initialMinute = minute,
        is24Hour = true,
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Button(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("%02d:%02d".format(hour, minute))
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    onConfirm(pickerState.hour, pickerState.minute)
                    showDialog = false
                }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            text = { TimePicker(state = pickerState) },
        )
    }
}
