package com.poyka.ripdpi.services

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import javax.inject.Inject
import javax.inject.Singleton

private const val KernelMajorWithUnprivilegedBindToDevice = 5
private const val KernelMinorWithUnprivilegedBindToDevice = 7

/**
 * Process-cached capability gate for the native per-UID TUN admission policy.
 *
 * Android 10 is the platform floor because flow attribution depends on
 * `ConnectivityManager.getConnectionOwnerUid`. Linux 5.7 removed the
 * privileged-only restriction from `SO_BINDTODEVICE`; the runtime probe also
 * admits vendor backports on older kernels. Android 12+ is the conservative
 * fallback when the kernel release cannot be parsed.
 */
@Singleton
class SoBindToDeviceUidPolicyEligibility private constructor(
    private val sdkInt: () -> Int,
    private val kernelRelease: () -> String?,
    private val probe: () -> Boolean,
) {
    @Inject
    constructor() : this(
        sdkInt = { Build.VERSION.SDK_INT },
        kernelRelease = { runCatching { System.getProperty("os.version") }.getOrNull() },
        probe = ::probeUnprivilegedBindToDevice,
    )

    private val cachedEligibility by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val sdk = sdkInt()
        if (sdk < Build.VERSION_CODES.Q) {
            false
        } else {
            val kernel = parseKernelVersion(kernelRelease())
            val probeSucceeded = runCatching(probe).getOrDefault(false)
            probeSucceeded ||
                kernel?.supportsUnprivilegedBindToDevice() == true ||
                (kernel == null && sdk >= Build.VERSION_CODES.S)
        }
    }

    fun isEligible(): Boolean = cachedEligibility

    internal companion object {
        fun forTest(
            sdkInt: Int,
            kernelRelease: String?,
            probe: () -> Boolean,
        ): SoBindToDeviceUidPolicyEligibility =
            SoBindToDeviceUidPolicyEligibility(
                sdkInt = { sdkInt },
                kernelRelease = { kernelRelease },
                probe = probe,
            )
    }
}

private data class KernelVersion(
    val major: Int,
    val minor: Int,
) {
    fun supportsUnprivilegedBindToDevice(): Boolean =
        major > KernelMajorWithUnprivilegedBindToDevice ||
            (major == KernelMajorWithUnprivilegedBindToDevice && minor >= KernelMinorWithUnprivilegedBindToDevice)
}

private fun parseKernelVersion(release: String?): KernelVersion? =
    kernelReleasePattern.find(release.orEmpty())?.let { match ->
        val major = match.groupValues[1].toIntOrNull()
        val minor = match.groupValues[2].toIntOrNull()
        if (major != null && minor != null) KernelVersion(major, minor) else null
    }

private fun probeUnprivilegedBindToDevice(): Boolean =
    probeUnprivilegedBindToDevice(
        openSocket = { Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, OsConstants.IPPROTO_TCP) },
        bindSocket = { socket ->
            setsockoptIfreqMethod.invoke(
                null,
                socket,
                OsConstants.SOL_SOCKET,
                OsConstants.SO_BINDTODEVICE,
                "lo",
            )
        },
        closeSocket = Os::close,
    )

internal fun probeUnprivilegedBindToDevice(
    openSocket: () -> FileDescriptor,
    bindSocket: (FileDescriptor) -> Unit,
    closeSocket: (FileDescriptor) -> Unit,
): Boolean {
    val socket = runCatching(openSocket).getOrNull() ?: return false
    val bindSucceeded = runCatching { bindSocket(socket) }.isSuccess
    val closeSucceeded = runCatching { closeSocket(socket) }.isSuccess
    return bindSucceeded && closeSucceeded
}

private val setsockoptIfreqMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    Os::class.java.getMethod(
        "setsockoptIfreq",
        FileDescriptor::class.java,
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        String::class.java,
    )
}

private val kernelReleasePattern = Regex("^(\\d+)\\.(\\d+)(?:[.\\-+_]|$)")
