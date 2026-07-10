package com.glazkov.outagewatch.ui.map

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.glazkov.outagewatch.api.Outage

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun OutageMapView(
    centerLat: Double,
    centerLon: Double,
    radiusKm: Double,
    outages: List<Outage>,
    dark: Boolean,
    onOutageTap: (String) -> Unit,
    modifier: Modifier,
    zoomControl: Boolean,
    focusToken: String?,
) {
    val html = remember(centerLat, centerLon, radiusKm, outages, dark, zoomControl) {
        buildMapHtml(centerLat, centerLon, radiusKm, outages, dark, zoomControl)
    }
    // Holds the last focus token applied to the live page so a repeated
    // recomposition doesn't re-fly, but a new tap (new nonce) does.
    val lastFocus = remember { arrayOfNulls<String>(1) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView.setWebContentsDebuggingEnabled(true)
            WebView(context).apply {
                settings.javaScriptEnabled = true
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.i(
                            "OutageMap",
                            "${message.messageLevel()} ${message.message()} " +
                                "(${message.sourceId()}:${message.lineNumber()})",
                        )
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url
                        if (url.scheme == "ow" && url.host == "outage") {
                            url.lastPathSegment?.let(onOutageTap)
                            return true
                        }
                        return false
                    }
                }
            }
        },
        update = { webView ->
            // update runs on every recomposition; only reload when content changed,
            // otherwise the map restarts mid-interaction and may never settle.
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(
                    "https://outagewatch.local/", html, "text/html", "utf-8", null
                )
                lastFocus[0] = null // fresh page; allow the current focus to re-apply
            }
            if (focusToken != null && focusToken != lastFocus[0]) {
                lastFocus[0] = focusToken
                val id = focusToken.substringBefore('#')
                    .replace("\\", "\\\\").replace("'", "\\'")
                webView.evaluateJavascript(
                    "window.focusOutage && window.focusOutage('$id')", null
                )
            }
        },
    )
}
