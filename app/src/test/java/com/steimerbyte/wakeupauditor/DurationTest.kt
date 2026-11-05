package com.steimerbyte.wakeupauditor

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationTest {

    @Test
    fun parsesDumpsysDurations() {
        assertEquals(5_392_161L, parseDuration("1h 29m 52s 161ms"))
        assertEquals(1_629_202L, parseDuration("27m 9s 202ms"))
        assertEquals(136L, parseDuration("136ms"))
        assertEquals(65L, parseDuration("65ms"))
        assertEquals(0L, parseDuration(""))
    }

    @Test
    fun formatsRoundTrip() {
        assertEquals("1h 29m 52s 161ms", formatDuration(5_392_161L))
        assertEquals("27m 9s 202ms", formatDuration(1_629_202L))
        assertEquals("136ms", formatDuration(136L))
        assertEquals("0ms", formatDuration(0L))
    }
}
