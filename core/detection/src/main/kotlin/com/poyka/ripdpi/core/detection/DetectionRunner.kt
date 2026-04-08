package com.poyka.ripdpi.core.detection

import android.content.Context
import com.poyka.ripdpi.core.detection.checker.BypassChecker
import com.poyka.ripdpi.core.detection.checker.DirectSignsChecker
import com.poyka.ripdpi.core.detection.checker.GeoIpChecker
import com.poyka.ripdpi.core.detection.checker.IndirectSignsChecker
import com.poyka.ripdpi.core.detection.checker.LocationSignalsChecker
import com.poyka.ripdpi.core.detection.checker.VerdictEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class DetectionRunnerConfig(
    val ownProxyPort: Int? = null,
    val ownPackageName: String? = null,
    val includeBypassCheck: Boolean = true,
    val includeLocationCheck: Boolean = true,
)

object DetectionRunner {
    data class Progress(
        val stage: String,
        val detail: String,
    )

    suspend fun run(
        context: Context,
        config: DetectionRunnerConfig = DetectionRunnerConfig(),
        onProgress: (suspend (Progress) -> Unit)? = null,
    ): DetectionCheckResult =
        coroutineScope {
            val excludePackage = config.ownPackageName ?: context.packageName
            val excludePorts = setOfNotNull(config.ownProxyPort)

            onProgress?.invoke(Progress("GeoIP", "Checking IP geolocation..."))
            val geoIpDeferred = async { GeoIpChecker.check() }

            onProgress?.invoke(Progress("Direct signs", "Checking VPN transport and installed apps..."))
            val directSignsDeferred = async { DirectSignsChecker.check(context, excludePackage) }

            onProgress?.invoke(Progress("Indirect signs", "Checking network interfaces and DNS..."))
            val indirectSignsDeferred = async { IndirectSignsChecker.check(context) }

            val locationSignalsDeferred =
                if (config.includeLocationCheck) {
                    onProgress?.invoke(Progress("Location", "Checking cellular signals..."))
                    async { LocationSignalsChecker.check(context) }
                } else {
                    null
                }

            val bypassDeferred =
                if (config.includeBypassCheck) {
                    async {
                        BypassChecker.check(
                            excludePorts = excludePorts,
                            onProgress = { progress ->
                                onProgress?.invoke(Progress("Bypass: ${progress.phase}", progress.detail))
                            },
                        )
                    }
                } else {
                    null
                }

            val geoIp = geoIpDeferred.await()
            val directSigns = directSignsDeferred.await()
            val indirectSigns = indirectSignsDeferred.await()
            val locationSignals =
                locationSignalsDeferred?.await() ?: CategoryResult(
                    name = "Location signals",
                    detected = false,
                    findings = listOf(Finding("Location check disabled")),
                )
            val bypassResult =
                bypassDeferred?.await() ?: BypassResult(
                    proxyEndpoint = null,
                    directIp = null,
                    proxyIp = null,
                    xrayApiScanResult = null,
                    findings = listOf(Finding("Bypass check disabled")),
                    detected = false,
                )

            val verdict =
                VerdictEngine.evaluate(
                    geoIp = geoIp,
                    directSigns = directSigns,
                    indirectSigns = indirectSigns,
                    locationSignals = locationSignals,
                    bypassResult = bypassResult,
                )

            DetectionCheckResult(
                geoIp = geoIp,
                directSigns = directSigns,
                indirectSigns = indirectSigns,
                locationSignals = locationSignals,
                bypassResult = bypassResult,
                verdict = verdict,
            )
        }
}
