import com.android.build.api.dsl.CommonExtension
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

the<CommonExtension>().apply {
    buildFeatures.compose = true
}

extensions.findByType<ComposeCompilerGradlePluginExtension>()?.apply {
    stabilityConfigurationFile.set(project.rootProject.layout.projectDirectory.file("app/compose-stability.conf"))

    val generateReports = providers.environmentVariable("CI").isPresent ||
        providers.gradleProperty("ripdpi.composeReports").map { it.toBoolean() }.getOrElse(false)

    if (generateReports) {
        reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
        metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
    }
}
