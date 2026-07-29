package com.poyka.ripdpi.services

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class RemoteDeviceRecoveryReceiptPersistenceTest {
    @Test
    fun `device protected commit restores categorical receipt without raw identifiers`() {
        val application = RuntimeEnvironment.getApplication()
        val preferences =
            application
                .createDeviceProtectedStorageContext()
                .getSharedPreferences(RemoteDeviceRecoveryReceiptPreferencesName, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val persistence = SharedPreferencesRemoteDeviceRecoveryReceiptPersistence(application)
        val rawGeneration = "00000000-0000-0000-0000-00000000000a"
        val rawServiceInstance = "service-instance-sensitive"
        val state =
            PersistedRemoteDeviceRecoveryReceipt(
                generationHash = rawGeneration.sha256ForTest(),
                serviceInstanceHash = rawServiceInstance.sha256ForTest(),
                receipt =
                    RemoteDeviceRecoveryReceipt(
                        generation = PresentReceiptValue,
                        startOrigin = "boot",
                        userUnlocked = "disabled",
                    ),
            )

        assertTrue(persistence.compareAndSet(expectedGenerationHash = null, state = state))

        assertEquals(state, SharedPreferencesRemoteDeviceRecoveryReceiptPersistence(application).load())
        val persistedText = preferences.all.toString()
        assertFalse(persistedText.contains(rawGeneration))
        assertFalse(persistedText.contains(rawServiceInstance))
    }

    @Test
    fun `compare and set rejects a stale recovery generation`() {
        val application = RuntimeEnvironment.getApplication()
        val preferences =
            application
                .createDeviceProtectedStorageContext()
                .getSharedPreferences(RemoteDeviceRecoveryReceiptPreferencesName, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val persistence = SharedPreferencesRemoteDeviceRecoveryReceiptPersistence(application)
        val first =
            PersistedRemoteDeviceRecoveryReceipt(
                generationHash = "first-hash",
                serviceInstanceHash = "first-service-hash",
                receipt = RemoteDeviceRecoveryReceipt(generation = PresentReceiptValue, startOrigin = "boot"),
            )
        val stale =
            first.copy(
                generationHash = "stale-hash",
                receipt = first.receipt.copy(startOrigin = "process_death"),
            )

        assertTrue(persistence.compareAndSet(null, first))
        assertFalse(persistence.compareAndSet("wrong-hash", stale))
        assertEquals(first, persistence.load())
    }

    @Test
    fun `locked direct boot storage failure never falls back to credential storage`() {
        val context = FailingDeviceProtectedStorageContext(RuntimeEnvironment.getApplication())
        val persistence = SharedPreferencesRemoteDeviceRecoveryReceiptPersistence(context)
        val state =
            PersistedRemoteDeviceRecoveryReceipt(
                generationHash = "generation-hash",
                serviceInstanceHash = "service-hash",
                receipt = RemoteDeviceRecoveryReceipt(generation = PresentReceiptValue),
            )

        assertEquals(
            RemoteDeviceRecoveryReceiptPersistenceAvailability.DeviceProtectedStorageUnavailable,
            persistence.availability,
        )
        assertNull(persistence.load())
        assertFalse(persistence.compareAndSet(expectedGenerationHash = null, state = state))
        assertFalse(context.credentialProtectedPreferencesRequested)
    }
}

private class FailingDeviceProtectedStorageContext(
    base: Context,
) : ContextWrapper(base) {
    var credentialProtectedPreferencesRequested = false
        private set

    override fun createDeviceProtectedStorageContext(): Context =
        error("device-protected storage is unavailable while user storage is locked")

    override fun getSharedPreferences(
        name: String?,
        mode: Int,
    ): SharedPreferences {
        credentialProtectedPreferencesRequested = true
        return super.getSharedPreferences(name, mode)
    }
}

private fun String.sha256ForTest(): String =
    java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
