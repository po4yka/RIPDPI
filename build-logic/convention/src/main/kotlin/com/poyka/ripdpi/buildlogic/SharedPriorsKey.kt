package com.poyka.ripdpi.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider

/**
 * Single resolver for the shared-priors release public key, used by both the
 * cargo environment (`ripdpi.android.rust-native`) and the `:core:service`
 * BuildConfig flag so native and Kotlin always agree.
 *
 * Sources, first non-blank wins: Gradle property `sharedPriors.publicKey`,
 * `local.properties`, environment `RIPDPI_SHARED_PRIORS_PUB_KEY`. Blank values
 * are treated as absent. A non-blank value must be 64 hex characters and not
 * all zero, otherwise the build fails. The provider yields an empty string when
 * no key is configured.
 */
object SharedPriorsKey {
    const val PROPERTY: String = "sharedPriors.publicKey"
    const val ENV: String = "RIPDPI_SHARED_PRIORS_PUB_KEY"

    private val hexKey = Regex("[0-9a-fA-F]{64}")

    fun resolve(project: Project): Provider<String> =
        SharedPriorsBuildProperty.resolve(project, PROPERTY, ENV).map { key ->
            if (key.isEmpty()) key else validated(key)
        }

    internal fun validated(raw: String): String {
        val key = raw.trim()
        if (!hexKey.matches(key)) {
            throw GradleException("$PROPERTY / $ENV must be exactly 64 hex characters")
        }
        if (key.all { it == '0' }) {
            throw GradleException("$PROPERTY / $ENV must not be the all-zero key")
        }
        return key
    }
}
