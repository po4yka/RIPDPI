package com.poyka.ripdpi.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class StrategyConfigImportTest {
    @Test
    fun readLimitedUtf8TextReturnsTextWithinLimit() {
        val text = readLimitedUtf8Text(ByteArrayInputStream("version: 1".toByteArray()), maxBytes = 32)

        assertEquals("version: 1", text)
    }

    @Test
    fun readLimitedUtf8TextRejectsFilesOverLimit() {
        assertThrows(StrategyConfigImportException.FileTooLarge::class.java) {
            readLimitedUtf8Text(ByteArrayInputStream("12345".toByteArray()), maxBytes = 4)
        }
    }

    @Test
    fun readLimitedUtf8TextRejectsBlankFiles() {
        assertThrows(StrategyConfigImportException.EmptyFile::class.java) {
            readLimitedUtf8Text(ByteArrayInputStream("  \n".toByteArray()), maxBytes = 32)
        }
    }
}
