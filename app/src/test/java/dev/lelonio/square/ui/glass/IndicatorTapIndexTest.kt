package dev.lelonio.square.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class IndicatorTapIndexTest {

    @Test
    fun resolvesTapAgainstMovingIndicatorInLeftToRightLayout() {
        assertEquals(
            1,
            indicatorTapIndex(
                pointerX = 0.6f,
                indicatorPosition = 0.8f,
                tabWidth = 1f,
                tabCount = 5,
                isLtr = true,
            ),
        )
        assertEquals(
            2,
            indicatorTapIndex(
                pointerX = 1.6f,
                indicatorPosition = 0.8f,
                tabWidth = 1f,
                tabCount = 5,
                isLtr = true,
            ),
        )
    }

    @Test
    fun resolvesTapAgainstMovingIndicatorInRightToLeftLayout() {
        assertEquals(
            0,
            indicatorTapIndex(
                pointerX = 0.8f,
                indicatorPosition = 0.8f,
                tabWidth = 1f,
                tabCount = 5,
                isLtr = false,
            ),
        )
        assertEquals(
            1,
            indicatorTapIndex(
                pointerX = 0.2f,
                indicatorPosition = 0.8f,
                tabWidth = 1f,
                tabCount = 5,
                isLtr = false,
            ),
        )
    }

    @Test
    fun clampsCoordinatesOutsideTheTabRow() {
        assertEquals(
            0,
            indicatorTapIndex(
                pointerX = -8f,
                indicatorPosition = 0f,
                tabWidth = 1f,
                tabCount = 5,
                isLtr = true,
            ),
        )
        assertEquals(
            4,
            indicatorTapIndex(
                pointerX = 8f,
                indicatorPosition = 4f,
                tabWidth = 1f,
                tabCount = 5,
                isLtr = true,
            ),
        )
    }

    @Test
    fun degenerateWidthOrSingleTabReturnsTheOnlySafeIndex() {
        assertEquals(0, indicatorTapIndex(2f, 3f, 0f, 5, true))
        assertEquals(0, indicatorTapIndex(2f, 3f, 4f, 1, false))
    }
}
