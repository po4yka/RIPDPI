package com.poyka.ripdpi.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider

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
