package com.glazkov.outagewatch.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import com.glazkov.outagewatch.api.Outage
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationType
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKNavigationAction
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
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
    val lastHtml = remember { arrayOfNulls<String>(1) }
    val lastFocus = remember { arrayOfNulls<String>(1) }
    val delegate = remember {
        object : NSObject(), WKNavigationDelegateProtocol {
            override fun webView(
                webView: WKWebView,
                decidePolicyForNavigationAction: WKNavigationAction,
                decisionHandler: (WKNavigationActionPolicy) -> Unit,
            ) {
                val url = decidePolicyForNavigationAction.request.URL
                val isLinkTap =
                    decidePolicyForNavigationAction.navigationType ==
                        WKNavigationType.WKNavigationTypeLinkActivated
                if (url?.scheme == "ow" && url.host == "outage") {
                    url.lastPathComponent?.let(onOutageTap)
                    decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                } else if (isLinkTap) {
                    // A tapped link (e.g. map attribution) must never navigate the
                    // WebView's own frame; block it. Everything that is not a link
                    // tap (the initial load, internal script) is allowed through.
                    decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                } else {
                    decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
                }
            }
        }
    }
    UIKitView(
        modifier = modifier,
        factory = {
            WKWebView(frame = kotlinx.cinterop.cValue { }, configuration = WKWebViewConfiguration()).apply {
                navigationDelegate = delegate
            }
        },
        update = { webView ->
            // Only reload when the page content changes; otherwise the map
            // restarts mid-interaction. A focus tap just calls into the page.
            if (lastHtml[0] != html) {
                lastHtml[0] = html
                webView.loadHTMLString(html, baseURL = NSURL(string = "https://outagewatch.local/"))
                lastFocus[0] = null
            }
            if (focusToken != null && focusToken != lastFocus[0]) {
                lastFocus[0] = focusToken
                val id = focusToken.substringBefore('#')
                    .replace("\\", "\\\\").replace("'", "\\'")
                webView.evaluateJavaScript(
                    "window.focusOutage && window.focusOutage('$id')", null
                )
            }
        },
    )
}
