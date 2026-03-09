import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

extensions.configure<KtlintExtension> {
    version.set("1.8.0")
    android.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    filter {
        exclude("**/build/**")
        exclude("**/generated/**")
    }
}
