package com.poyka.ripdpi.services

import android.os.Build
import com.poyka.ripdpi.core.TunDeviceQualificationNativeBindings
import javax.inject.Inject
import javax.inject.Singleton

private const val KernelMajorWithUnprivilegedBindToDevice = 5
private const val KernelMinorWithUnprivilegedBindToDevice = 7
private const val NativeProbeUnavailable = 0
private const val NativeProbeSupported = 1
private const val NativeProbePermissionDenied = 2
private const val NativeProbeBridgeFailure = -1

internal enum class BindToDeviceProbeOutcome(
    val wireValue: String,
) {
    NotSupported("not_supported"),
    Supported("supported"),
    PermissionDenied("permission_denied"),
    BridgeFailure("bridge_failure"),
    Unavailable("unavailable"),
}

data class RemoteDeviceUidPolicyQualification(
    val kernelMajorMinorBand: String = "unknown",
    val unprivilegedBindToDevice: String = BindToDeviceProbeOutcome.Unavailable.wireValue,
    val uidPolicyEligible: Boolean = false,
    val uidPolicyArmed: Boolean = false,
    val uidResolvedCount: Long = 0,
    val uidUnresolvedCount: Long = 0,
    val policyDecisionDeniedTcpCount: Long = 0,
    val policyDecisionDeniedUdpCount: Long = 0,
    val policyDecisionDeniedOtherCount: Long = 0,
)

internal fun RemoteDeviceUidPolicyQualification.privacySafe(): RemoteDeviceUidPolicyQualification =
    copy(
        kernelMajorMinorBand =
            kernelMajorMinorBand.takeIf { value -> kernelMajorMinorPattern.matches(value) }
                ?: "unknown",
        unprivilegedBindToDevice =
            unprivilegedBindToDevice.takeIf { value -> value in bindToDeviceProbeWireValues }
                ?: BindToDeviceProbeOutcome.Unavailable.wireValue,
        uidResolvedCount = uidResolvedCount.coerceAtLeast(0),
        uidUnresolvedCount = uidUnresolvedCount.coerceAtLeast(0),
        policyDecisionDeniedTcpCount = policyDecisionDeniedTcpCount.coerceAtLeast(0),
        policyDecisionDeniedUdpCount = policyDecisionDeniedUdpCount.coerceAtLeast(0),
        policyDecisionDeniedOtherCount = policyDecisionDeniedOtherCount.coerceAtLeast(0),
    )

/** Process-cached capability evidence and gate for native per-UID TUN admission. */
@Singleton
class SoBindToDeviceUidPolicyEligibility private constructor(
    private val sdkInt: () -> Int,
    private val kernelRelease: () -> String?,
    private val probe: () -> BindToDeviceProbeOutcome,
) {
    @Inject
    constructor(nativeBindings: TunDeviceQualificationNativeBindings) : this(
        sdkInt = { Build.VERSION.SDK_INT },
        kernelRelease = { runCatching { System.getProperty("os.version") }.getOrNull() },
        probe = {
            try {
                nativeBindings.probeUnprivilegedBindToDevice().toProbeOutcome()
            } catch (_: UnsatisfiedLinkError) {
                BindToDeviceProbeOutcome.Unavailable
            }
        },
    )

    private val cachedQualification by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val sdk = sdkInt()
        val kernel = parseKernelVersion(kernelRelease())
        val probeOutcome =
            if (sdk < Build.VERSION_CODES.Q) {
                BindToDeviceProbeOutcome.NotSupported
            } else {
                probe()
            }
        RemoteDeviceUidPolicyQualification(
            kernelMajorMinorBand = kernel?.majorMinorBand ?: "unknown",
            unprivilegedBindToDevice = probeOutcome.wireValue,
            uidPolicyEligible =
                when {
                    sdk < Build.VERSION_CODES.Q -> {
                        false
                    }

                    probeOutcome == BindToDeviceProbeOutcome.PermissionDenied ||
                        probeOutcome == BindToDeviceProbeOutcome.BridgeFailure -> {
                        false
                    }

                    probeOutcome == BindToDeviceProbeOutcome.Supported -> {
                        true
                    }

                    else -> {
                        kernel?.supportsUnprivilegedBindToDevice() == true ||
                            (kernel == null && sdk >= Build.VERSION_CODES.S)
                    }
                },
        )
    }

    fun isEligible(): Boolean {
        val qualification = cachedQualification
        check(qualification.unprivilegedBindToDevice != BindToDeviceProbeOutcome.BridgeFailure.wireValue) {
            "Native UID policy qualification failed"
        }
        return qualification.uidPolicyEligible
    }

    fun qualification(): RemoteDeviceUidPolicyQualification = cachedQualification

    internal companion object {
        fun forTest(
            sdkInt: Int,
            kernelRelease: String?,
            probe: () -> BindToDeviceProbeOutcome,
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
    val majorMinorBand: String = "$major.$minor"

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

internal fun Int.toProbeOutcome(): BindToDeviceProbeOutcome =
    when (this) {
        NativeProbeSupported -> BindToDeviceProbeOutcome.Supported
        NativeProbePermissionDenied -> BindToDeviceProbeOutcome.PermissionDenied
        NativeProbeUnavailable -> BindToDeviceProbeOutcome.Unavailable
        NativeProbeBridgeFailure -> BindToDeviceProbeOutcome.BridgeFailure
        else -> BindToDeviceProbeOutcome.Unavailable
    }

private val bindToDeviceProbeWireValues = BindToDeviceProbeOutcome.entries.mapTo(mutableSetOf()) { it.wireValue }
private val kernelMajorMinorPattern = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
private val kernelReleasePattern = Regex("^(\\d+)\\.(\\d+)(?:[.\\-+_]|$)")
