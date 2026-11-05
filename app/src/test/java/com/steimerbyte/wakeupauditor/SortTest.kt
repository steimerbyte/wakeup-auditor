package com.steimerbyte.wakeupauditor

import org.junit.Assert.assertEquals
import org.junit.Test

/** Prüft die vier Sortierachsen der Rangliste. */
class SortTest {

    private fun row(pkg: String, jobs: Int = 0, alarms: Int = 0, bgMs: Long = 0) =
        AppRow(pkg, null, jobs, alarms, fgMs = 0, bgMs = bgMs, fgsMs = 0, cachedMs = 0)

    // Punkte: a=1×3+1×2+1m=6 · b=5×3+0+0=15 · c=0+6×2+2m=14 · d=e=0
    private val rows = listOf(
        row("a", jobs = 1, alarms = 1, bgMs = 60_000),
        row("b", jobs = 5),
        row("c", alarms = 6, bgMs = 120_000),
        row("e"),
        row("d")
    )

    private fun order(axis: Sort) = rows.rankedBy(axis).map { it.pkg }

    @Test
    fun sortsByPoints() = assertEquals(listOf("b", "c", "a", "d", "e"), order(Sort.POINTS))

    @Test
    fun sortsByJobs() = assertEquals(listOf("b", "a", "c", "d", "e"), order(Sort.JOBS))

    @Test
    fun sortsByAlarms() = assertEquals(listOf("c", "a", "b", "d", "e"), order(Sort.ALARME))

    @Test
    fun sortsByMinutes() = assertEquals(listOf("c", "a", "b", "d", "e"), order(Sort.MINUTEN))

    @Test
    fun minutesIgnorePartialMinutes() {
        // 59_999 ms sind 0 volle Minuten und dürfen nicht als 1 zählen.
        val r = row("x", bgMs = 59_999)
        assertEquals(0L, r.minutes)
        assertEquals(0, r.score)
    }
}
