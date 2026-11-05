package com.steimerbyte.wakeupauditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Prüft, wie ein Shell-Aufruf in einen Fehler mit stderr-Zeile übersetzt wird. */
class ShellResultTest {

    @Test
    fun usesStdout() {
        assertEquals("dumpsys output\n", ShellResult("dumpsys output\n", "warnung").required())
    }

    @Test
    fun blankOutputFailsWithStderrLine() {
        val e = assertThrows(IllegalStateException::class.java) {
            ShellResult("", "Error: Unknown command").required()
        }
        assertEquals("Error: Unknown command", e.message)
    }

    @Test
    fun blankOutputWithoutStderrFails() {
        val e = assertThrows(IllegalStateException::class.java) {
            ShellResult("\n", null).required()
        }
        assertEquals("Kommando lieferte keine Ausgabe", e.message)
    }
}
