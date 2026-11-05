package com.steimerbyte.wakeupauditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prüft die beiden neuen Parser: AppOps-Modus und /proc-Probe. */
class ProcAndOpsTest {

    // ---------- AppOps ----------

    @Test
    fun readsExplicitMode() {
        assertEquals(false, parseBackgroundMode("RUN_ANY_IN_BACKGROUND: ignore\n"))
        assertEquals(true, parseBackgroundMode("RUN_ANY_IN_BACKGROUND: allow\n"))
    }

    @Test
    fun readsDefaultMode() {
        // Ungesetzt: cmd appops get gibt die Systemvorgabe aus, hier allow.
        assertEquals(true, parseBackgroundMode("Default mode: allow\n"))
    }

    @Test
    fun unknownAnswerIsNull() {
        assertNull(parseBackgroundMode(""))
        assertNull(parseBackgroundMode("No operations."))
        assertEquals(false, parseBackgroundMode("RUN_ANY_IN_BACKGROUND: deny"))
    }

    // ---------- /proc ----------

    private val proc = """
        cpu  123456 789 98765 4567890 12345 678 0 0 0 0
        cpu0 61728 394 49382 2283945 6172 339 0 0 0 0
        MemTotal:       11680596 kB
        MemFree:         1234567 kB
        MemAvailable:    6305148 kB
    """.trimIndent()

    @Test
    fun parsesCpuAndRam() {
        val s = parseProcSample(proc)!!
        assertEquals(123456L + 789 + 98765 + 4567890 + 12345 + 678, s.total)
        assertEquals(4567890L + 12345, s.idle)
        val expectedUsed = (11680596L - 6305148L).toFloat() / 11680596L
        assertTrue(kotlin.math.abs(s.used - expectedUsed) < 0.001f)
        assertEquals("5,1/11,1 GiB", s.text)
    }

    @Test
    fun missingMeminfoStillGivesCpu() {
        val s = parseProcSample("cpu 1 2 3 4 5\n")!!
        assertEquals(15L, s.total)
        assertEquals(9L, s.idle)
        assertNull(s.text)
    }

    @Test
    fun garbageIsNull() {
        assertNull(parseProcSample(""))
        assertNull(parseProcSample("cpu 1 2 3\n"))
    }
}
