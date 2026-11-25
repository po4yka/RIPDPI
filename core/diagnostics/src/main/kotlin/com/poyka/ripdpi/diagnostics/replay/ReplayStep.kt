package com.poyka.ripdpi.diagnostics.replay

enum class ReplayStepKind {
    DnsResolve,
    TcpOpen,
    TlsClientHello,
    TlsHandshake,
    FirstByte,
}

enum class ReplayStepStatus {
    Pending,
    Success,
    Failure,
}

enum class ReplayErrorKind {
    DnsTampered,
    ConnectionRefused,
    ConnectionReset,
    Timeout,
    TlsHandshakeFailed,
    Unknown,
}

enum class ReplayVerdict {
    Success,
    Failure,
    Cancelled,
}
