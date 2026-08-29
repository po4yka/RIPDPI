package com.poyka.ripdpi.services

import kotlinx.coroutines.CancellationException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException

/** Opaque platform failures may contain a CT/trust rejection, so they are terminal. */
internal fun Throwable.permitsOwnedStackTransportFallback(): Boolean {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    var transportFailure = false
    while (current != null && seen.add(current)) {
        when (current) {
            is CancellationException, is SSLException, is GeneralSecurityException, is SecurityException -> {
                return false
            }

            is ConnectException, is NoRouteToHostException, is SocketTimeoutException, is UnknownHostException -> {
                transportFailure = true
            }
        }
        current = current.cause
    }
    return current == null && transportFailure
}
