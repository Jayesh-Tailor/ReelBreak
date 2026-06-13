package com.example.reelbreak

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.reelbreak.ui.theme.ReelBreakTheme

class MainActivity : ComponentActivity() {
    private var isAccessibilityEnabled by mutableStateOf(false)
    private var canDrawOverlay by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissionStatus()
        setContent {
            ReelBreakTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SetupScreen(
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        canDrawOverlay = canDrawOverlay,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    private fun refreshPermissionStatus() {
        isAccessibilityEnabled = isReelWatcherServiceEnabled()
        canDrawOverlay = Settings.canDrawOverlays(this)
    }

    private fun isReelWatcherServiceEnabled(): Boolean {
        val expectedService = ComponentName(this, ReelWatcherService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabledServices.contains(expectedService, ignoreCase = true)
    }
}

@Composable
fun SetupScreen(
    isAccessibilityEnabled: Boolean,
    canDrawOverlay: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "ReelBreak Setup",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Accessibility Service: ${if (isAccessibilityEnabled) "Enabled" else "Disabled"}"
        )
        Text(
            text = "Overlay Permission: ${if (canDrawOverlay) "Granted" else "Not granted"}"
        )
        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        ) {
            Text("Enable Accessibility Service")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri()
                )
                context.startActivity(intent)
            }
        ) {
            Text("Grant Overlay Permission")
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("After enabling both permissions, open Instagram Reels.")
    }
}

@Preview(showBackground = true)
@Composable
fun SetupScreenPreview() {
    ReelBreakTheme {
        SetupScreen(
            isAccessibilityEnabled = false,
            canDrawOverlay = false
        )
    }
}