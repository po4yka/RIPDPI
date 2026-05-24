package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.collections.immutable.persistentListOf

@Composable
fun ReplayFailureRoute(onBack: () -> Unit) {
    val steps =
        remember {
            persistentListOf(
                ReplayStep(
                    whenLabel = "12:18:43.012",
                    name = "DNS resolve",
                    detail = "answers in 8 ms · 1.1.1.1",
                    status = ReplayStepStatus.Success,
                ),
                ReplayStep(
                    whenLabel = "12:18:43.043",
                    name = "TCP open",
                    detail = "SYN+ACK in 31 ms · 142.250.190.78:443",
                    status = ReplayStepStatus.Success,
                ),
                ReplayStep(
                    whenLabel = "12:18:43.084",
                    name = "TLS ClientHello",
                    detail = "sent 517 B · SNI=youtube.com",
                    status = ReplayStepStatus.Success,
                ),
                ReplayStep(
                    whenLabel = "12:18:43.121",
                    name = "TLS reset",
                    detail = "RST received in 84 ms before ServerHello",
                    status = ReplayStepStatus.Failure,
                ),
            )
        }
    ReplayFailureScreen(
        timestampLabel = "12:18:43",
        probeSummary = "Probe replay · youtube.com · TLS ClientHello",
        steps = steps,
        recommendation = "Possible RST + SNI inspection · try tlsrec_split_host instead",
        onReplay = {},
        onBack = onBack,
    )
}
