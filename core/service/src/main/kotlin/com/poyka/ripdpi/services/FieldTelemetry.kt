package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.data.NetworkFingerprint
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

interface TelemetryInstallSaltStore {
    fun loadSalt(): String

    fun rotateSalt()
}

@Singleton
class FileBackedTelemetryInstallSaltStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : TelemetryInstallSaltStore {
        private val delegate =
            FileBackedTelemetryInstallSaltStoreDelegate(
                saltFile =
                    File(context.noBackupFilesDir, "ripdpi")
                        .apply { mkdirs() }
                        .resolve("telemetry-install-salt-v1.txt"),
            )

        override fun loadSalt(): String = delegate.loadSalt()

        override fun rotateSalt() = delegate.rotateSalt()
    }

internal class FileBackedTelemetryInstallSaltStoreDelegate(
    private val saltFile: File,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val lock = Any()

    fun rotateSalt(): Unit =
        synchronized(lock) {
            saltFile.delete()
        }

    fun loadSalt(): String =
        synchronized(lock) {
            val existing = saltFile.takeIf { it.exists() }?.readText()?.trim()
            if (!existing.isNullOrEmpty()) {
                return@synchronized existing
            }

            val bytes = ByteArray(32)
            secureRandom.nextBytes(bytes)
            val generated = bytes.toHexString()
            saltFile.parentFile?.mkdirs()
            saltFile.writeText(generated)
            generated
        }
}

interface TelemetryFingerprintHasher {
    fun hash(fingerprint: NetworkFingerprint?): String?
}

@Singleton
class DefaultTelemetryFingerprintHasher
    @Inject
    constructor(
        private val saltStore: TelemetryInstallSaltStore,
    ) : TelemetryFingerprintHasher {
        override fun hash(fingerprint: NetworkFingerprint?): String? {
            val parts = fingerprint?.canonicalParts() ?: return null
            val digest =
                MessageDigest.getInstance("SHA-256").digest(
                    (saltStore.loadSalt() + "|" + parts.joinToString("|")).toByteArray(),
                )
            return "v1:${digest.toHexString()}"
        }
    }

private fun ByteArray.toHexString(): String =
    buildString(size * 2) {
        this@toHexString.forEach { byte ->
            append(((byte.toInt() shr 4) and 0xF).toString(16))
            append((byte.toInt() and 0xF).toString(16))
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class FieldTelemetryModule {
    @Binds
    @Singleton
    abstract fun bindTelemetryInstallSaltStore(store: FileBackedTelemetryInstallSaltStore): TelemetryInstallSaltStore

    @Binds
    @Singleton
    abstract fun bindTelemetryFingerprintHasher(hasher: DefaultTelemetryFingerprintHasher): TelemetryFingerprintHasher
}
