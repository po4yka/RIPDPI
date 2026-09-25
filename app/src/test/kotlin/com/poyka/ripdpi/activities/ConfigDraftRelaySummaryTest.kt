package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.RelayKindTor
import com.poyka.ripdpi.data.RelayKindVlessReality
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigDraftRelaySummaryTest {
    @Test
    fun `relay summary shows the selected kind`() {
        val draft = ConfigDraft(relayEnabled = true)
        assertEquals("VLESS + Reality", draft.copy(relayKind = RelayKindVlessReality).relaySummary)
        assertEquals("Tor", draft.copy(relayKind = RelayKindTor).relaySummary)
        assertEquals("future_kind", draft.copy(relayKind = "future_kind").relaySummary)
    }
}
