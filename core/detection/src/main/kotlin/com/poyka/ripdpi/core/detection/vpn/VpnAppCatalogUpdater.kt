package com.poyka.ripdpi.core.detection.vpn

import android.content.Context
import com.poyka.ripdpi.core.detection.VpnAppKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RemoteVpnAppEntry(
    val packageName: String,
    val appName: String,
    val family: String,
    val kind: String,
    val defaultPorts: List<Int> = emptyList(),
)

object VpnAppCatalogUpdater {
    private const val PREFS_NAME = "vpn_catalog_updates"
    private const val KEY_EXTRA_ENTRIES = "extra_entries"
    private val json = Json { ignoreUnknownKeys = true }

    fun loadExtraEntries(context: Context): List<VpnAppSignature> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_EXTRA_ENTRIES, null) ?: return emptyList()
        return try {
            val entries: List<RemoteVpnAppEntry> = json.decodeFromString(raw)
            entries.map { it.toSignature() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveExtraEntries(
        context: Context,
        entries: List<RemoteVpnAppEntry>,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_EXTRA_ENTRIES, json.encodeToString(entries)).apply()
    }

    fun mergedSignatures(context: Context): List<VpnAppSignature> {
        val extra = loadExtraEntries(context)
        val builtinNames = VpnAppCatalog.knownPackageNames
        val merged = VpnAppCatalog.signatures.toMutableList()
        for (sig in extra) {
            if (sig.packageName !in builtinNames) {
                merged.add(sig)
            }
        }
        return merged
    }

    private fun RemoteVpnAppEntry.toSignature(): VpnAppSignature =
        VpnAppSignature(
            packageName = packageName,
            appName = appName,
            family = family,
            kind =
                when (kind) {
                    "targeted_bypass" -> VpnAppKind.TARGETED_BYPASS
                    else -> VpnAppKind.GENERIC_VPN
                },
            defaultPorts = defaultPorts.toSet(),
        )
}
