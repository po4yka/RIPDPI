import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

plugins {
    id("ripdpi.android.coverage")
    id("ripdpi.android.library")
    id("ripdpi.android.protobuf")
    id("ripdpi.android.quality")
    id("ripdpi.android.serialization")
}

extensions.configure<LibraryExtension> {
    namespace = "com.poyka.ripdpi.core.data.model"
    flavorDimensions += "runtimeEdition"
    productFlavors {
        create("standard") { dimension = "runtimeEdition" }
        create("extended") { dimension = "runtimeEdition" }
    }
}

@CacheableTask
abstract class PrepareStandardSettingsSchema : DefaultTask() {
    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val source = sourceDir.get()
        val output = outputDir.get()
        val settingsSchema = "app_settings.proto"
        val schema = source.file(settingsSchema).asFile.readText()
        fileSystemOperations.sync {
            from(source)
            into(output)
        }
        val standardSchema =
            schema.reserveEditionSetting("root_mode_enabled", 135).reserveEditionSetting("fake_md5sig", 331)
        output.file(settingsSchema).asFile.writeText(standardSchema)
    }

    private fun String.reserveEditionSetting(
        name: String,
        number: Int,
    ): String {
        val declaration = Regex("^  bool $name = $number;$", RegexOption.MULTILINE)
        val legacyField =
            checkNotNull(declaration.findAll(this).singleOrNull()) {
                "Expected the standard schema to remove edition setting $name at field $number."
            }
        return replaceRange(legacyField.range, "  reserved $number;\n  reserved \"$name\";")
    }
}

val prepareStandardSettingsSchema =
    tasks.register<PrepareStandardSettingsSchema>("prepareStandardSettingsSchema") {
        sourceDir.set(layout.projectDirectory.dir("src/main/proto"))
        outputDir.set(layout.buildDirectory.dir("generated/schema/standard"))
    }

extensions.configure<MapProperty<String, Directory>>("protoLiteSourceDirectories") {
    put("standard", prepareStandardSettingsSchema.flatMap { it.outputDir })
}

dependencies {
    api(libs.ipaddress)
    api(libs.protobuf.javalite)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.unit.test)
}
