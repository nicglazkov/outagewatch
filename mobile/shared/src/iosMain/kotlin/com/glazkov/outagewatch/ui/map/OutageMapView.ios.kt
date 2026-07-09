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
) {
    val html = remember(centerLat, centerLon, radiusKm, outages, dark) {
        buildMapHtml(centerLat, centerLon, radiusKm, outages, dark)
    }
    val delegate = remember {
        object : NSObject(), WKNavigationDelegateProtocol {
            override fun webView(
                webView: WKWebView,
                decidePolicyForNavigationAction: WKNavigationAction,
                decisionHandler: (WKNavigationActionPolicy) -> Unit,
            ) {
                val url = decidePolicyForNavigationAction.request.URL
                if (url?.scheme == "ow" && url.host == "outage") {
                    url.lastPathComponent?.let(onOutageTap)
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
            webView.loadHTMLString(html, baseURL = NSURL(string = "https://outagewatch.local/"))
        },
    )
}
