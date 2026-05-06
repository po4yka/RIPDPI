package com.poyka.ripdpi.activities

val DpiFailureClass.displayLabel: String
    get() =
        when (this) {
            DpiFailureClass.TCP_RESET -> "TCP Reset"
            DpiFailureClass.SILENT_DROP -> "Silent Drop"
            DpiFailureClass.TLS_ALERT -> "TLS Alert"
            DpiFailureClass.HTTP_BLOCKPAGE -> "HTTP Blockpage"
            DpiFailureClass.QUIC_BREAKAGE -> "QUIC Breakage"
            DpiFailureClass.TLS_HANDSHAKE_FAILURE -> "TLS Handshake Failure"
            DpiFailureClass.CONNECTION_FREEZE -> "Connection Freeze"
            DpiFailureClass.REDIRECT -> "Redirect"
            DpiFailureClass.OTHER -> "Unknown DPI"
        }
