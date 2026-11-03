package com.example.kharcha

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.kharcha.shared.App
import com.example.kharcha.shared.PlatformHooks

class MainActivity : ComponentActivity() {

    private var smsEnabled by mutableStateOf(false)

    private val requestSmsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> smsEnabled = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        smsEnabled = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

        val repository = (application as KharchaApplication).repository
        setContent {
            App(
                repository = repository,
                platform = PlatformHooks(
                    smsTrackingEnabled = smsEnabled,
                    onEnableSmsTracking = { requestSmsPermission.launch(Manifest.permission.RECEIVE_SMS) },
                ),
            )
        }
    }
}
