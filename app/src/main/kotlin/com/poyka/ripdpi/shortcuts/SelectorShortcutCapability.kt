package com.poyka.ripdpi.shortcuts

import android.content.Context
import android.content.Intent
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

const val ExtraSelectorCapability = "com.poyka.ripdpi.extra.SELECTOR_CAPABILITY"

private const val CapabilityPreferences = "selector_shortcut_capability"
private const val CapabilityKey = "hmac_key"
private const val CapabilityKeyBytes = 32
private const val HmacSha256 = "HmacSHA256"

@Singleton
class SelectorShortcutCapability
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val keyLock = Any()

        fun sign(
            groupId: String,
            profileId: String,
        ): String = encode(hmac(groupId, profileId))

        fun verifies(intent: Intent?): Boolean {
            val groupId = intent?.getStringExtra(ExtraSelectGroupId)
            val profileId = intent?.getStringExtra(ExtraSelectProfileId)
            val supplied = intent?.getStringExtra(ExtraSelectorCapability)?.decodeCapability()
            return if (groupId != null && profileId != null && supplied != null) {
                MessageDigest.isEqual(hmac(groupId, profileId), supplied)
            } else {
                false
            }
        }

        private fun hmac(
            groupId: String,
            profileId: String,
        ): ByteArray =
            Mac.getInstance(HmacSha256).run {
                init(SecretKeySpec(key(), HmacSha256))
                doFinal(capabilityPayload(groupId, profileId))
            }

        private fun key(): ByteArray =
            synchronized(keyLock) {
                val preferences = context.getSharedPreferences(CapabilityPreferences, Context.MODE_PRIVATE)
                preferences.getString(CapabilityKey, null)?.decodeCapability()?.let { return@synchronized it }
                val generated = ByteArray(CapabilityKeyBytes).also(SecureRandom()::nextBytes)
                check(preferences.edit().putString(CapabilityKey, encode(generated)).commit()) {
                    "Unable to persist selector shortcut capability"
                }
                generated
            }

        private fun String.decodeCapability(): ByteArray? =
            runCatching { Base64.decode(this, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }.getOrNull()

        private fun encode(value: ByteArray): String =
            Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        private fun capabilityPayload(
            groupId: String,
            profileId: String,
        ): ByteArray {
            val groupBytes = groupId.toByteArray(StandardCharsets.UTF_8)
            val profileBytes = profileId.toByteArray(StandardCharsets.UTF_8)
            return ByteBuffer
                .allocate(Int.SIZE_BYTES * 2 + groupBytes.size + profileBytes.size)
                .putInt(groupBytes.size)
                .put(groupBytes)
                .putInt(profileBytes.size)
                .put(profileBytes)
                .array()
        }
    }
