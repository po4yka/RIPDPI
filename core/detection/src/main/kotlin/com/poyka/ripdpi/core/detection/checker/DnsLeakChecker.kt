package com.poyka.ripdpi.core.detection.checker

import android.content.Context
import android.net.ConnectivityManager
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.withContext

object DnsLeakChecker {
    suspend fun check(
        dispatchers: AppCoroutineDispatchers,
        context: Context,
        encryptedDnsEnabled: Boolean = false,
    ): CategoryResult =
        withContext(dispatchers.io) {
            resultFrom(getActiveDnsServers(context), encryptedDnsEnabled)
        }

    internal fun resultFrom(
        activeDnsServers: List<String>,
        encryptedDnsEnabled: Boolean,
    ): CategoryResult =
        CategoryResult(
            name = "DNS Leak",
            detected = false,
            findings =
                listOf(
                    Finding(
                        "Active network DNS servers: " +
                            activeDnsServers.joinToString(", ").ifEmpty { "unavailable" },
                    ),
                    Finding("App encrypted DNS setting: ${if (encryptedDnsEnabled) "enabled" else "disabled"}"),
                ),
        )

    private fun getActiveDnsServers(context: Context): List<String> =
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return emptyList()
            cm.getLinkProperties(network)?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
}
