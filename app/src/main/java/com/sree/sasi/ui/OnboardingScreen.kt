package com.sree.sasi.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sree.sasi.SasiApp
import com.sree.sasi.R
import com.sree.sasi.overlay.CompanionService
import com.sree.sasi.screentime.ScreenTimeTracker
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as SasiApp
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("Sasi") }
    var resumed by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { resumed++ }

    val overlayGranted = remember(resumed) { Settings.canDrawOverlays(context) }
    val usageGranted = remember(resumed) { ScreenTimeTracker.hasUsageAccess(context) }
    val notifGranted = remember(resumed) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun openOverlaySettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openUsageSettings() {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openBatterySettings() {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            // Some OEMs block this intent; the step is optional.
        }
    }

    fun finish() {
        scope.launch {
            app.prefs.setCompanionName(name.trim().ifEmpty { "Sasi" })
            app.prefs.setOnboardingDone(true)
            app.prefs.setCompanionEnabled(true)
            CompanionService.start(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Step ${step + 1} of 6",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        LinearProgressIndicator(
            progress = (step + 1) / 6f,
            modifier = Modifier.fillMaxWidth(),
        )

        when (step) {
            0 -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SasiFace(size = 160.dp)
                    Text(
                        text = stringResource(R.string.onboarding_welcome_title),
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.onboarding_welcome_body),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            1 -> PermissionStep(
                title = stringResource(R.string.onboarding_overlay_title),
                body = stringResource(R.string.onboarding_overlay_body),
                granted = overlayGranted,
                actionLabel = stringResource(R.string.action_allow_overlay),
                onAction = ::openOverlaySettings,
            )
            2 -> PermissionStep(
                title = stringResource(R.string.onboarding_usage_title),
                body = stringResource(R.string.onboarding_usage_body),
                granted = usageGranted,
                actionLabel = stringResource(R.string.action_grant_usage),
                onAction = ::openUsageSettings,
            )
            3 -> PermissionStep(
                title = stringResource(R.string.onboarding_notif_title),
                body = stringResource(R.string.onboarding_notif_body),
                granted = notifGranted,
                actionLabel = stringResource(R.string.action_allow_notifications),
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
            4 -> PermissionStep(
                title = stringResource(R.string.onboarding_battery_title),
                body = stringResource(R.string.onboarding_battery_body),
                granted = false,
                actionLabel = stringResource(R.string.action_allow_background),
                onAction = ::openBatterySettings,
                optionalHint = stringResource(R.string.optional_step_hint),
            )
            5 -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SasiFace(size = 120.dp, faceRes = R.drawable.cat_face_happy)
                    Text(
                        text = stringResource(R.string.onboarding_name_title),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 20) name = it },
                        label = { Text(stringResource(R.string.companion_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0) {
                OutlinedButton(onClick = { step-- }) {
                    Text(stringResource(R.string.action_back))
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            when (step) {
                4 -> Button(onClick = { step++ }) {
                    Text(stringResource(R.string.action_skip))
                }
                5 -> Button(onClick = ::finish) {
                    Text(stringResource(R.string.action_finish))
                }
                else -> Button(onClick = { step++ }) {
                    Text(stringResource(R.string.action_next))
                }
            }
        }
    }
}

@Composable
private fun PermissionStep(
    title: String,
    body: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    optionalHint: String? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            if (optionalHint != null) {
                Text(
                    text = optionalHint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (granted) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.status_granted)) },
                    enabled = false,
                )
            } else {
                Button(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
