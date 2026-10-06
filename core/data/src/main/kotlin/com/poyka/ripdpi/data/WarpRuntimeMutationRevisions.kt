package com.poyka.ripdpi.data

interface WarpRuntimeRevisionReader {
    fun warpRuntimeRevision(profileId: String): Long
}

/** Process-local ABA fence; mutations become visible only after encrypted journal completion. */
internal class WarpRuntimeMutationRevisions : WarpRuntimeRevisionReader {
    override fun warpRuntimeRevision(profileId: String): Long = current(profileId)

    private val revisions = mutableMapOf<String, Long>()
    private var epoch = 0L

    @Synchronized fun current(profileId: String): Long = revisions[profileId] ?: epoch

    @Synchronized fun changed(profileId: String) {
        revisions[profileId] = current(profileId) + 1L
    }

    @Synchronized fun invalidateAll() {
        epoch = maxOf(epoch, revisions.values.maxOrNull() ?: epoch) + 1L
        revisions.clear()
    }
}

internal fun WarpCredentials?.sameRuntimeProvisioningMaterial(other: WarpCredentials?): Boolean =
    this?.provisioningMaterial() == other?.provisioningMaterial()

private fun WarpCredentials.provisioningMaterial(): List<String?> =
    listOf(
        profileId,
        accountKind,
        deviceId,
        accessToken,
        clientId,
        privateKey,
        publicKey,
        peerPublicKey,
        interfaceAddressV4,
        interfaceAddressV6,
    )
