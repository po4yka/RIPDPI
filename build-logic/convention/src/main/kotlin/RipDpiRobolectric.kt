import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.CommandLineArgumentProvider

internal fun Project.configureRipDpiRobolectric() {
    configureRobolectricNativeResources()
    val toolchains = extensions.getByType(JavaToolchainService::class.java)
    val sdkDirectory = rootProject.layout.buildDirectory.dir("robolectric-sdks")
    tasks.withType(Test::class.java).configureEach {
        dependsOn(":prepareRobolectricSdks")
        jvmArgumentProviders.add(
            project.objects.newInstance(RobolectricSdkArguments::class.java).apply { directory.set(sdkDirectory) },
        )
        javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
        systemProperty("robolectric.looperMode", "PAUSED")
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        jvmArgs(
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.security=ALL-UNNAMED",
            "--add-opens=java.base/java.text=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        )
    }
}

internal abstract class RobolectricSdkArguments : CommandLineArgumentProvider {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val directory: DirectoryProperty

    override fun asArguments(): Iterable<String> =
        listOf("-Drobolectric.dependency.dir=${directory.get().asFile.absolutePath}")
}
