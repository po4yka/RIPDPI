import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

private const val NativeResourcesArtifactType = "ripdpi-robolectric-native-resources"

/** Keep the original native JAR; place its unchanged font data before it on JVM test classpaths. */
internal fun Project.configureRobolectricNativeResources() {
    dependencies.registerTransform(RobolectricNativeResourcesTransform::class.java) {
        from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
        to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, NativeResourcesArtifactType)
    }
    val unitTests =
        extensions.findByType(ApplicationExtension::class.java)?.testOptions?.unitTests
            ?: extensions.getByType(LibraryExtension::class.java).testOptions.unitTests
    // AGP applies this callback after it sets the original unit-test classpath.
    unitTests.all { task ->
        val variantName = task.name.removePrefix("test").replaceFirstChar(Char::lowercaseChar)
        val configuration = configurations.getByName("${variantName}RuntimeClasspath")
        val nativeResources =
            configuration.incoming
                .artifactView {
                    componentFilter { id ->
                        id is ModuleComponentIdentifier &&
                            id.group == "org.robolectric" && id.module == "nativeruntime-dist-compat"
                    }
                    attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, NativeResourcesArtifactType)
                }.files
        task.classpath = nativeResources + task.classpath
    }
}

@CacheableTransform
abstract class RobolectricNativeResourcesTransform : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        extractRobolectricNativeResources(inputArtifact.get().asFile, outputs.dir("resources"))
    }
}

internal fun extractRobolectricNativeResources(
    jar: File,
    output: File,
) {
    val outputPath = output.toPath().toAbsolutePath().normalize()
    val roots = listOf("fonts", "hyphen-data")
    val counts = roots.associateWith { 0 }.toMutableMap()
    ZipFile(jar).use { archive ->
        for (entry in archive.entries().asSequence()) {
            val root = roots.firstOrNull { entry.name.startsWith("$it/") } ?: continue
            if (entry.isDirectory) continue
            val destination = outputPath.resolve(entry.name).normalize()
            require(destination.startsWith(outputPath.resolve(root))) { "Unsafe native resource path: ${entry.name}" }
            Files.createDirectories(destination.parent)
            archive.getInputStream(entry).use { source ->
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            counts[root] = counts.getValue(root) + 1
        }
    }
    require(counts.values.all { it > 0 }) { "Native runtime JAR must contain fonts and hyphen data: $jar" }
}
