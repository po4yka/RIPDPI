// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs detekt, ktlint, and Android Lint across all modules"
    dependsOn(
        subprojects.flatMap { it.tasks.matching { t -> t.name == "detekt" } },
        subprojects.flatMap { it.tasks.matching { t -> t.name == "lintDebug" } },
        subprojects.flatMap { it.tasks.matching { t -> t.name == "ktlintCheck" } },
    )
}
