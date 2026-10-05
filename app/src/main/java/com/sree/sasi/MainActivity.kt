package com.sree.sasi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sree.sasi.ui.HomeScreen
import com.sree.sasi.ui.OnboardingScreen
import com.sree.sasi.ui.SettingsScreen
import com.sree.sasi.ui.theme.SasiTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SasiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val prefs = remember { (application as SasiApp).prefs }
                    val onboardingDone by prefs.onboardingDone.collectAsState(initial = null)
                    var route by remember { mutableStateOf("home") }

                    when (onboardingDone) {
                        null -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                        false -> OnboardingScreen()
                        true -> when (route) {
                            "settings" -> SettingsScreen(onBack = { route = "home" })
                            else -> HomeScreen(onOpenSettings = { route = "settings" })
                        }
                    }
                }
            }
        }
    }
}
