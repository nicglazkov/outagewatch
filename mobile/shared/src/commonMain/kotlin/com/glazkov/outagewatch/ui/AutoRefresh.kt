package com.glazkov.outagewatch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Keeps a screen from ever showing stale data. It refreshes when the app comes
 * back to the foreground and then on a steady interval while the screen stays
 * visible, and stops entirely when the screen is not resumed (no wasted calls in
 * the background). The screen's own ViewModel handles the first load, so this
 * skips the very first resume to avoid a redundant fetch on entry.
 *
 * [onRefresh] should be a quiet refresh (no pull-to-refresh spinner), so the
 * automatic updates never flash an indicator; manual pull still shows one.
 */
@Composable
fun AutoRefresh(intervalMillis: Long = 60_000L, onRefresh: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val firstResume = remember { mutableStateOf(true) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (firstResume.value) firstResume.value = false else onRefresh()
            while (true) {
                delay(intervalMillis)
                onRefresh()
            }
        }
    }
}
