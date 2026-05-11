package com.poyka.ripdpi.diagnostics.dpi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpiDiagnosticsRuntimeFlagsTest {
    @Test
    fun includeQuicDefaultsToEnabled() {
        withClearedIncludeQuicProperty {
            assertTrue(DpiDiagnosticsRuntimeFlags.includeQuic)
        }
    }

    @Test
    fun includeQuicCanBeDisabledBySystemProperty() {
        withIncludeQuicProperty("false") {
            assertFalse(DpiDiagnosticsRuntimeFlags.includeQuic)
        }
    }

    @Test
    fun malformedIncludeQuicPropertyFallsBackToEnabled() {
        withIncludeQuicProperty("definitely") {
            assertTrue(DpiDiagnosticsRuntimeFlags.includeQuic)
        }
    }

    private fun withClearedIncludeQuicProperty(block: () -> Unit) {
        val previous = System.getProperty(IncludeQuicProperty)
        try {
            System.clearProperty(IncludeQuicProperty)
            block()
        } finally {
            restoreProperty(previous)
        }
    }

    private fun withIncludeQuicProperty(
        value: String,
        block: () -> Unit,
    ) {
        val previous = System.getProperty(IncludeQuicProperty)
        try {
            System.setProperty(IncludeQuicProperty, value)
            block()
        } finally {
            restoreProperty(previous)
        }
    }

    private fun restoreProperty(previous: String?) {
        if (previous == null) {
            System.clearProperty(IncludeQuicProperty)
        } else {
            System.setProperty(IncludeQuicProperty, previous)
        }
    }

    private companion object {
        private const val IncludeQuicProperty = "dpi.diagnostics.includeQuic"
    }
}
