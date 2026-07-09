package com.glazkov.outagewatch

import androidx.compose.ui.window.ComposeUIViewController
import com.glazkov.outagewatch.ui.App
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController { App() }
