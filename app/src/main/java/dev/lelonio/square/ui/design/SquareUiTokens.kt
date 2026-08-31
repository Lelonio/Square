package dev.lelonio.square.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/** Centralized visual tokens for Phase 4. Values intentionally stay close to the existing glass language. */
@Immutable
object SquareUiTokens {
    val Space1 = 4.dp
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp
    val Space5 = 20.dp
    val Space6 = 24.dp
    val Space7 = 32.dp
    val Space8 = 40.dp

    val RadiusSmall = 12.dp
    val RadiusMedium = 18.dp
    val RadiusLarge = 26.dp
    val RadiusPill = 999.dp

    val TouchTarget = 48.dp
    val IconSmall = 18.dp
    val IconMedium = 24.dp
    val IconLarge = 30.dp

    val ContentMaxPhone = 720.dp
    val ContentMaxLarge = 1180.dp

    val CardShape = RoundedCornerShape(RadiusMedium)
    val SheetShape = RoundedCornerShape(topStart = RadiusLarge, topEnd = RadiusLarge)
}
