package com.poyka.ripdpi.activities

import androidx.annotation.StringRes
import com.poyka.ripdpi.R

@get:StringRes
val DpiFailureClass.displayLabelRes: Int
    get() =
        when (this) {
            DpiFailureClass.TCP_RESET -> R.string.diagnostics_dpi_failure_tcp_reset
            DpiFailureClass.SILENT_DROP -> R.string.diagnostics_dpi_failure_silent_drop
            DpiFailureClass.TLS_ALERT -> R.string.diagnostics_dpi_failure_tls_alert
            DpiFailureClass.HTTP_BLOCKPAGE -> R.string.diagnostics_dpi_failure_http_blockpage
            DpiFailureClass.QUIC_BREAKAGE -> R.string.diagnostics_dpi_failure_quic_breakage
            DpiFailureClass.TLS_HANDSHAKE_FAILURE -> R.string.diagnostics_dpi_failure_tls_handshake_failure
            DpiFailureClass.CONNECTION_FREEZE -> R.string.diagnostics_dpi_failure_connection_freeze
            DpiFailureClass.REDIRECT -> R.string.diagnostics_dpi_failure_redirect
            DpiFailureClass.OTHER -> R.string.diagnostics_dpi_failure_other
        }
