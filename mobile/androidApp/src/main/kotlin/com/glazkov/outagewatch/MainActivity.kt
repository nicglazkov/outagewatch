package com.glazkov.outagewatch

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.glazkov.outagewatch.data.DeviceLocation
import com.glazkov.outagewatch.location.AndroidLocationFinder
import com.glazkov.outagewatch.ui.App
import com.glazkov.outagewatch.ui.AppGraph
import com.glazkov.outagewatch.ui.AppInfo
import com.glazkov.outagewatch.ui.ExternalLinks
import com.glazkov.outagewatch.ui.PendingOutage
import com.glazkov.outagewatch.update.UpdateCheckWorker
import java.util.concurrent.TimeUnit
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
        AppInfo.version = BuildConfig.VERSION_NAME
        ExternalLinks.opener = { url ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // A notification tap launches us with this extra; let the UI open it.
        intent?.getStringExtra(EXTRA_OUTAGE_ID)?.let { PendingOutage.id.value = it }
        lifecycleScope.launch {
            // One-time: switch existing watches to address-only so the app stops
            // sending "outage nearby" pushes; then heal any un-registered subs.
            AppGraph.locations.migrateAreaAlertsOff()
            AppGraph.locations.retryMissingSubscriptions()
        }
        scheduleUpdateChecks()
        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OUTAGE_ID)?.let { PendingOutage.id.value = it }
    }

    /** Check GitHub for a newer release about twice a day, in the background, so
     *  a new version can raise a notification even when the app isn't open. */
    private fun scheduleUpdateChecks() {
        val work = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            UpdateCheckWorker.WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, work,
        )
    }

    companion object {
        const val EXTRA_OUTAGE_ID = "outage_id"
    }
}
