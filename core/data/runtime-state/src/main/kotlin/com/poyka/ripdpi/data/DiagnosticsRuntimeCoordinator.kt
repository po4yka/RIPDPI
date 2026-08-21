package com.poyka.ripdpi.data

interface DiagnosticsRuntimeCoordinator {
    suspend fun runRawPathScan(block: suspend () -> Unit): RawPathExecutionResult

    suspend fun runAutomaticRawPathScan(block: suspend () -> Unit): RawPathExecutionResult

    suspend fun acquireInPathRouteLease(): DiagnosticsInPathRouteLease? = null

    fun isInPathRouteLeaseCurrent(lease: DiagnosticsInPathRouteLease): Boolean = false
}

data class DiagnosticsProxyCredentials(
    val username: String,
    val password: String,
) {
    init {
        require(username.isNotEmpty() && username.toByteArray().size <= MaxSocks5CredentialBytes) {
            "Diagnostics proxy username must fit SOCKS5 authentication bounds"
        }
        require(password.isNotEmpty() && password.toByteArray().size <= MaxSocks5CredentialBytes) {
            "Diagnostics proxy password must fit SOCKS5 authentication bounds"
        }
    }

    override fun toString(): String = "DiagnosticsProxyCredentials([REDACTED])"
}

data class DiagnosticsInPathRouteLease(
    val runtimeId: String,
    val routeGeneration: Long,
    val host: String,
    val port: Int,
    val credentials: DiagnosticsProxyCredentials,
) {
    init {
        require(runtimeId.isNotBlank()) { "Diagnostics route runtimeId must not be blank" }
        require(routeGeneration > 0) { "Diagnostics route generation must be positive" }
        require(host == "127.0.0.1" || host == "::1") { "Diagnostics route must use a loopback listener" }
        require(port in 1..MaxTcpPort) { "Diagnostics route port must be valid" }
    }

    override fun toString(): String =
        "DiagnosticsInPathRouteLease(runtimeId=[REDACTED], " +
            "routeGeneration=$routeGeneration, host=$host, port=$port, credentials=[REDACTED])"
}

private const val MaxSocks5CredentialBytes = 255
private const val MaxTcpPort = 65_535
