package com.poyka.ripdpi.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [ProxyGroupBlobStore] for JVM unit tests. The production binding
 * (`KeystoreProxyGroupBlobStore`) AES-256-GCM seals the blob via AndroidKeyStore,
 * which is unavailable under Robolectric; this fake substitutes a plain in-memory
 * blob so the repository wiring is testable, mirroring `FakeAwgCredentialStore` /
 * `FakeWarpCredentialStore`.
 */
class FakeProxyGroupBlobStore(
    blob: String? = null,
) : ProxyGroupBlobStore {
    private val state = MutableStateFlow(blob)
    private val mutex = Mutex()

    override suspend fun read(): String? = state.value

    override suspend fun write(json: String) {
        update { json }
    }

    override suspend fun clear() {
        update { null }
    }

    override suspend fun update(transform: (String?) -> String?): String? =
        mutex.withLock {
            transform(state.value).also { state.value = it }
        }

    override fun observe() = state.asStateFlow()
}
