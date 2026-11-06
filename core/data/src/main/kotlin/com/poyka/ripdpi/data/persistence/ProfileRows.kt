package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.awg.AwgProfileDao

/** The only synchronous storage surface used by a coordinated profile mutation. */
internal class ProfileRows(
    val records: ProfileRecordAccess,
    val awgProfiles: AwgProfileDao,
    val rules: ProfileRoutingDao,
) {
    val relayProfiles = RelayProfileRows(records)
    val relayCredentials = RelayCredentialRows(records)
    val warpProfiles = WarpProfileRows(records)
    val warpCredentials = WarpCredentialRows(records)
    val warpEndpoints = WarpEndpointRows(records)
    val xrayMetadata = XrayMetadataRows(records)
    val xraySecrets = XraySecretRows(records)
    val awgCredentials = AwgCredentialRows(records)
    val settings = SettingsRows(records)
    val selection = ProfileSelectionRows(records)
    val groups = ProxyGroupRows(records)
}
