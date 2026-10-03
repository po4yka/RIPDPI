package com.poyka.ripdpi.services

/** Only runtime inputs relevant to candidate resolution; never includes profile secrets. */
data class CandidateRelayProbeEnvironment(
    val vpnProtectionRequired: Boolean,
    val tlsProfile: String,
    val experiments: RuntimeExperimentSelection,
    val quicBindLowPort: Boolean,
    val quicMigrateAfterHandshake: Boolean,
)
