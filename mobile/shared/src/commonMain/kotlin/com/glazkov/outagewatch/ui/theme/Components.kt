package com.glazkov.outagewatch.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Large iOS-style navigation title. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    val c = LocalCompass.current
    Text(
        text,
        color = c.label,
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp),
    )
}

/** Uppercase grouped-list section header. */
@Composable
fun SectionHeader(text: String) {
    val c = LocalCompass.current
    Text(
        text.uppercase(),
        color = c.secondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 6.dp),
    )
}

/**
 * A rounded inset card that hosts a column of [Cell]s. The caller places Cells
 * directly and passes showSeparator = false on the last one.
 */
@Composable
fun GroupedSection(content: @Composable () -> Unit) {
    val c = LocalCompass.current
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.card),
    ) {
        content()
    }
}

/**
 * One grouped-list row: an optional leading icon tile, a title with optional
 * subtitle, and a trailing status label and/or chevron.
 */
@Composable
fun Cell(
    title: String,
    subtitle: String? = null,
    leadingEmoji: String? = null,
    leadingTint: Color? = null,
    trailing: String? = null,
    trailingColor: Color? = null,
    chevron: Boolean = false,
    showSeparator: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalCompass.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingEmoji != null) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                        .background(leadingTint ?: c.separator),
                    contentAlignment = Alignment.Center,
                ) { Text(leadingEmoji, fontSize = 15.sp) }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = c.label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (subtitle != null) {
                    Text(subtitle, color = c.secondary, fontSize = 13.sp)
                }
            }
            if (trailing != null) {
                Text(
                    trailing,
                    color = trailingColor ?: c.secondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (chevron) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = c.tertiary,
                )
            }
        }
        if (showSeparator) {
            Box(
                Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(c.separator)
            )
        }
    }
}

/** Footer/disclaimer text under a section. */
@Composable
fun GroupedFootnote(text: String) {
    val c = LocalCompass.current
    Text(
        text,
        color = c.secondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
    )
}
