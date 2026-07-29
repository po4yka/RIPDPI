package com.poyka.ripdpi.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal data class PersistedRemoteDeviceRecoveryReceipt(
    val generationHash: String,
    val serviceInstanceHash: String,
    val receipt: RemoteDeviceRecoveryReceipt,
)

internal interface RemoteDeviceRecoveryReceiptPersistence {
    fun load(): PersistedRemoteDeviceRecoveryReceipt?

    fun compareAndSet(
        expectedGenerationHash: String?,
        state: PersistedRemoteDeviceRecoveryReceipt,
    ): Boolean
}

@Singleton
internal class SharedPreferencesRemoteDeviceRecoveryReceiptPersistence
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : RemoteDeviceRecoveryReceiptPersistence {
        private val preferences =
            context
                .deviceProtectedStorageContextOrSelf()
                .getSharedPreferences(RemoteDeviceRecoveryReceiptPreferencesName, Context.MODE_PRIVATE)

        override fun load(): PersistedRemoteDeviceRecoveryReceipt? =
            synchronized(preferences) {
                val generationHash = preferences.getString(KeyGenerationHash, null) ?: return@synchronized null
                val serviceInstanceHash =
                    preferences.getString(KeyServiceInstanceHash, null) ?: return@synchronized null
                PersistedRemoteDeviceRecoveryReceipt(
                    generationHash = generationHash,
                    serviceInstanceHash = serviceInstanceHash,
                    receipt =
                        RemoteDeviceRecoveryReceipt(
                            generation = preferences.getString(KeyGeneration, null) ?: UnknownReceiptValue,
                            startOrigin = preferences.getString(KeyStartOrigin, null) ?: UnknownReceiptValue,
                            userUnlocked = preferences.getString(KeyUserUnlocked, null) ?: UnknownReceiptValue,
                            alwaysOn = preferences.getString(KeyAlwaysOn, null) ?: UnknownReceiptValue,
                            lockdown = preferences.getString(KeyLockdown, null) ?: UnknownReceiptValue,
                            serviceInstanceChanged =
                                preferences.getString(KeyServiceInstanceChanged, null) ?: UnknownReceiptValue,
                            timeToForegroundService =
                                preferences.getString(KeyTimeToForegroundService, null) ?: NotObservedReceiptValue,
                            timeToTun = preferences.getString(KeyTimeToTun, null) ?: NotObservedReceiptValue,
                            timeToFirstFlow =
                                preferences.getString(KeyTimeToFirstFlow, null) ?: NotObservedReceiptValue,
                            postStartDataPlaneOutcome =
                                preferences.getString(KeyPostStartDataPlaneOutcome, null) ?: PendingReceiptValue,
                        ).privacySafe(),
                )
            }

        override fun compareAndSet(
            expectedGenerationHash: String?,
            state: PersistedRemoteDeviceRecoveryReceipt,
        ): Boolean =
            synchronized(preferences) {
                if (preferences.getString(KeyGenerationHash, null) != expectedGenerationHash) {
                    return@synchronized false
                }
                val receipt = state.receipt.privacySafe()
                preferences
                    .edit()
                    .putString(KeyGenerationHash, state.generationHash)
                    .putString(KeyServiceInstanceHash, state.serviceInstanceHash)
                    .putString(KeyGeneration, receipt.generation)
                    .putString(KeyStartOrigin, receipt.startOrigin)
                    .putString(KeyUserUnlocked, receipt.userUnlocked)
                    .putString(KeyAlwaysOn, receipt.alwaysOn)
                    .putString(KeyLockdown, receipt.lockdown)
                    .putString(KeyServiceInstanceChanged, receipt.serviceInstanceChanged)
                    .putString(KeyTimeToForegroundService, receipt.timeToForegroundService)
                    .putString(KeyTimeToTun, receipt.timeToTun)
                    .putString(KeyTimeToFirstFlow, receipt.timeToFirstFlow)
                    .putString(KeyPostStartDataPlaneOutcome, receipt.postStartDataPlaneOutcome)
                    .commit()
            }
    }

private fun Context.deviceProtectedStorageContextOrSelf(): Context =
    runCatching { createDeviceProtectedStorageContext() }
        .getOrNull()
        ?: this

internal const val RemoteDeviceRecoveryReceiptPreferencesName = "remote_device_recovery_receipt"
private const val KeyGenerationHash = "generation_hash"
private const val KeyServiceInstanceHash = "service_instance_hash"
private const val KeyGeneration = "generation"
private const val KeyStartOrigin = "start_origin"
private const val KeyUserUnlocked = "user_unlocked"
private const val KeyAlwaysOn = "always_on"
private const val KeyLockdown = "lockdown"
private const val KeyServiceInstanceChanged = "service_instance_changed"
private const val KeyTimeToForegroundService = "time_to_foreground_service"
private const val KeyTimeToTun = "time_to_tun"
private const val KeyTimeToFirstFlow = "time_to_first_flow"
private const val KeyPostStartDataPlaneOutcome = "post_start_data_plane_outcome"
