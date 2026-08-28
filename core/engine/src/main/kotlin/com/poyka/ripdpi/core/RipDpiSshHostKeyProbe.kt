package com.poyka.ripdpi.core

import javax.inject.Inject

private const val ProbeObserved = 0
private const val ProbeInvalidInput = 1
private const val ProbeTimeout = 2
private const val ProbeConnectFailed = 3
private const val ProbeHandshakeFailed = 4
private const val ProbeProtectionDenied = 5
private val Sha256Fingerprint = Regex("SHA256:[A-Za-z0-9+/]{43}")
private val HostKeyAlgorithm = Regex("[A-Za-z0-9@._+-]{1,128}")

interface RipDpiSshHostKeyBindings {
    fun probeHostKey(
        addressLiteral: String,
        port: Int,
        timeoutMillis: Int,
        socketController: SshProbeSocketController,
        observationOut: Array<String?>,
    ): Int
}

/** Blocking, credential-free key exchange. The service owns DNS, permission and cancellation. */
class RipDpiSshHostKeyProbe(
    private val bindings: RipDpiSshHostKeyBindings,
) {
    @Inject
    constructor(bindings: RipDpiSshHostKeyNativeBindings) : this(bindings as RipDpiSshHostKeyBindings)

    fun probe(
        request: SshHostKeyProbeRequest,
        socketController: SshProbeSocketController,
    ): SshHostKeyProbeResult {
        val output = arrayOfNulls<String>(2)
        val status =
            bindings.probeHostKey(
                request.addressLiteral,
                request.port,
                request.timeoutMillis,
                socketController,
                output,
            )
        return if (status == ProbeObserved) {
            val fingerprint = output[0].orEmpty()
            val algorithm = output[1].orEmpty()
            if (Sha256Fingerprint.matches(fingerprint) && HostKeyAlgorithm.matches(algorithm)) {
                SshHostKeyProbeResult.Observed(fingerprint, algorithm)
            } else {
                SshHostKeyProbeResult.Failed(SshHostKeyProbeFailure.InternalFailure)
            }
        } else {
            SshHostKeyProbeResult.Failed(
                when (status) {
                    ProbeInvalidInput -> SshHostKeyProbeFailure.InvalidInput
                    ProbeTimeout -> SshHostKeyProbeFailure.Timeout
                    ProbeConnectFailed -> SshHostKeyProbeFailure.ConnectFailed
                    ProbeHandshakeFailed -> SshHostKeyProbeFailure.HandshakeFailed
                    ProbeProtectionDenied -> SshHostKeyProbeFailure.ProtectionDenied
                    else -> SshHostKeyProbeFailure.InternalFailure
                },
            )
        }
    }
}

class RipDpiSshHostKeyNativeBindings
    @Inject
    constructor() : RipDpiSshHostKeyBindings {
        override fun probeHostKey(
            addressLiteral: String,
            port: Int,
            timeoutMillis: Int,
            socketController: SshProbeSocketController,
            observationOut: Array<String?>,
        ): Int {
            RipDpiRelayNativeLoader.ensureLoaded()
            return jniProbeHostKey(addressLiteral, port, timeoutMillis, socketController, observationOut)
        }

        private external fun jniProbeHostKey(
            addressLiteral: String,
            port: Int,
            timeoutMillis: Int,
            socketController: Any,
            observationOut: Array<String?>,
        ): Int
    }
