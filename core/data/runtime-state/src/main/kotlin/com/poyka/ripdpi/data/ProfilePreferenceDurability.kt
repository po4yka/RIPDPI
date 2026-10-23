package com.poyka.ripdpi.data

import android.content.SharedPreferences
import java.util.UUID
import java.util.WeakHashMap

/** A failed commit may already have changed Android's process-shared preferences cache. */
internal class ProfilePreferenceDurability private constructor() {
    private var uncertain = false

    /** Confirm an uncertain cache before any instance can expose it or start another mutation. */
    fun <T> access(
        preferences: SharedPreferences,
        block: () -> T,
    ): T =
        synchronized(this) {
            if (uncertain) commit(preferences.edit())
            block()
        }

    fun commit(editor: SharedPreferences.Editor) =
        synchronized(this) {
            uncertain = true
            // A changing metadata value forces a write even when the retried payload is identical.
            editor.putString(ConfirmationKey, UUID.randomUUID().toString()).commitOrThrow()
            uncertain = false
        }

    companion object {
        private const val ConfirmationKey = "durability-confirmation"
        private val owners = WeakHashMap<SharedPreferences, ProfilePreferenceDurability>()

        fun forPreferences(preferences: SharedPreferences): ProfilePreferenceDurability =
            synchronized(owners) {
                owners.getOrPut(preferences) { ProfilePreferenceDurability() }
            }
    }
}
