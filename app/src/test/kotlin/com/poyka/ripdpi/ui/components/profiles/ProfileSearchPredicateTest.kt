package com.poyka.ripdpi.ui.components.profiles

import com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel
import com.poyka.ripdpi.activities.RelayProfileUiState
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.ui.screens.config.filterRelayProfiles
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticProfileSearchEntry
import com.poyka.ripdpi.ui.screens.diagnostics.filterDiagnosticProfiles
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSearchPredicateTest {
    private val relays =
        listOf(
            RelayProfileUiState("same-prefix-a", "vless", "VLESS", "Germany", "Local operator"),
            RelayProfileUiState("same-prefix-b", "ssh", "SSH", "France", "Other operator"),
            RelayProfileUiState("unknown-id", "future_kind", "Future kind", "", ""),
        )

    @Test fun `relay query trims ignores case and combines terms across visible fields`() {
        assertEquals(listOf(relays[0]), filterRelayProfiles(relays, "  SAME-PREFIX   germany  ", null))
        assertEquals(listOf(relays[1]), filterRelayProfiles(relays, "other SSH", null))
        assertEquals(emptyList<RelayProfileUiState>(), filterRelayProfiles(relays, "Germany", "ssh"))
    }

    @Test fun `blank query preserves order and kind filter does not select a fallback`() {
        assertEquals(relays, filterRelayProfiles(relays, "  ", null))
        assertEquals(listOf(relays[1]), filterRelayProfiles(relays, "", "ssh"))
        assertEquals(emptyList<RelayProfileUiState>(), filterRelayProfiles(relays, "", "removed-kind"))
    }

    @Test fun `unknown kind remains searchable with exact unchanged identity`() {
        assertEquals(listOf(relays[2]), filterRelayProfiles(relays, "future", "future_kind"))
        assertEquals("unknown-id", filterRelayProfiles(relays, "unknown-id", null).single().id)
    }

    @Test fun `diagnostics searches displayed description source and family with AND filter`() {
        val web =
            DiagnosticProfileSearchEntry(
                DiagnosticsProfileOptionUiModel(
                    "web",
                    "Web check",
                    "bundled",
                    family = DiagnosticProfileFamily.WEB_CONNECTIVITY,
                ),
                "Website connectivity",
                "Tests public websites",
            )
        val messaging =
            DiagnosticProfileSearchEntry(
                DiagnosticsProfileOptionUiModel(
                    "messages",
                    "Chat check",
                    "imported",
                    family = DiagnosticProfileFamily.MESSAGING,
                ),
                "Messaging",
                "Tests message delivery",
            )
        val entries = listOf(web, messaging)
        assertEquals(listOf(web), filterDiagnosticProfiles(entries, " PUBLIC bundled ", null))
        assertEquals(listOf(messaging), filterDiagnosticProfiles(entries, "delivery messaging", "MESSAGING"))
        assertEquals(
            emptyList<DiagnosticProfileSearchEntry>(),
            filterDiagnosticProfiles(entries, "websites", "MESSAGING"),
        )
        assertEquals(entries, filterDiagnosticProfiles(entries, "", null))
    }
}
