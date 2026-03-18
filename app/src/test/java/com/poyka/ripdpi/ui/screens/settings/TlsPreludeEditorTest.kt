package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.activities.TlsPreludeUiState
import com.poyka.ripdpi.data.DefaultTlsRandRecFragmentCount
import com.poyka.ripdpi.data.DefaultTlsRandRecMaxFragmentSize
import com.poyka.ripdpi.data.DefaultTlsRandRecMinFragmentSize
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TlsPreludeEditorTest {
    @Test
    fun `disabled mode removes tls preludes and preserves send steps`() {
        val state =
            SettingsUiState(
                tcpChainSteps =
                    listOf(
                        TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                        TcpChainStepModel(
                            kind = TcpChainStepKind.TlsRandRec,
                            marker = "sniext+4",
                            fragmentCount = 5,
                            minFragmentSize = 24,
                            maxFragmentSize = 48,
                        ),
                        TcpChainStepModel(
                            kind = TcpChainStepKind.HostFake,
                            marker = "endhost+8",
                            fakeHostTemplate = "googlevideo.com",
                        ),
                        TcpChainStepModel(TcpChainStepKind.Split, "midsld"),
                    ),
            )

        val updated = state.rewriteTlsPreludeChainForEditor(mode = TlsPreludeModeDisabled)

        assertEquals(
            listOf(
                TcpChainStepModel(
                    kind = TcpChainStepKind.HostFake,
                    marker = "endhost+8",
                    fakeHostTemplate = "googlevideo.com",
                ),
                TcpChainStepModel(TcpChainStepKind.Split, "midsld"),
            ),
            updated,
        )
    }

    @Test
    fun `single split mode creates tlsrec step with normalized marker`() {
        val state =
            SettingsUiState(
                tcpChainSteps = listOf(TcpChainStepModel(TcpChainStepKind.Split, "host+1")),
                tlsPrelude = TlsPreludeUiState(tlsrecMarker = "sniext+4"),
            )

        val updated =
            state.rewriteTlsPreludeChainForEditor(
                mode = TcpChainStepKind.TlsRec.wireName,
                marker = "   ",
            )

        assertEquals(
            listOf(
                TcpChainStepModel(TcpChainStepKind.TlsRec, "0"),
                TcpChainStepModel(TcpChainStepKind.Split, "host+1"),
            ),
            updated,
        )
    }

    @Test
    fun `randomized split mode creates tlsrandrec step with default fragment knobs`() {
        val state =
            SettingsUiState(
                tcpChainSteps = listOf(TcpChainStepModel(TcpChainStepKind.Split, "host+1")),
                tlsPrelude = TlsPreludeUiState(tlsrecMarker = "extlen"),
            )

        val updated =
            state.rewriteTlsPreludeChainForEditor(
                mode = TcpChainStepKind.TlsRandRec.wireName,
                fragmentCount = 0,
                minFragmentSize = 0,
                maxFragmentSize = 0,
            )

        assertEquals(
            listOf(
                TcpChainStepModel(
                    kind = TcpChainStepKind.TlsRandRec,
                    marker = "extlen",
                    fragmentCount = DefaultTlsRandRecFragmentCount,
                    minFragmentSize = DefaultTlsRandRecMinFragmentSize,
                    maxFragmentSize = DefaultTlsRandRecMaxFragmentSize,
                ),
                TcpChainStepModel(TcpChainStepKind.Split, "host+1"),
            ),
            updated,
        )
    }

    @Test
    fun `disabled mode does not create an editor step`() {
        val state = SettingsUiState()

        assertNull(state.toTlsPreludeEditorStep(mode = TlsPreludeModeDisabled))
    }
}
