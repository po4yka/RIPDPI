package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticsArchiveFailureTest {
    @Test
    fun `archive failure codes are stable and contain no exception detail`() {
        val codes = DiagnosticsArchiveFailureCode.entries.map { it.supportCode }

        assertEquals(
            listOf(
                "archive_storage",
                "archive_io",
                "archive_database",
                "archive_inconsistent_result",
            ),
            codes,
        )
        val failure =
            DiagnosticsArchiveException(
                DiagnosticsArchiveFailureCode.STORAGE,
                IllegalStateException("/data/user/0/private"),
            )
        assertFalse(failure.message.orEmpty().contains("/data/user/0/private"))
    }
}
