package com.steimerbyte.wakeupauditor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Prüft die Parser gegen die mitgeschnittenen Dumps (siehe raw/), falls vorhanden. */
class DumpCheckTest {

    private val dir = listOf("raw", "../raw", "../../raw")
        .map { File(it) }
        .firstOrNull { File(it, "jobs.txt").isFile }

    private fun f(n: String) = File(dir, n).readText()

    @Test
    fun parsersMatchTheDumps() {
        assumeTrue("raw/ fehlt", dir != null)
        val a = analyze(f("jobs.txt"), f("alarm.txt"), f("batt.txt"), f("whitelist.txt"), f("pkg-uid.txt"))
        val jobLines = f("jobs.txt").lineSequence().count { it.startsWith("  JOB ") }
        val alarmSum = f("alarm.txt").lineSequence()
            .mapNotNull { Regex("^    \\+\\S+ \\d+ wakes (\\d+) alarms").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .sum()
        val doze = f("whitelist.txt").lineSequence().filter { it.isNotBlank() }
            .map { it.split(',')[1] }.distinct().count()

        assertEquals(jobLines, a.jobsTotal)
        assertEquals(jobLines, a.rows.sumOf { it.jobs })
        assertEquals(alarmSum, a.alarmsTotal)
        assertEquals(alarmSum, a.rows.sumOf { it.alarms })
        assertEquals(doze, a.dozeCount)
        assertEquals(a.rows.maxOf { it.score }, a.rows.first().score)
    }
}
