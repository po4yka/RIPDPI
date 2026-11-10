package com.poyka.ripdpi.data

import java.util.Base64

internal fun validateSshProfile(profile: ProxyProfile.Ssh) {
    require(profile.server.isNotBlank()) { "SSH requires a server hostname" }
    require(profile.serverPort in SshPortRange) { "SSH requires a valid server port" }
    require(profile.username.isNotBlank()) { "SSH requires a username" }
    when (profile.authType) {
        RelaySshAuthTypePassword -> require(!profile.password.isNullOrEmpty()) { "SSH requires a password" }
        RelaySshAuthTypePrivateKey -> require(!profile.privateKey.isNullOrBlank()) { "SSH requires a private key" }
        else -> throw IllegalArgumentException("SSH requires a supported authentication type")
    }
    // Discovery is an editor action; durable activation must already have an accepted pin.
    require(isValidSshHostKeyFingerprint(profile.hostKeyFingerprint)) { "SSH requires an accepted SHA256 host key" }
}

private fun isValidSshHostKeyFingerprint(value: String?): Boolean {
    val fingerprint = value?.trim().orEmpty()
    val body = fingerprint.removePrefix("SHA256:")
    if (!fingerprint.startsWith("SHA256:") ||
        body.length !in SshFingerprintUnpaddedLength..SshFingerprintPaddedLength
    ) {
        return false
    }
    return listOf(
        Base64.getDecoder() to Base64.getEncoder(),
        Base64.getUrlDecoder() to Base64.getUrlEncoder(),
    ).any { (decoder, encoder) ->
        val digest = runCatching { decoder.decode(body) }.getOrNull()
        digest?.size == SshFingerprintBytes &&
            encoder.withoutPadding().encodeToString(digest) == body.removeSuffix("=")
    }
}

private const val SshFingerprintBytes = 32
private const val SshFingerprintUnpaddedLength = 43
private const val SshFingerprintPaddedLength = 44
private val SshPortRange = 1..65535
