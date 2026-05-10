package com.poyka.ripdpi.core.detection.debug

data class TunProbeDebugPath(
    val available: Boolean = false,
    val interfaceName: String? = null,
    val selectedMode: String? = null,
    val selectedIp: String? = null,
    val strictStatus: String? = null,
    val curlStatus: String? = null,
    val error: String? = null,
)

data class TunProbeDebugSnapshot(
    val debugEnabled: Boolean = false,
    val modeOverride: String? = null,
    val resolverDescription: String? = null,
    val activeNetworkIsVpn: Boolean = false,
    val vpnNetworkPresent: Boolean = false,
    val underlyingNetworkPresent: Boolean = false,
    val vpnPath: TunProbeDebugPath = TunProbeDebugPath(),
    val underlyingPath: TunProbeDebugPath = TunProbeDebugPath(),
)

object TunProbeDebugFormatter {
    fun formatSection(snapshot: TunProbeDebugSnapshot = TunProbeDebugSnapshot()): String =
        buildString {
            appendLine("[tunProbe]")
            appendLine("debugEnabled=${snapshot.debugEnabled}")
            appendLine("modeOverride=${snapshot.modeOverride.orEmpty()}")
            appendLine("resolverDescription=${snapshot.resolverDescription.orEmpty()}")
            appendLine("activeNetworkIsVpn=${snapshot.activeNetworkIsVpn}")
            appendLine("vpnNetworkPresent=${snapshot.vpnNetworkPresent}")
            appendLine("underlyingNetworkPresent=${snapshot.underlyingNetworkPresent}")
            appendPath("vpnPath", snapshot.vpnPath)
            appendPath("underlyingPath", snapshot.underlyingPath)
        }

    private fun StringBuilder.appendPath(
        prefix: String,
        path: TunProbeDebugPath,
    ) {
        appendLine("$prefix.available=${path.available}")
        appendLine("$prefix.interfaceName=${path.interfaceName.orEmpty()}")
        appendLine("$prefix.selectedMode=${path.selectedMode.orEmpty()}")
        appendLine("$prefix.selectedIp=${path.selectedIp.orEmpty()}")
        appendLine("$prefix.strictStatus=${path.strictStatus.orEmpty()}")
        appendLine("$prefix.curlStatus=${path.curlStatus.orEmpty()}")
        appendLine("$prefix.error=${path.error.orEmpty()}")
    }
}
