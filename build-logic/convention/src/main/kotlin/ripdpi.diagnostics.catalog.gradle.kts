import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateDiagnosticsCatalogTask : DefaultTask() {
    @get:Input
    val generatedAt: String = DiagnosticsCatalogGeneratedAt

    @get:OutputFile
    abstract val outputFile: org.gradle.api.file.RegularFileProperty

    @TaskAction
    fun generate() {
        val rendered = DiagnosticsCatalogDefinitions.renderCatalog().trimEnd() + "\n"
        val destination = outputFile.get().asFile
        destination.parentFile.mkdirs()
        if (!destination.exists() || destination.readText() != rendered) {
            destination.writeText(rendered)
        }
    }
}

abstract class CheckDiagnosticsCatalogTask : DefaultTask() {
    @get:Input
    val generatedAt: String = DiagnosticsCatalogGeneratedAt

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val committedCatalogFile: org.gradle.api.file.RegularFileProperty

    @TaskAction
    fun checkCatalog() {
        val expected = DiagnosticsCatalogDefinitions.renderCatalog().trimEnd() + "\n"
        val committed = committedCatalogFile.get().asFile.readText()
        if (committed != expected) {
            throw GradleException(
                "Bundled diagnostics catalog is out of date. Run :${project.path.trimStart(':')}:generateDiagnosticsCatalog",
            )
        }
    }
}

val diagnosticsCatalogAsset = layout.projectDirectory.file("src/main/assets/diagnostics/default_profiles.json")

val generateDiagnosticsCatalog =
    tasks.register<GenerateDiagnosticsCatalogTask>("generateDiagnosticsCatalog") {
        group = "build setup"
        description = "Generates the checked-in diagnostics catalog asset from typed definitions."
        outputFile.set(diagnosticsCatalogAsset)
    }

val checkDiagnosticsCatalog =
    tasks.register<CheckDiagnosticsCatalogTask>("checkDiagnosticsCatalog") {
        group = "verification"
        description = "Fails when the checked-in diagnostics catalog asset differs from generated output."
        committedCatalogFile.set(diagnosticsCatalogAsset)
    }

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(checkDiagnosticsCatalog)
}
