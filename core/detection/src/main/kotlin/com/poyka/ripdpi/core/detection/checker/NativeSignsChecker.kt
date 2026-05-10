package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.NativeSignsResult
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface

data class NativeSignsSnapshot(
    val nativeInterfaces: List<String> = emptyList(),
    val mapsLines: List<String> = emptyList(),
    val mountsLines: List<String> = emptyList(),
    val rootArtifacts: List<String> = emptyList(),
    val selinuxMode: String? = null,
)

fun interface NativeSignsBridge {
    fun snapshot(): NativeSignsSnapshot
}

object NativeSignsChecker {
    fun check(
        bridge: NativeSignsBridge = JniNativeSignsBridge(),
        jvmInterfaceProvider: () -> List<String> = ::jvmNetworkInterfaces,
    ): NativeSignsResult {
        val snapshot =
            try {
                bridge.snapshot()
            } catch (e: Throwable) {
                return unavailableResult(e)
            }
        val jvmInterfaces = jvmInterfaceProvider().toSet()
        val hiddenInterfaces =
            snapshot.nativeInterfaces
                .filter { it.isNotBlank() && it !in jvmInterfaces }
                .distinct()
                .sorted()
        val hookMarkers = hookMarkers(snapshot.mapsLines)
        val rwxRegions = rwxRegions(snapshot.mapsLines)
        val rootArtifacts = rootArtifacts(snapshot)
        return result(
            hiddenInterfaces = hiddenInterfaces,
            hookMarkers = hookMarkers,
            rwxRegions = rwxRegions,
            rootArtifacts = rootArtifacts,
            selinuxMode = snapshot.selinuxMode,
        )
    }

    private fun result(
        hiddenInterfaces: List<String>,
        hookMarkers: List<String>,
        rwxRegions: List<String>,
        rootArtifacts: List<String>,
        selinuxMode: String?,
    ): NativeSignsResult {
        val findings = mutableListOf<Finding>()
        val evidence = mutableListOf<EvidenceItem>()
        for (name in hiddenInterfaces) {
            addSignal(
                findings = findings,
                evidence = evidence,
                description = "Hidden native interface: $name",
                detected = true,
                confidence = EvidenceConfidence.HIGH,
            )
        }
        for (marker in hookMarkers) {
            addSignal(
                findings = findings,
                evidence = evidence,
                description = "Hook library marker in process maps: $marker",
                detected = true,
                confidence = EvidenceConfidence.HIGH,
            )
        }
        for (artifact in rootArtifacts) {
            addSignal(
                findings = findings,
                evidence = evidence,
                description = "Root artifact: $artifact",
                detected = false,
                confidence = EvidenceConfidence.MEDIUM,
            )
        }
        for (region in rwxRegions) {
            findings.add(
                Finding(
                    description = "Large RWX memory region: $region",
                    source = EvidenceSource.NATIVE_SIGNS,
                    confidence = EvidenceConfidence.LOW,
                ),
            )
        }
        if (selinuxMode.equals("permissive", ignoreCase = true)) {
            addSignal(
                findings = findings,
                evidence = evidence,
                description = "SELinux mode: permissive",
                detected = false,
                confidence = EvidenceConfidence.MEDIUM,
            )
        }
        if (findings.isEmpty()) {
            findings.add(Finding("Native signs: no hidden interfaces, hooks, or root artifacts detected"))
        }
        val detected = hiddenInterfaces.isNotEmpty() || hookMarkers.isNotEmpty()
        val hasReviewOnlySignals =
            rootArtifacts.isNotEmpty() ||
                selinuxMode.equals(
                    "permissive",
                    ignoreCase = true,
                )
        val needsReview = !detected && hasReviewOnlySignals
        return NativeSignsResult(
            category =
                CategoryResult(
                    name = "Native signs",
                    detected = detected,
                    findings = findings,
                    needsReview = needsReview,
                    evidence = evidence,
                ),
            hiddenInterfaces = hiddenInterfaces,
            hookMarkers = hookMarkers,
            rwxRegions = rwxRegions,
            rootArtifacts = rootArtifacts,
        )
    }

    private fun unavailableResult(error: Throwable): NativeSignsResult =
        NativeSignsResult(
            category =
                CategoryResult(
                    name = "Native signs",
                    detected = false,
                    findings =
                        listOf(
                            Finding(
                                "Native signs unavailable: ${error.message ?: error::class.java.simpleName}",
                            ),
                        ),
                ),
            hasError = false,
        )

    private fun addSignal(
        findings: MutableList<Finding>,
        evidence: MutableList<EvidenceItem>,
        description: String,
        detected: Boolean,
        confidence: EvidenceConfidence,
    ) {
        findings.add(
            Finding(
                description = description,
                detected = detected,
                needsReview = !detected,
                source = EvidenceSource.NATIVE_SIGNS,
                confidence = confidence,
            ),
        )
        evidence.add(
            EvidenceItem(
                source = EvidenceSource.NATIVE_SIGNS,
                detected = detected,
                confidence = confidence,
                description = description,
            ),
        )
    }

    private fun hookMarkers(mapsLines: List<String>): List<String> {
        val text = mapsLines.joinToString("\n").lowercase()
        return HookMarkers.filter { it in text }
    }

    private fun rwxRegions(mapsLines: List<String>): List<String> =
        mapsLines
            .filter { line -> line.contains("rwx") && line.regionSizeBytes() > LargeRwxRegionBytes }
            .map { line -> line.substringBeforeLast(' ') }
            .distinct()

    private fun rootArtifacts(snapshot: NativeSignsSnapshot): List<String> =
        (snapshot.rootArtifacts + overlayMounts(snapshot.mountsLines))
            .distinct()
            .sorted()

    private fun overlayMounts(mountsLines: List<String>): List<String> =
        mountsLines
            .filter { line -> line.contains(" overlay ") || line.contains(" bind ") }
            .map { line -> "mount:${line.take(MountFindingMaxChars)}" }

    private fun String.regionSizeBytes(): Long {
        val range = substringBefore(' ')
        val start = range.substringBefore('-').toLongOrNull(16) ?: return 0
        val end = range.substringAfter('-', "").toLongOrNull(16) ?: return 0
        return end - start
    }

    private fun jvmNetworkInterfaces(): List<String> =
        runCatching {
            NetworkInterface
                .getNetworkInterfaces()
                .toList()
                .map(NetworkInterface::getName)
        }.getOrDefault(emptyList())

    private const val LargeRwxRegionBytes = 4L * 1024L * 1024L
    private const val MountFindingMaxChars = 160
    private val HookMarkers = listOf("frida", "substrate", "xposed", "lspatch", "zygisk", "riru")
}

class JniNativeSignsBridge : NativeSignsBridge {
    override fun snapshot(): NativeSignsSnapshot {
        System.loadLibrary("ripdpi")
        val payload = jniSnapshot() ?: error("native signs bridge returned no payload")
        return JSONObject(payload).toNativeSignsSnapshot()
    }

    private external fun jniSnapshot(): String?
}

class ProcfsNativeSignsBridge : NativeSignsBridge {
    override fun snapshot(): NativeSignsSnapshot =
        NativeSignsSnapshot(
            nativeInterfaces = procNetDevices(),
            mapsLines = readLines("/proc/self/maps"),
            mountsLines = readLines("/proc/mounts"),
            rootArtifacts = rootArtifacts(),
            selinuxMode = selinuxMode(),
        )

    private fun procNetDevices(): List<String> =
        readLines("/proc/net/dev")
            .drop(2)
            .mapNotNull { line -> line.substringBefore(':').trim().takeIf(String::isNotBlank) }

    private fun rootArtifacts(): List<String> =
        buildList {
            val path = System.getenv("PATH")
            path
                .orEmpty()
                .split(File.pathSeparator)
                .map { File(it, "su") }
                .filter(File::exists)
                .forEach { add(it.path) }
            RootArtifactPaths.filter { File(it).exists() }.forEach(::add)
        }

    private fun selinuxMode(): String? =
        when (readText("/sys/fs/selinux/enforce")?.trim()) {
            "0" -> "permissive"
            "1" -> "enforcing"
            else -> null
        }

    private fun readLines(path: String): List<String> = readText(path)?.lineSequence()?.toList().orEmpty()

    private fun readText(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    private companion object {
        val RootArtifactPaths =
            listOf(
                "/data/adb/magisk",
                "/data/adb/ksu",
                "/data/adb/ap",
                "/data/adb/modules",
            )
    }
}

private fun JSONObject.toNativeSignsSnapshot(): NativeSignsSnapshot =
    NativeSignsSnapshot(
        nativeInterfaces = stringArray("nativeInterfaces"),
        mapsLines = stringArray("mapsLines"),
        mountsLines = stringArray("mountsLines"),
        rootArtifacts = stringArray("rootArtifacts"),
        selinuxMode = optString("selinuxMode").takeIf(String::isNotBlank),
    )

private fun JSONObject.stringArray(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            array.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}
