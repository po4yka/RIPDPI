package com.poyka.ripdpi.ui.screens.awg

import com.poyka.ripdpi.data.awg.AwgCohortCatalogData
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.data.awg.matchCohortForConf
import com.poyka.ripdpi.data.wireguard.AmneziaWgArm64S34FloorException
import com.poyka.ripdpi.data.wireguard.AmneziaWgConfig
import com.poyka.ripdpi.data.wireguard.AmneziaWgParameters
import com.poyka.ripdpi.data.wireguard.WireGuardConfModel
import com.poyka.ripdpi.data.wireguard.WireGuardConfParser
import com.poyka.ripdpi.data.wireguard.WireGuardConfUnknownKeyException

/** Replaces the editor state from a `.conf`, or returns this state unchanged when it cannot. */
fun AmneziaWgEditorState.populateFromConf(
    conf: String,
    catalog: AwgCohortCatalogData,
): AmneziaWgEditorState = (importConf(conf, catalog) as? AwgConfImport.Imported)?.editor ?: this

/**
 * Imports a WireGuard or AmneziaWG `.conf` through [WireGuardConfParser]. A plain
 * WireGuard config becomes an AmneziaWG profile with no obfuscation (`Jc`/`S` = 0,
 * `H1`-`H4` = 1-4), which is the same wire protocol. When the parsed obfuscation params
 * byte-match a [catalog] preset the editor locks onto that cohort, otherwise it lands on
 * `Custom`. Only the first `[Peer]` is used; [AwgConfImport.Imported.ignoredPeerCount]
 * reports the rest so the screen can say so.
 */
fun AmneziaWgEditorState.importConf(
    conf: String,
    catalog: AwgCohortCatalogData,
): AwgConfImport {
    val result = conf.takeIf { it.isNotBlank() }?.let { runCatching { WireGuardConfParser.parse(it) } }
    val parsed = result?.getOrNull()
    val unknownKey = (result?.exceptionOrNull() as? WireGuardConfUnknownKeyException)?.key
    return when {
        conf.isBlank() -> AwgConfImport.Failed(AwgConfImportFailure.UNREADABLE)
        unknownKey != null -> AwgConfImport.Failed(AwgConfImportFailure.UNSUPPORTED_OPTION, unknownKey)
        parsed == null -> AwgConfImport.Failed(AwgConfImportFailure.INVALID)
        else -> imported(conf, parsed, catalog)
    }
}

private fun AmneziaWgEditorState.imported(
    conf: String,
    parsed: WireGuardConfModel,
    catalog: AwgCohortCatalogData,
): AwgConfImport.Imported {
    val awg = (parsed as? AmneziaWgConfig)?.awg ?: AmneziaWgParameters()
    val cohortId = matchCohortForConf(conf, catalog)
    val nextForm = buildFormFromConf(parsed, awg, cohortId)
    // obfuscationRawText() seeds the S/H/I raw text from the freshly-built
    // form, so the I-fields parsed into the form above are covered here too.
    // transportRawText() seeds the non-column fields (Address/DNS/MTU/
    // AllowedIPs/Keepalive) that the parser retains but buildFormFromConf does
    // not carry as form columns — without these a pasted .conf left Address
    // blank (blocking activation) and silently discarded DNS/AllowedIPs/MTU
    // (audit P1-12).
    val nextRaw =
        nextForm.identityRawText() + nextForm.obfuscationRawText() + transportRawText(parsed)
    val editor =
        copy(
            form = nextForm,
            rawTextByField = rawTextByField + nextRaw,
            obfuscationLocked = cohortId != AwgProfileForm.CUSTOM_COHORT_ID,
        )
    return AwgConfImport.Imported(
        editor = editor,
        ignoredPeerCount = (parsed.peers.size - 1).coerceAtLeast(0),
        blockedByPaddingFloor =
            runCatching { awg.requireSupportedProfile() }.exceptionOrNull() is
                AmneziaWgArm64S34FloorException,
    )
}

private fun AmneziaWgEditorState.transportRawText(parsed: WireGuardConfModel): Map<AwgEditorField, String> {
    val iface = parsed.interfaceSection
    val peer = parsed.peers.firstOrNull()
    return buildMap {
        iface.address.takeIf { it.isNotEmpty() }?.let { put(AwgEditorField.ADDRESS, it.joinToString(", ")) }
        iface.dns.takeIf { it.isNotEmpty() }?.let { put(AwgEditorField.DNS, it.joinToString(", ")) }
        iface.mtu?.let { put(AwgEditorField.MTU, it.toString()) }
        peer?.allowedIps?.takeIf { it.isNotEmpty() }?.let { put(AwgEditorField.ALLOWED_IPS, it.joinToString(", ")) }
        peer?.persistentKeepalive?.let { low ->
            put(AwgEditorField.PERSISTENT_KEEPALIVE, peer.persistentKeepaliveMax?.let { "$low-$it" } ?: "$low")
        }
    }
}

private fun AmneziaWgEditorState.buildFormFromConf(
    parsed: WireGuardConfModel,
    awg: AmneziaWgParameters,
    cohortId: String,
): AwgProfileForm {
    val peer = parsed.peers.firstOrNull()
    val endpoint = peer?.endpoint.orEmpty()
    // `[2001:db8::1]:51820` keeps its brackets only around the port separator.
    val host = endpoint.substringBeforeLast(':', endpoint).removeSurrounding("[", "]")
    val port = endpoint.substringAfterLast(':', "").toIntOrNull() ?: 0
    return form.copy(
        wireProfile = awg.wireProfile,
        interfacePrivateKey = parsed.interfaceSection.privateKey,
        peerPublicKey = peer?.publicKey.orEmpty(),
        presharedKey = peer?.presharedKey.orEmpty(),
        server = host,
        serverPort = port,
        jc = awg.jc ?: 0,
        jmin = awg.jmin ?: 0,
        jmax = awg.jmax ?: 0,
        s1 = awg.s1 ?: 0,
        s2 = awg.s2 ?: 0,
        s3 = awg.s3 ?: 0,
        s4 = awg.s4 ?: 0,
        h1 = awg.h1 ?: "1",
        h2 = awg.h2 ?: "2",
        h3 = awg.h3 ?: "3",
        h4 = awg.h4 ?: "4",
        i1 = awg.i1.orEmpty(),
        i2 = awg.i2.orEmpty(),
        i3 = awg.i3.orEmpty(),
        i4 = awg.i4.orEmpty(),
        i5 = awg.i5.orEmpty(),
        v3 = awg.v3,
        cohortId = cohortId,
    )
}

/** Why a `.conf` could not fill the editor. */
enum class AwgConfImportFailure {
    /** The file could not be read, was empty, too large, or not text. */
    UNREADABLE,

    /** The text is not a WireGuard / AmneziaWG config RIPDPI can parse. */
    INVALID,

    /** The config uses a key RIPDPI does not know; [AwgConfImport.Failed.key] names it. */
    UNSUPPORTED_OPTION,
}

/** Outcome of [AmneziaWgEditorState.importConf]. */
sealed interface AwgConfImport {
    data class Imported(
        val editor: AmneziaWgEditorState,
        val ignoredPeerCount: Int,
        /** Non-zero S3/S4, which AWG 3.x header masking needs, stay blocked by the device floor. */
        val blockedByPaddingFloor: Boolean = false,
    ) : AwgConfImport

    data class Failed(
        val reason: AwgConfImportFailure,
        /** The unsupported key name for [AwgConfImportFailure.UNSUPPORTED_OPTION]; never a value. */
        val key: String = "",
    ) : AwgConfImport
}
