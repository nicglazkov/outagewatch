package com.glazkov.outagewatch

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.glazkov.outagewatch.data.DeviceLocation
import com.glazkov.outagewatch.location.AndroidLocationFinder
import com.glazkov.outagewatch.ui.App
import com.glazkov.outagewatch.ui.AppGraph
import com.glazkov.outagewatch.ui.PendingOutage
import kotlinx.coroutines.launch

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
        // A notification tap launches us with this extra; let the UI open it.
        intent?.getStringExtra(EXTRA_OUTAGE_ID)?.let { PendingOutage.id.value = it }
        // Heal any subscription that couldn't register earlier (offline / no token).
        lifecycleScope.launch { AppGraph.locations.retryMissingSubscriptions() }
        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OUTAGE_ID)?.let { PendingOutage.id.value = it }
    }

    companion object {
        const val EXTRA_OUTAGE_ID = "outage_id"
    }
}
