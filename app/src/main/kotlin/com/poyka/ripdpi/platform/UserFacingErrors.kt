package com.poyka.ripdpi.platform

import androidx.annotation.StringRes
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

private const val MaxCauseDepth = 8

/**
 * Maps a failure to a localized message class for the UI. Raw exception text is
 * not user copy: it stays in the logs (see [userMessage]).
 */
@StringRes
fun Throwable.userMessageRes(
    @StringRes fallback: Int = R.string.error_user_generic,
): Int {
    val chain = generateSequence(this) { it.cause }.take(MaxCauseDepth).toList()
    return when {
        chain.any { it is SocketTimeoutException || it is TimeoutException } -> R.string.error_user_timeout
        chain.any { it.isNetworkFailure() } -> R.string.error_user_no_network
        chain.any { it is SSLException || it is CertificateException } -> R.string.error_user_tls
        chain.any { it is SecurityException } -> R.string.error_user_permission
        else -> fallback
    }
}

private fun Throwable.isNetworkFailure(): Boolean =
    // ConnectException (refused) is not a network outage: the target or local port is down.
    this is UnknownHostException || this is NoRouteToHostException

/** Logs [error] with its raw text and returns the localized message for the UI. */
fun StringResolver.userMessage(
    error: Throwable,
    @StringRes fallback: Int = R.string.error_user_generic,
): String {
    Logger.w(error) { "Operation failed: ${error.javaClass.simpleName}" }
    return getString(error.userMessageRes(fallback))
}
