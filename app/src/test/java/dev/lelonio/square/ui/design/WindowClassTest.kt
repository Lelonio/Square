package dev.lelonio.square.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowClassTest {
    @Test
    fun compactMediumExpandedBreakpointsAreDeterministic() {
        assertEquals(SquareWindowClass.COMPACT, classify(599))
        assertEquals(SquareWindowClass.MEDIUM, classify(600))
        assertEquals(SquareWindowClass.MEDIUM, classify(839))
        assertEquals(SquareWindowClass.EXPANDED, classify(840))
    }

    @Test
    fun gridColumnsStayBounded() {
        assertEquals(2, SquareWindowClass.COMPACT.gridColumns())
        assertEquals(3, SquareWindowClass.MEDIUM.gridColumns())
        assertEquals(5, SquareWindowClass.EXPANDED.gridColumns())
    }

    private fun classify(widthDp: Int): SquareWindowClass = when {
        widthDp < 600 -> SquareWindowClass.COMPACT
        widthDp < 840 -> SquareWindowClass.MEDIUM
        else -> SquareWindowClass.EXPANDED
    }
}
