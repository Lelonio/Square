package dev.lelonio.square.playback

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackOperationGateTest {
    @Test
    fun newerOperationInvalidatesOlderCallback() {
        val gate = PlaybackOperationGate()
        val first = gate.begin()
        val second = gate.begin()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun invalidateMakesSuspendedOperationStale() {
        val gate = PlaybackOperationGate()
        val operation = gate.begin()

        gate.invalidate()

        assertFalse(gate.isCurrent(operation))
    }

    @Test
    fun exclusiveSerializesMutations() = runBlocking {
        val gate = PlaybackOperationGate()
        val order = mutableListOf<Int>()

        val first = async {
            gate.exclusive {
                order += 1
                delay(10)
                order += 2
            }
        }
        val second = async {
            gate.exclusive {
                order += 3
                order += 4
            }
        }

        first.await()
        second.await()

        assertEquals(listOf(1, 2, 3, 4), order)
    }
}
