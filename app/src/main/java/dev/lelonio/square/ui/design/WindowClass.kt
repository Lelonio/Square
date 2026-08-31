package dev.lelonio.square.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Lightweight adaptive breakpoints matching Android's compact/medium/expanded
 * width guidance without adding another UI dependency.
 */
@Immutable
enum class SquareWindowClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

@Composable
fun rememberSquareWindowClass(): SquareWindowClass {
    val width = LocalConfiguration.current.screenWidthDp
    return remember(width) {
        when {
            width < 600 -> SquareWindowClass.COMPACT
            width < 840 -> SquareWindowClass.MEDIUM
            else -> SquareWindowClass.EXPANDED
        }
    }
}

fun SquareWindowClass.contentMaxWidth() = when (this) {
    SquareWindowClass.COMPACT -> SquareUiTokens.ContentMaxPhone
    SquareWindowClass.MEDIUM -> SquareUiTokens.ContentMaxPhone
    SquareWindowClass.EXPANDED -> SquareUiTokens.ContentMaxLarge
}

fun SquareWindowClass.gridColumns(minItemWidthDp: Int = 168): Int {
    val width = when (this) {
        SquareWindowClass.COMPACT -> 360
        SquareWindowClass.MEDIUM -> 600
        SquareWindowClass.EXPANDED -> 840
    }
    return (width / minItemWidthDp).coerceIn(2, 6)
}
