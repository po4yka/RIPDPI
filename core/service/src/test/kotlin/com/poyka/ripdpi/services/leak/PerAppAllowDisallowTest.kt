package com.poyka.ripdpi.services.leak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-app allow/disallow tests: reconnect requirement and lockdown interactions.
 *
 * [PerAppPolicyEnforcer.requiresReconnect] must return true when the effective
 * package set changes so the OS re-establishes the VPN interface with the
 * updated routing rules.
 */
class PerAppAllowDisallowTest {
    private val enforcer = PerAppPolicyEnforcer()

    @Test
    fun identicalSetDoesNotRequireReconnect() {
        val apps = setOf("com.example.app", "com.another.app")
        assertFalse(enforcer.requiresReconnect(prev = apps, next = apps))
    }

    @Test
    fun addingAppRequiresReconnect() {
        val prev = setOf("com.example.app")
        val next = setOf("com.example.app", "com.new.app")
        assertTrue(enforcer.requiresReconnect(prev = prev, next = next))
    }

    @Test
    fun removingAppRequiresReconnect() {
        val prev = setOf("com.example.app", "com.remove.me")
        val next = setOf("com.example.app")
        assertTrue(enforcer.requiresReconnect(prev = prev, next = next))
    }

    @Test
    fun swappingAppsRequiresReconnect() {
        val prev = setOf("com.app.a")
        val next = setOf("com.app.b")
        assertTrue(enforcer.requiresReconnect(prev = prev, next = next))
    }

    @Test
    fun emptyToNonEmptyRequiresReconnect() {
        assertTrue(enforcer.requiresReconnect(prev = emptySet(), next = setOf("com.example.app")))
    }

    @Test
    fun nonEmptyToEmptyRequiresReconnect() {
        assertTrue(enforcer.requiresReconnect(prev = setOf("com.example.app"), next = emptySet()))
    }

    @Test
    fun lockdownEmptySetEquivalentToNoExclusions() {
        // Lockdown mode results in both prev and next being empty — no reconnect
        assertFalse(enforcer.requiresReconnect(prev = emptySet(), next = emptySet()))
    }

    @Test
    fun sameOrderDifferentContentRequiresReconnect() {
        val prev = setOf("com.app.one", "com.app.two")
        val next = setOf("com.app.one", "com.app.three")
        assertTrue(enforcer.requiresReconnect(prev = prev, next = next))
    }
}
