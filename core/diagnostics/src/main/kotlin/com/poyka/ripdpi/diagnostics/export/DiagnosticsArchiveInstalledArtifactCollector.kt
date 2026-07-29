package com.poyka.ripdpi.diagnostics.export

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

private const val Sha256BufferSize = 16 * 1024
private const val CollectionAttemptCount = 2
private const val NativeLibraryPathPartCount = 3

private val PackagedNativeLibraryAllowList =
    setOf(
        "libripdpi.so",
        "libripdpi-tunnel.so",
        "libripdpi-relay.so",
        "libripdpi-warp.so",
    )

@Singleton
internal class DiagnosticsArchiveInstalledArtifactCollector
    internal constructor(
        private val cancellationCheck: suspend () -> Unit = { currentCoroutineContext().ensureActive() },
        private val snapshotSource: () -> InstalledArtifactSnapshotResult,
    ) {
        @Inject
        constructor(snapshotSource: AndroidInstalledArtifactSnapshotSource) : this(
            snapshotSource = snapshotSource::snapshotOrFailure,
        )

        internal suspend fun collect(): DiagnosticsArchiveInstalledArtifact {
            var resolved: DiagnosticsArchiveInstalledArtifact? = null
            repeat(CollectionAttemptCount) { attempt ->
                if (resolved == null) {
                    cancellationCheck()
                    when (val before = snapshotSource()) {
                        is InstalledArtifactSnapshotResult.Failure -> {
                            if (attempt == CollectionAttemptCount - 1) resolved = before.toUnavailableArtifact()
                        }

                        is InstalledArtifactSnapshotResult.Success -> {
                            val collected = collect(before.snapshot)
                            cancellationCheck()
                            when (val after = snapshotSource()) {
                                is InstalledArtifactSnapshotResult.Failure -> {
                                    if (attempt == CollectionAttemptCount - 1) {
                                        resolved = after.toUnavailableArtifact()
                                    }
                                }

                                is InstalledArtifactSnapshotResult.Success -> {
                                    if (before.snapshot.identity == after.snapshot.identity) resolved = collected
                                }
                            }
                        }
                    }
                }
            }
            return resolved
                ?: DiagnosticsArchiveInstalledArtifact(
                    collectionStatus = DiagnosticsArchiveInstalledArtifactCollectionStatus.INCONSISTENT,
                    failures = listOf(DiagnosticsArchiveInstalledArtifactFailure.ARTIFACT_CHANGED_DURING_COLLECTION),
                )
        }

        private suspend fun collect(snapshot: InstalledArtifactSnapshot): DiagnosticsArchiveInstalledArtifact {
            val failures = linkedSetOf<DiagnosticsArchiveInstalledArtifactFailure>()
            val baseApkSha256 = hashFile(snapshot.baseApk) ?: failures.recordBaseApkReadFailure()
            val splitApkSha256 =
                snapshot.splitApks
                    .mapNotNull { splitApk ->
                        hashFile(splitApk) ?: failures.recordSplitApkReadFailure()
                    }.sorted()
            val currentSignerCertificateSha256 =
                snapshot.currentSignerCertificates
                    .map { certificate ->
                        cancellationCheck()
                        sha256Hex(certificate)
                    }.distinct()
                    .sorted()
                    .also { hashes ->
                        if (hashes.isEmpty()) failures += DiagnosticsArchiveInstalledArtifactFailure.SIGNERS_UNAVAILABLE
                    }
            val packagedNativeLibraries = collectPackagedNativeLibraries(snapshot.allApks, failures)
            val collectionStatus =
                when {
                    DiagnosticsArchiveInstalledArtifactFailure.NATIVE_LIBRARY_CONFLICT in failures -> {
                        DiagnosticsArchiveInstalledArtifactCollectionStatus.INCONSISTENT
                    }

                    baseApkSha256 == null &&
                        splitApkSha256.isEmpty() &&
                        currentSignerCertificateSha256.isEmpty() &&
                        packagedNativeLibraries.isEmpty() -> {
                        DiagnosticsArchiveInstalledArtifactCollectionStatus.UNAVAILABLE
                    }

                    failures.isEmpty() -> {
                        DiagnosticsArchiveInstalledArtifactCollectionStatus.COMPLETE
                    }

                    else -> {
                        DiagnosticsArchiveInstalledArtifactCollectionStatus.PARTIAL
                    }
                }
            return DiagnosticsArchiveInstalledArtifact(
                collectionStatus = collectionStatus,
                baseApkSha256 = baseApkSha256,
                splitApkSha256 = splitApkSha256,
                currentSignerCertificateSha256 = currentSignerCertificateSha256,
                signingLineage = snapshot.signingLineage,
                packagedNativeLibrarySha256 = packagedNativeLibraries,
                debuggable = snapshot.debuggable,
                failures = failures.sortedBy { it.ordinal },
            )
        }

        private suspend fun collectPackagedNativeLibraries(
            apkFiles: List<File>,
            failures: MutableSet<DiagnosticsArchiveInstalledArtifactFailure>,
        ): List<DiagnosticsArchiveInstalledNativeLibrary> {
            val hashesByKey = linkedMapOf<NativeLibraryKey, MutableSet<String>>()
            apkFiles.forEach { apkFile ->
                cancellationCheck()
                if (!scanPackagedNativeLibraries(apkFile, hashesByKey)) {
                    failures += DiagnosticsArchiveInstalledArtifactFailure.NATIVE_LIBRARY_SCAN_FAILED
                }
            }
            val detectedAbis = hashesByKey.keys.mapTo(linkedSetOf()) { it.abi }
            val missingLibrary =
                detectedAbis.isEmpty() ||
                    detectedAbis.any { abi ->
                        PackagedNativeLibraryAllowList.any { name -> NativeLibraryKey(abi, name) !in hashesByKey }
                    }
            if (missingLibrary) failures += DiagnosticsArchiveInstalledArtifactFailure.NATIVE_LIBRARY_MISSING
            if (hashesByKey.values.any { it.size > 1 }) {
                failures += DiagnosticsArchiveInstalledArtifactFailure.NATIVE_LIBRARY_CONFLICT
            }
            return hashesByKey
                .filterValues { it.size == 1 }
                .map { (key, hashes) ->
                    DiagnosticsArchiveInstalledNativeLibrary(
                        abi = key.abi,
                        name = key.name,
                        sha256 = hashes.single(),
                    )
                }.sortedWith(compareBy({ it.abi.ordinal }, { it.name }, { it.sha256 }))
        }

        private suspend fun scanPackagedNativeLibraries(
            apkFile: File,
            hashesByKey: MutableMap<NativeLibraryKey, MutableSet<String>>,
        ): Boolean =
            try {
                ZipFile(apkFile).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        cancellationCheck()
                        collectPackagedNativeLibraryEntry(zip, entry, hashesByKey)
                    }
                }
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }

        private suspend fun collectPackagedNativeLibraryEntry(
            zip: ZipFile,
            entry: java.util.zip.ZipEntry,
            hashesByKey: MutableMap<NativeLibraryKey, MutableSet<String>>,
        ) {
            val key = nativeLibraryKey(entry.name)
            if (!entry.isDirectory && key != null) {
                val hash = zip.getInputStream(entry).use { input -> sha256Hex(input) }
                hashesByKey.getOrPut(key, ::linkedSetOf).add(hash)
            }
        }

        private suspend fun hashFile(file: File): String? =
            try {
                file.inputStream().buffered().use { input -> sha256Hex(input) }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }

        private suspend fun sha256Hex(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(Sha256BufferSize)
            while (true) {
                cancellationCheck()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            return digest.digest().toHex()
        }
    }

@Singleton
internal class AndroidInstalledArtifactSnapshotSource
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        @Suppress("DEPRECATION")
        internal fun snapshotOrFailure(): InstalledArtifactSnapshotResult =
            try {
                val signingFlag =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        PackageManager.GET_SIGNING_CERTIFICATES
                    } else {
                        PackageManager.GET_SIGNATURES
                    }
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, signingFlag)
                val applicationInfo =
                    packageInfo.applicationInfo
                        ?: return InstalledArtifactSnapshotResult.Failure(
                            DiagnosticsArchiveInstalledArtifactFailure.SOURCE_UNAVAILABLE,
                        )
                val baseSource =
                    applicationInfo.sourceDir
                        ?: return InstalledArtifactSnapshotResult.Failure(
                            DiagnosticsArchiveInstalledArtifactFailure.SOURCE_UNAVAILABLE,
                        )
                val currentSigners = packageInfo.currentSignerCertificates()
                val baseApk = File(baseSource)
                val splitApks =
                    applicationInfo.splitSourceDirs
                        .orEmpty()
                        .map(::File)
                        .sortedBy(File::getPath)
                InstalledArtifactSnapshotResult.Success(
                    InstalledArtifactSnapshot(
                        identity =
                            InstalledArtifactIdentity(
                                versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
                                lastUpdateTime = packageInfo.lastUpdateTime,
                                sourceFiles = (listOf(baseApk) + splitApks).map(::fileIdentity),
                                currentSignerSha256 = currentSigners.map(::sha256Hex).distinct().sorted(),
                                debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                            ),
                        baseApk = baseApk,
                        splitApks = splitApks,
                        currentSignerCertificates = currentSigners,
                        signingLineage = packageInfo.signingLineageBand(),
                        debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                    ),
                )
            } catch (_: PackageManager.NameNotFoundException) {
                InstalledArtifactSnapshotResult.Failure(
                    DiagnosticsArchiveInstalledArtifactFailure.PACKAGE_UNAVAILABLE,
                )
            } catch (_: SecurityException) {
                InstalledArtifactSnapshotResult.Failure(
                    DiagnosticsArchiveInstalledArtifactFailure.PACKAGE_QUERY_FAILED,
                )
            }
    }

internal sealed interface InstalledArtifactSnapshotResult {
    data class Success(
        val snapshot: InstalledArtifactSnapshot,
    ) : InstalledArtifactSnapshotResult

    data class Failure(
        val reason: DiagnosticsArchiveInstalledArtifactFailure,
    ) : InstalledArtifactSnapshotResult
}

internal data class InstalledArtifactSnapshot(
    val identity: InstalledArtifactIdentity,
    val baseApk: File,
    val splitApks: List<File>,
    val currentSignerCertificates: List<ByteArray>,
    val signingLineage: DiagnosticsArchiveSigningLineageBand,
    val debuggable: Boolean,
) {
    val allApks: List<File> = listOf(baseApk) + splitApks
}

internal data class InstalledArtifactIdentity(
    val versionCode: Long,
    val lastUpdateTime: Long,
    val sourceFiles: List<InstalledArtifactFileIdentity>,
    val currentSignerSha256: List<String>,
    val debuggable: Boolean,
)

internal data class InstalledArtifactFileIdentity(
    val path: String,
    val size: Long,
    val modifiedAt: Long,
)

private data class NativeLibraryKey(
    val abi: DiagnosticsArchiveNativeAbi,
    val name: String,
)

private fun nativeLibraryKey(entryName: String): NativeLibraryKey? {
    val parts = entryName.split('/')
    if (
        parts.size != NativeLibraryPathPartCount ||
        parts[0] != "lib" ||
        parts[2] !in PackagedNativeLibraryAllowList
    ) {
        return null
    }
    return NativeLibraryKey(abi = parts[1].toArchiveAbi(), name = parts[2])
}

private fun String.toArchiveAbi(): DiagnosticsArchiveNativeAbi =
    when (this) {
        "arm64-v8a" -> DiagnosticsArchiveNativeAbi.ARM64
        "armeabi", "armeabi-v7a" -> DiagnosticsArchiveNativeAbi.ARM32
        "x86_64" -> DiagnosticsArchiveNativeAbi.X86_64
        "x86" -> DiagnosticsArchiveNativeAbi.X86
        else -> DiagnosticsArchiveNativeAbi.OTHER
    }

private fun fileIdentity(file: File): InstalledArtifactFileIdentity =
    InstalledArtifactFileIdentity(
        path = file.path,
        size = file.length(),
        modifiedAt = file.lastModified(),
    )

@Suppress("DEPRECATION")
private fun PackageInfo.currentSignerCertificates(): List<ByteArray> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray() }
    } else {
        signatures.orEmpty().map { it.toByteArray() }
    }

@Suppress("DEPRECATION")
private fun PackageInfo.signingLineageBand(): DiagnosticsArchiveSigningLineageBand =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val currentSigningInfo = signingInfo
        classifySigningLineage(
            sdkInt = Build.VERSION.SDK_INT,
            currentSignerCount = currentSigningInfo?.apkContentsSigners.orEmpty().size,
            signingInfoAvailable = currentSigningInfo != null,
            hasPastSigningCertificates = currentSigningInfo?.hasPastSigningCertificates() == true,
        )
    } else {
        classifySigningLineage(
            sdkInt = Build.VERSION.SDK_INT,
            currentSignerCount = signatures.orEmpty().size,
            signingInfoAvailable = false,
            hasPastSigningCertificates = false,
        )
    }

internal fun classifySigningLineage(
    sdkInt: Int,
    currentSignerCount: Int,
    signingInfoAvailable: Boolean,
    hasPastSigningCertificates: Boolean,
): DiagnosticsArchiveSigningLineageBand =
    when {
        currentSignerCount == 0 -> DiagnosticsArchiveSigningLineageBand.UNAVAILABLE
        currentSignerCount > 1 -> DiagnosticsArchiveSigningLineageBand.MULTIPLE_CURRENT
        sdkInt < Build.VERSION_CODES.P -> DiagnosticsArchiveSigningLineageBand.SINGLE_CURRENT_HISTORY_UNAVAILABLE
        !signingInfoAvailable -> DiagnosticsArchiveSigningLineageBand.UNAVAILABLE
        hasPastSigningCertificates -> DiagnosticsArchiveSigningLineageBand.SINGLE_WITH_HISTORY
        else -> DiagnosticsArchiveSigningLineageBand.SINGLE_CURRENT
    }

private fun InstalledArtifactSnapshotResult.Failure.toUnavailableArtifact() =
    DiagnosticsArchiveInstalledArtifact(
        collectionStatus = DiagnosticsArchiveInstalledArtifactCollectionStatus.UNAVAILABLE,
        failures = listOf(reason),
    )

private fun MutableSet<DiagnosticsArchiveInstalledArtifactFailure>.recordBaseApkReadFailure(): String? {
    add(DiagnosticsArchiveInstalledArtifactFailure.BASE_APK_READ_FAILED)
    return null
}

private fun MutableSet<DiagnosticsArchiveInstalledArtifactFailure>.recordSplitApkReadFailure(): String? {
    add(DiagnosticsArchiveInstalledArtifactFailure.SPLIT_APK_READ_FAILED)
    return null
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
