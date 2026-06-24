package com.poyka.ripdpi.data

/**
 * In-memory [ProxyGroupBlobStore] for JVM unit tests. The production binding
 * (`KeystoreProxyGroupBlobStore`) AES-256-GCM seals the blob via AndroidKeyStore,
 * which is unavailable under Robolectric; this fake substitutes a plain in-memory
 * blob so the repository wiring is testable, mirroring `FakeAwgCredentialStore` /
 * `FakeWarpCredentialStore`.
 */
class FakeProxyGroupBlobStore(
    private var blob: String? = null,
) : ProxyGroupBlobStore {
    override fun read(): String? = blob

    override fun write(json: String) {
        blob = json
    }

    override fun clear() {
        blob = null
    }
}
