package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiAmneziaWgConfig
import javax.inject.Inject
import javax.inject.Singleton

/** The supervisor publishes consumed configuration only while its exact session remains alive. */
@Singleton
class ActiveAwgRuntimeRegistry
    @Inject
    constructor() {
        internal class Registration(
            val configuration: ResolvedRipDpiAmneziaWgConfig,
            val alive: () -> Boolean,
        ) {
            override fun toString(): String = "AwgRuntimeRegistration([REDACTED])"
        }

        @Volatile private var active: Registration? = null

        @Synchronized
        internal fun register(
            configuration: ResolvedRipDpiAmneziaWgConfig,
            alive: () -> Boolean,
        ): Registration = Registration(configuration, alive).also { active = it }

        @Synchronized
        internal fun revoke(registration: Registration?) {
            if (active === registration) active = null
        }

        internal fun capture(configuration: ResolvedRipDpiAmneziaWgConfig): Registration? =
            active?.takeIf { it.configuration == configuration && it.alive() }

        internal fun isCurrent(registration: Registration): Boolean = active === registration && registration.alive()
    }
