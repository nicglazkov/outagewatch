package com.glazkov.outagewatch

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.glazkov.outagewatch.data.DeviceLocation
import com.glazkov.outagewatch.location.AndroidLocationFinder
import com.glazkov.outagewatch.ui.App

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Constructed here so its permission launcher registers before onStart.
    private val locationFinder = AndroidLocationFinder(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DeviceLocation.finder = locationFinder
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            App()
        }
    }
}
