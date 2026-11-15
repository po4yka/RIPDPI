package com.poyka.ripdpi.buildlogic

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.io.StringReader
import java.util.Properties

/** Resolve a shared-priors input from the first non-blank build source. */
object SharedPriorsBuildProperty {
    fun resolve(
        project: Project,
        property: String,
        environment: String,
    ): Provider<String> {
        val providers = project.providers
        val localProperties =
            providers
                .fileContents(
                    project.rootProject.layout.projectDirectory
                        .file("local.properties"),
                ).asText
                .map { text -> Properties().apply { load(StringReader(text)) }.getProperty(property).orEmpty() }
        return providers
            .gradleProperty(property)
            .nonBlank()
            .orElse(localProperties.nonBlank())
            .orElse(providers.environmentVariable(environment).nonBlank())
            .orElse("")
    }

    private fun Provider<String>.nonBlank(): Provider<String> = map(String::trim).filter(String::isNotEmpty)
}
