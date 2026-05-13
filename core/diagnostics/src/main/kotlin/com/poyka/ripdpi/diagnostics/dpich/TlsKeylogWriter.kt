package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream

private const val MillisPerSecond = 1_000L

data class KeylogRetentionPolicy(
    val hoursToKeep: Int = DefaultHoursToKeep,
) {
    val maxAgeMs: Long = hoursToKeep.coerceAtLeast(0) * HourMs

    private companion object {
        private const val DefaultHoursToKeep = 24
        private const val HourMs = 60L * 60L * 1_000L
    }
}

class TlsKeylogWriter(
    private val path: File,
) {
    private val mutex = Mutex()

    suspend fun append(
        label: String,
        clientRandom: String,
        secret: String,
    ) {
        mutex.withLock {
            path.parentFile?.mkdirs()
            FileOutputStream(path, true).use { output ->
                output.write("$label $clientRandom $secret\n".toByteArray(Charsets.US_ASCII))
            }
        }
    }

    suspend fun rotate(timestampSeconds: Long): File =
        mutex.withLock {
            path.parentFile?.mkdirs()
            if (!path.exists()) path.writeText("")
            val rotated = File("${path.absolutePath}.$timestampSeconds")
            if (rotated.exists()) rotated.delete()
            check(path.renameTo(rotated)) { "Failed to rotate TLS keylog file" }
            path.writeText("")
            rotated
        }

    fun purgeOld(
        nowMs: Long = System.currentTimeMillis(),
        retention: KeylogRetentionPolicy = KeylogRetentionPolicy(),
    ): List<File> {
        val parent = path.parentFile ?: return emptyList()
        val prefix = "${path.name}."
        return parent
            .listFiles()
            .orEmpty()
            .filter { file -> file.name.startsWith(prefix) }
            .filter { file -> nowMs - file.lastModified() > retention.maxAgeMs }
            .filter { file -> file.delete() }
    }
}

data class TlsKeylogSettings(
    val path: String? = null,
    val debugModeEnabled: Boolean = false,
) {
    val visibleInSettings: Boolean = debugModeEnabled

    fun effectiveTlsKeylogPath(privacyModeEnabled: Boolean): String? =
        path.takeUnless { privacyModeEnabled || it.isNullOrBlank() }
}

class TlsKeylogPathValidator(
    private val appFilesDir: File,
    private val safRoots: List<File> = emptyList(),
) {
    fun validate(path: String): String {
        val candidate = File(path).canonicalFile
        val allowedRoots = listOf(appFilesDir) + safRoots
        if (allowedRoots.any { root -> candidate.isUnder(root.canonicalFile) }) {
            return candidate.absolutePath
        }
        throw IllegalArgumentException("TLS keylog path must be inside app storage")
    }

    private fun File.isUnder(root: File): Boolean =
        generateSequence(this) { file -> file.parentFile }.any { file -> file == root }
}

data class TlsKeylogFinalizationResult(
    val rotatedFile: File,
    val purgedFiles: List<File>,
)

class TlsKeylogRunFinalizer(
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / MillisPerSecond },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val retention: KeylogRetentionPolicy = KeylogRetentionPolicy(),
) {
    suspend fun finishRun(path: String): TlsKeylogFinalizationResult? {
        if (path.isBlank()) return null

        val writer = TlsKeylogWriter(File(path))
        val rotated = writer.rotate(timestampSeconds = nowSeconds())
        val purged = writer.purgeOld(nowMs = nowMs(), retention = retention)
        return TlsKeylogFinalizationResult(rotatedFile = rotated, purgedFiles = purged)
    }
}
