package com.poyka.ripdpi.diagnostics.export

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DiagnosticsArchiveInstalledArtifactCollectorTest {
    @Test
    fun `signing lineage never claims absent history when platform cannot expose it`() {
        assertEquals(
            DiagnosticsArchiveSigningLineageBand.SINGLE_CURRENT_HISTORY_UNAVAILABLE,
            classifySigningLineage(
                sdkInt = 27,
                currentSignerCount = 1,
                signingInfoAvailable = false,
                hasPastSigningCertificates = false,
            ),
        )
        assertEquals(
            DiagnosticsArchiveSigningLineageBand.SINGLE_WITH_HISTORY,
            classifySigningLineage(
                sdkInt = 28,
                currentSignerCount = 1,
                signingInfoAvailable = true,
                hasPastSigningCertificates = true,
            ),
        )
        assertEquals(
            DiagnosticsArchiveSigningLineageBand.SINGLE_CURRENT,
            classifySigningLineage(
                sdkInt = 28,
                currentSignerCount = 1,
                signingInfoAvailable = true,
                hasPastSigningCertificates = false,
            ),
        )
        assertEquals(
            DiagnosticsArchiveSigningLineageBand.MULTIPLE_CURRENT,
            classifySigningLineage(
                sdkInt = 27,
                currentSignerCount = 2,
                signingInfoAvailable = false,
                hasPastSigningCertificates = false,
            ),
        )
    }

    @Test
    fun `collector hashes base splits current signers and allowlisted packaged libraries`() {
        val directory = Files.createTempDirectory("installed-artifact-complete").toFile()
        val baseApk = writeApk(directory.resolve("base-private-name.apk"), "arm64-v8a", "arm64")
        val splitOne = writeApk(directory.resolve("secret-feature-name.apk"), "x86_64", "x64")
        val splitTwo = writeApk(directory.resolve("private-language-name.apk"), null, "resource")
        val signerOne = "current-signer-one".toByteArray()
        val signerTwo = "current-signer-two".toByteArray()
        val snapshot =
            snapshot(
                baseApk = baseApk,
                splitApks = listOf(splitTwo, splitOne),
                currentSigners = listOf(signerTwo, signerOne),
                signingLineage = DiagnosticsArchiveSigningLineageBand.MULTIPLE_CURRENT,
                debuggable = true,
            )
        val collector =
            DiagnosticsArchiveInstalledArtifactCollector {
                InstalledArtifactSnapshotResult.Success(snapshot)
            }

        val artifact = collector.collect()

        assertEquals(DiagnosticsArchiveInstalledArtifactCollectionStatus.COMPLETE, artifact.collectionStatus)
        assertEquals(fileSha256(baseApk), artifact.baseApkSha256)
        assertEquals(listOf(fileSha256(splitOne), fileSha256(splitTwo)).sorted(), artifact.splitApkSha256)
        assertEquals(
            listOf(byteSha256(signerOne), byteSha256(signerTwo)).sorted(),
            artifact.currentSignerCertificateSha256,
        )
        assertEquals(DiagnosticsArchiveSigningLineageBand.MULTIPLE_CURRENT, artifact.signingLineage)
        assertEquals(true, artifact.debuggable)
        assertTrue(artifact.failures.isEmpty())
        assertEquals(8, artifact.packagedNativeLibrarySha256.size)
        assertEquals(
            setOf(DiagnosticsArchiveNativeAbi.ARM64, DiagnosticsArchiveNativeAbi.X86_64),
            artifact.packagedNativeLibrarySha256.map { it.abi }.toSet(),
        )
        assertEquals(
            PackagedLibraryNames,
            artifact.packagedNativeLibrarySha256.map { it.name }.toSet(),
        )
    }

    @Test
    fun `collector reports categorical partial results without leaking failure details`() {
        val directory = Files.createTempDirectory("installed-artifact-partial").toFile()
        val baseApk = writeApk(directory.resolve("base-secret.apk"), "arm64-v8a", "base", libraryLimit = 1)
        val missingSplit = directory.resolve("missing-secret-split.apk")
        val snapshot =
            snapshot(
                baseApk = baseApk,
                splitApks = listOf(missingSplit),
                currentSigners = emptyList(),
            )
        val collector =
            DiagnosticsArchiveInstalledArtifactCollector {
                InstalledArtifactSnapshotResult.Success(snapshot)
            }

        val artifact = collector.collect()

        assertEquals(DiagnosticsArchiveInstalledArtifactCollectionStatus.PARTIAL, artifact.collectionStatus)
        assertEquals(fileSha256(baseApk), artifact.baseApkSha256)
        assertTrue(artifact.splitApkSha256.isEmpty())
        assertTrue(artifact.currentSignerCertificateSha256.isEmpty())
        assertTrue(DiagnosticsArchiveInstalledArtifactFailure.SPLIT_APK_READ_FAILED in artifact.failures)
        assertTrue(DiagnosticsArchiveInstalledArtifactFailure.SIGNERS_UNAVAILABLE in artifact.failures)
        assertTrue(DiagnosticsArchiveInstalledArtifactFailure.NATIVE_LIBRARY_SCAN_FAILED in artifact.failures)
        assertTrue(DiagnosticsArchiveInstalledArtifactFailure.NATIVE_LIBRARY_MISSING in artifact.failures)
    }

    @Test
    fun `collector retries one identity change and fails closed on repeated changes`() {
        val directory = Files.createTempDirectory("installed-artifact-toctou").toFile()
        val baseApk = writeApk(directory.resolve("base.apk"), "arm64-v8a", "stable")
        val stable = snapshot(baseApk = baseApk, identityMarker = 3L)
        val retrySnapshots =
            ArrayDeque(
                listOf(
                    snapshot(baseApk = baseApk, identityMarker = 1L),
                    snapshot(baseApk = baseApk, identityMarker = 2L),
                    stable,
                    stable,
                ),
            )

        val retried =
            DiagnosticsArchiveInstalledArtifactCollector {
                InstalledArtifactSnapshotResult.Success(retrySnapshots.removeFirst())
            }.collect()

        assertEquals(DiagnosticsArchiveInstalledArtifactCollectionStatus.COMPLETE, retried.collectionStatus)
        assertTrue(retrySnapshots.isEmpty())

        var marker = 0L
        val inconsistent =
            DiagnosticsArchiveInstalledArtifactCollector {
                marker += 1
                InstalledArtifactSnapshotResult.Success(snapshot(baseApk = baseApk, identityMarker = marker))
            }.collect()

        assertEquals(DiagnosticsArchiveInstalledArtifactCollectionStatus.INCONSISTENT, inconsistent.collectionStatus)
        assertNull(inconsistent.baseApkSha256)
        assertEquals(
            listOf(DiagnosticsArchiveInstalledArtifactFailure.ARTIFACT_CHANGED_DURING_COLLECTION),
            inconsistent.failures,
        )
    }

    @Test
    fun `collector fails closed when packaged native hashes conflict`() {
        val directory = Files.createTempDirectory("installed-artifact-conflict").toFile()
        val baseApk = writeApk(directory.resolve("base.apk"), "arm64-v8a", "base")
        val splitApk = writeApk(directory.resolve("split.apk"), "arm64-v8a", "different")
        val snapshot = snapshot(baseApk = baseApk, splitApks = listOf(splitApk))

        val artifact =
            DiagnosticsArchiveInstalledArtifactCollector {
                InstalledArtifactSnapshotResult.Success(snapshot)
            }.collect()

        assertEquals(DiagnosticsArchiveInstalledArtifactCollectionStatus.INCONSISTENT, artifact.collectionStatus)
        assertTrue(DiagnosticsArchiveInstalledArtifactFailure.NATIVE_LIBRARY_CONFLICT in artifact.failures)
        assertTrue(artifact.packagedNativeLibrarySha256.isEmpty())
    }

    @Test
    fun `collector returns typed unavailable result for package lookup failure`() {
        val artifact =
            DiagnosticsArchiveInstalledArtifactCollector {
                InstalledArtifactSnapshotResult.Failure(
                    DiagnosticsArchiveInstalledArtifactFailure.PACKAGE_QUERY_FAILED,
                )
            }.collect()

        assertEquals(DiagnosticsArchiveInstalledArtifactCollectionStatus.UNAVAILABLE, artifact.collectionStatus)
        assertEquals(listOf(DiagnosticsArchiveInstalledArtifactFailure.PACKAGE_QUERY_FAILED), artifact.failures)
        assertNull(artifact.debuggable)
    }

    @Test
    fun `serialized collector result excludes structural package metadata`() {
        val directory = Files.createTempDirectory("installed-artifact-privacy").toFile()
        val baseApk = writeApk(directory.resolve("base-private-path.apk"), "arm64-v8a", "privacy")
        val splitApk = writeApk(directory.resolve("secret-split-name.apk"), null, "resource")
        val snapshot =
            snapshot(
                baseApk = baseApk,
                splitApks = listOf(splitApk),
                currentSigners = listOf("current-cert-private-der".toByteArray()),
                signingLineage = DiagnosticsArchiveSigningLineageBand.SINGLE_WITH_HISTORY,
            )
        val artifact =
            DiagnosticsArchiveInstalledArtifactCollector {
                InstalledArtifactSnapshotResult.Success(snapshot)
            }.collect()

        val encoded = Json.encodeToString(DiagnosticsArchiveInstalledArtifact.serializer(), artifact)

        listOf(
            directory.path,
            baseApk.name,
            splitApk.name,
            "splitName",
            "installer",
            "lastUpdateTime",
            "modifiedAt",
            "size",
            "current-cert-private-der",
            "subject",
            "issuer",
            "serial",
            "exception",
        ).forEach { forbidden -> assertFalse("$forbidden must not be serialized", encoded.contains(forbidden)) }
        assertTrue(encoded.contains("SINGLE_WITH_HISTORY"))
    }

    private fun snapshot(
        baseApk: File,
        splitApks: List<File> = emptyList(),
        currentSigners: List<ByteArray> = listOf("current-signer".toByteArray()),
        signingLineage: DiagnosticsArchiveSigningLineageBand = DiagnosticsArchiveSigningLineageBand.SINGLE_CURRENT,
        debuggable: Boolean = false,
        identityMarker: Long = 1L,
    ): InstalledArtifactSnapshot =
        InstalledArtifactSnapshot(
            identity =
                InstalledArtifactIdentity(
                    versionCode = identityMarker,
                    lastUpdateTime = identityMarker,
                    sourceFiles =
                        (listOf(baseApk) + splitApks).map { file ->
                            InstalledArtifactFileIdentity(file.path, file.length(), file.lastModified())
                        },
                    currentSignerSha256 = currentSigners.map(::byteSha256).sorted(),
                    debuggable = debuggable,
                ),
            baseApk = baseApk,
            splitApks = splitApks,
            currentSignerCertificates = currentSigners,
            signingLineage = signingLineage,
            debuggable = debuggable,
        )

    private fun writeApk(
        file: File,
        abi: String?,
        marker: String,
        libraryLimit: Int = PackagedLibraryNames.size,
    ): File {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/$marker.txt"))
            zip.write(marker.toByteArray())
            zip.closeEntry()
            if (abi != null) {
                PackagedLibraryNames.take(libraryLimit).forEach { name ->
                    zip.putNextEntry(ZipEntry("lib/$abi/$name"))
                    zip.write("$marker:$name".toByteArray())
                    zip.closeEntry()
                }
            }
        }
        return file
    }

    private fun fileSha256(file: File): String = byteSha256(file.readBytes())

    private fun byteSha256(value: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        val PackagedLibraryNames =
            setOf(
                "libripdpi.so",
                "libripdpi-tunnel.so",
                "libripdpi-relay.so",
                "libripdpi-warp.so",
            )
    }
}
