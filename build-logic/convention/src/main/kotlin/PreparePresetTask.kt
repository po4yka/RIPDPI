import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@DisableCachingByDefault(because = "Reads a credential-bearing archive; must never enter the build cache")
abstract class PreparePresetTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archive: RegularFileProperty

    @get:Internal
    abstract val passphrase: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val schemaSource: RegularFileProperty

    @get:OutputFile
    abstract val presetFile: RegularFileProperty

    @TaskAction
    fun prepare() {
        presetFile.get().asFile.delete()
        val bytes = archive.get().asFile.readBytes()
        val json = if (bytes.startsWithMagic()) decrypt(bytes) else bytes.toString(Charsets.UTF_8)
        val document =
            runCatching { Json.parseToJsonElement(json).jsonObject }
                .getOrElse { throw GradleException("The archive is not a backup document") }
        val version = document["schemaVersion"]?.jsonPrimitive?.intOrNull
        val supported = supportedVersions()
        if (version == null || version !in supported) {
            throw GradleException("Backup schema version $version is not supported by this checkout ($supported)")
        }
        requireBootstrapSlotOnly(document)
        val preset = JsonObject(document - "privateData" + ("containsCredentials" to JsonPrimitive(false)))
        presetFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(preset.toString())
        }
    }

    private fun requireBootstrapSlotOnly(document: JsonObject) {
        if ((document["profiles"] as? JsonArray).orEmpty().isNotEmpty()) {
            throw GradleException("The archive carries profiles; a preset ships no credentials")
        }
        val groups = (document["groups"] as? JsonArray).orEmpty().map { it.jsonObject }
        var slots = 0
        groups.forEach { group ->
            val id = group["id"]?.jsonPrimitive?.contentOrNull
            if ((group["members"] as? JsonArray).orEmpty().isNotEmpty()) {
                throw GradleException("Group $id has members; a preset ships no credentials")
            }
            val subscription = group["subscription"] as? JsonObject ?: return@forEach
            if (!subscription.isPendingBootstrap()) {
                throw GradleException("Group $id has a subscription that is not a pending BOOTSTRAP slot")
            }
            slots++
        }
        if (slots == 0) {
            throw GradleException("The archive has no group with a pending BOOTSTRAP subscription")
        }
    }

    private fun JsonObject.isPendingBootstrap(): Boolean =
        this["kind"]?.jsonPrimitive?.contentOrNull == "BOOTSTRAP" &&
            (this["consumedAt"] ?: JsonNull) == JsonNull &&
            !this["link"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() &&
            this["token"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty() &&
            ((this["mirrors"] as? JsonObject)?.get("mirrors") as? JsonArray).orEmpty().isEmpty()

    private fun supportedVersions(): IntRange {
        val source = schemaSource.get().asFile.readText()

        fun constant(name: String): Int =
            Regex("""const val $name: Int = (\d+)""")
                .find(source)
                ?.groupValues
                ?.get(1)
                ?.toInt()
                ?: throw GradleException("$name not found in ${schemaSource.get().asFile.name}")
        return constant("OldestSupportedBackupVersion")..constant("BackupSchemaVersion")
    }

    private fun decrypt(bytes: ByteArray): String {
        if (bytes.size <= HEADER_BYTES) throw GradleException("The archive is truncated")
        val secret =
            (passphrase.orNull ?: System.getenv(PASSPHRASE_ENV))?.takeIf(String::isNotEmpty)
                ?: throw GradleException("Set $PASSPHRASE_ENV to the export passphrase")
        val input = DataInputStream(ByteArrayInputStream(bytes))
        input.skipBytes(MAGIC.size)
        val iterations = input.readInt()
        if (iterations != ITERATIONS) throw GradleException("Unsupported archive header")
        val salt = ByteArray(SALT_BYTES).also(input::readFully)
        val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
        return try {
            val key =
                SecretKeyFactory
                    .getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(PBEKeySpec(secret.toCharArray(), salt, iterations, KEY_BITS))
                    .encoded
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(bytes, 0, HEADER_BYTES)
            cipher.doFinal(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES).toString(Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            throw GradleException("Wrong passphrase or damaged archive")
        }
    }

    private fun ByteArray.startsWithMagic(): Boolean =
        size >= MAGIC.size && copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    private companion object {
        val MAGIC = "RIPDPIE1".toByteArray(Charsets.US_ASCII)
        const val PASSPHRASE_ENV = "RIPDPI_PRESET_PASSPHRASE"
        const val ITERATIONS = 210_000
        const val SALT_BYTES = 16
        const val NONCE_BYTES = 12
        const val KEY_BITS = 256
        const val GCM_TAG_BITS = 128
        const val HEADER_BYTES = 8 + Int.SIZE_BYTES + SALT_BYTES + NONCE_BYTES
    }
}
