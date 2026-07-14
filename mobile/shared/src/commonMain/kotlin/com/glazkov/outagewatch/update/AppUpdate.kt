package com.glazkov.outagewatch.update

import com.glazkov.outagewatch.api.OutageApi
import com.russhwolf.settings.Settings

/**
 * In-app update check for the sideloaded (GitHub Releases) build. There is no
 * Play In-App Updates API here, so we ask the GitHub Releases API for the latest
 * version and compare it to the installed one.
 */
object AppUpdate {
    private const val KEY_NOTIFIED = "update_notified_version"
    private const val RELEASES_URL = "https://github.com/nicglazkov/outagewatch/releases/latest"

    data class Available(val version: String, val url: String)

    /**
     * Returns an [Available] only the first time a given newer version is seen,
     * so the app prompts once and only once per update. Returns null on any error,
     * when already up to date, or when this version was already shown.
     */
    suspend fun check(current: String, api: OutageApi, settings: Settings = Settings()): Available? {
        val release = runCatching { api.latestRelease() }.getOrNull() ?: return null
        return decide(
            current = current,
            latestTag = release.tagName,
            url = release.htmlUrl,
            lastNotified = settings.getStringOrNull(KEY_NOTIFIED),
            markNotified = { settings.putString(KEY_NOTIFIED, it) },
        )
    }

    /** Pure decision: given the installed version, the latest tag, and the last
     *  version we already prompted for, should we prompt now? Calls [markNotified]
     *  when it decides to prompt, so it prompts once and only once per update. */
    fun decide(
        current: String,
        latestTag: String?,
        url: String?,
        lastNotified: String?,
        markNotified: (String) -> Unit,
    ): Available? {
        if (current.isBlank()) return null
        val latest = latestTag?.removePrefix("v")?.trim().orEmpty()
        if (latest.isEmpty() || compareVersions(latest, current) <= 0) return null
        if (lastNotified == latest) return null
        markNotified(latest)
        return Available(latest, url ?: RELEASES_URL)
    }

    /** Compare dotted numeric versions. Positive if [a] is newer than [b]. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".")
        val pb = b.split(".")
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x - y
        }
        return 0
    }
}
