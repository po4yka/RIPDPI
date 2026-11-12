package com.poyka.ripdpi.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WsTunnelWorkerTransportProvisioner
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val credentialStore: WsTunnelWorkerCredentialStore,
    ) {
        private val mutex = Mutex()

        @Suppress("TooGenericExceptionCaught")
        suspend fun provision(
            workerUrl: String,
            credentialRef: String,
            bearer: SecretString,
        ): CloudflareWorkerTransportConfig =
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                val requested = CloudflareWorkerTransportConfig(workerUrl.trim(), credentialRef.trim(), bearer)
                withContext(NonCancellable) {
                    val previousRef = appSettingsRepository.snapshot().wsTunnelWorkerCredentialRef.trim()
                    // A new reference keeps the old URL/bearer pair intact if the process dies before publication.
                    val config =
                        if (requested.credentialRef == previousRef ||
                            credentialStore.load(requested.credentialRef) != null
                        ) {
                            requested.copy(credentialRef = UUID.randomUUID().toString())
                        } else {
                            requested
                        }
                    try {
                        credentialStore.save(config.credentialRef, config.authBearer.value)
                        appSettingsRepository.update {
                            setWsTunnelWorkerUrl(config.workerUrl)
                            setWsTunnelWorkerCredentialRef(config.credentialRef)
                        }
                    } catch (failure: Throwable) {
                        discardUnpublished(config.credentialRef, failure)
                        throw failure
                    }
                    // Cleanup failure must never roll back an already published credential.
                    if (previousRef.isNotEmpty()) {
                        credentialStore.clear(previousRef)
                    }
                    config
                }
            }

        suspend fun clear() {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    val previousRef = appSettingsRepository.snapshot().wsTunnelWorkerCredentialRef.trim()
                    appSettingsRepository.update {
                        setWsTunnelWorkerUrl("")
                        setWsTunnelWorkerCredentialRef("")
                    }
                    if (previousRef.isNotEmpty()) credentialStore.clear(previousRef)
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun discardUnpublished(
            credentialRef: String,
            failure: Throwable,
        ) {
            try {
                // Keep the secret if publication succeeded or its durable state cannot be read.
                if (appSettingsRepository.snapshot().wsTunnelWorkerCredentialRef.trim() != credentialRef) {
                    credentialStore.clear(credentialRef)
                }
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
        }
    }
