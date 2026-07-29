package com.glazkov.outagewatch.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The status tiles draw vectors rather than emoji on purpose. Compose on iOS has
 * no glyph for U+26A1 (the lightning bolt) in any form, with or without a
 * variation selector, so it rendered as a missing-glyph box; U+26A0 resolved to
 * a monochrome symbol there while Android drew a colour emoji. Vectors render
 * identically on both platforms and match the app icon's bolt.
 */
val OutageBolt: ImageVector by lazy {
    ImageVector.Builder(
        name = "OutageBolt",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // The same path as the Android notification icon, so every bolt in the
        // product is the same shape.
        path(fill = SolidColor(Color.Black)) {
            moveTo(13f, 2f)
            lineTo(4.5f, 13f)
            horizontalLineTo(10.5f)
            lineTo(9f, 22f)
            lineTo(19.5f, 9.5f)
            horizontalLineTo(12.5f)
            close()
        }
    }.build()
}
