package com.poyka.ripdpi.services

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

private const val ProxySocketPrefix = "px-"
private const val ProxySocketRandomBytes = 16
private const val MaxSockaddrPathBytes = 108
private val ProxySocketName = Regex("px-[0-9a-f]{32}")
private val ProxySocketRandom = SecureRandom()
private val IssuedProxySocketPaths = ConcurrentHashMap.newKeySet<String>()

internal fun isProxyUnixPath(path: String): Boolean =
    path.startsWith("/") && '\u0000' !in path && path.toByteArray(Charsets.UTF_8).size < MaxSockaddrPathBytes

internal fun newProxySocketPath(directory: File): String {
    val bytes = ByteArray(ProxySocketRandomBytes).also(ProxySocketRandom::nextBytes)
    val path = File(directory, ProxySocketPrefix + bytes.joinToString("") { "%02x".format(it) }).absolutePath
    if (!isProxyUnixPath(path)) {
        throw SupervisorStartupFailureException(
            SupervisorExitCause.StartupFailure(IOException("Proxy socket path does not fit a socket address")),
        )
    }
    IssuedProxySocketPaths += path
    return path
}

internal fun sweepStaleProxySockets(directory: File) {
    directory
        .listFiles { entry ->
            ProxySocketName.matches(entry.name) &&
                !entry.isDirectory &&
                !Files.isSymbolicLink(entry.toPath()) &&
                entry.absolutePath !in IssuedProxySocketPaths
        }?.forEach { it.delete() }
}
