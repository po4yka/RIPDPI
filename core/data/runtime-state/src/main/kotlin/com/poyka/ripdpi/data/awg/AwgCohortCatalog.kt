package com.poyka.ripdpi.data.awg

import android.content.Context
import com.poyka.ripdpi.data.wireguard.WireGuardConfParser
import com.poyka.ripdpi.serialization.RipDpiLenientJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val AwgCohortAssetPath = "awg-cohorts.json"

/**
 * The set of object keys a well-formed cohort preset is allowed to carry.
 *
 * `s3`/`s4` and `i1`..`i5` are AmneziaWG v1.5 obfuscation extensions. They are
 * intentionally NOT in [REQUIRED_PRESET_KEYS] because the shipped presets
 * predate them. `s3`/`s4` remain recognized for compatibility, but strict
 * validation accepts only absent or zero values (amneziawg-go#110).
 */
private val ALLOWED_PRESET_KEYS =
    setOf(
        "id",
        "displayNameKey",
        "descriptionKey",
        "jc",
        "jmin",
        "jmax",
        "s1",
        "s2",
        "s3",
        "s4",
        "h1",
        "h2",
        "h3",
        "h4",
        "i1",
        "i2",
        "i3",
        "i4",
        "i5",
        "randomizeHeaders",
    )

/** The set of preset keys that must be present and non-null. */
private val REQUIRED_PRESET_KEYS =
    setOf(
        "id",
        "displayNameKey",
        "descriptionKey",
        "jc",
        "jmin",
        "jmax",
        "s1",
        "s2",
        "h1",
        "h2",
        "h3",
        "h4",
        "randomizeHeaders",
    )

private val awgCohortCatalogJson =
    RipDpiLenientJson

/** Strict reader used only by [AwgCohortCatalog.strictValidate]; rejects unknown keys. */
private val awgCohortStrictJson =
    Json.Default

/**
 * One AmneziaWG obfuscation cohort preset, mirroring a row of the deployer's
 * `ripdpi-vpn-deploy/docs/AWG-COHORTS.md` table.
 *
 * The obfuscation numbers (`jc`, `jmin`, `jmax`, `s1`, `s2`, `s3`, `s4`,
 * `h1`..`h4`, `i1`..`i5`) are server-coordinated and must exactly match what the
 * server sends; when a preset is selected they are not user-editable.
 *
 * [s3]/[s4] and [i1]..[i5] are the AmneziaWG v1.5 obfuscation extensions. They
 * default to `null` (not pinned) so the shipped presets — which predate the
 * extension — decode unchanged; only a preset that explicitly pins them carries
 * a value, and a `null` field is excluded from cohort matching (see
 * [matchCohortForConf]). Build validation permits only absent or zero `s3`/`s4`
 * while amneziawg-go#110 remains open.
 *
 * [displayNameKey] / [descriptionKey] are `strings.xml` resource keys, never
 * literal localized text — the asset JSON carries no human-readable labels.
 *
 * [randomizeHeaders] records the deployer's intent for `h1`..`h4`: `false`
 * cohorts (e.g. `rtk_south`) pin literal header values, `true` cohorts ship a
 * representative set that a future provisioning step is expected to
 * re-randomise per peer.
 */
@Serializable
data class AwgCohortPreset(
    val id: String,
    @SerialName("displayNameKey") val displayNameKey: String,
    @SerialName("descriptionKey") val descriptionKey: String,
    val jc: Int,
    val jmin: Int,
    val jmax: Int,
    val s1: Int,
    val s2: Int,
    val s3: Int? = null,
    val s4: Int? = null,
    val h1: Long,
    val h2: Long,
    val h3: Long,
    val h4: Long,
    val i1: String? = null,
    val i2: String? = null,
    val i3: String? = null,
    val i4: String? = null,
    val i5: String? = null,
    val randomizeHeaders: Boolean,
)

/** On-disk shape of the `awg-cohorts.json` asset. */
@Serializable
internal data class AwgCohortCatalogPayload(
    val presets: List<AwgCohortPreset> = emptyList(),
)

/**
 * An in-memory AmneziaWG cohort catalog. A [presets] list of zero is the
 * documented degraded state: a malformed asset boots the app with an empty
 * catalog and the picker shows only "Custom".
 */
data class AwgCohortCatalogData(
    val presets: List<AwgCohortPreset>,
) {
    /** Returns the preset with [id], or `null` when absent. */
    fun find(id: String): AwgCohortPreset? = presets.firstOrNull { it.id == id }
}

/**
 * Decodes the `awg-cohorts.json` payload into an [AwgCohortCatalogData].
 *
 * Malformed JSON does not throw: it degrades to an empty catalog, matching the
 * acceptance criterion that the app boots with an empty catalog and the picker
 * shows only "Custom" rather than crashing.
 */
fun decodeAwgCohortCatalog(json: String): AwgCohortCatalogData {
    val payload =
        runCatching {
            awgCohortCatalogJson.decodeFromString(AwgCohortCatalogPayload.serializer(), json)
        }.getOrNull() ?: return AwgCohortCatalogData(presets = emptyList())
    return AwgCohortCatalogData(presets = payload.presets)
}

/**
 * Applies [preset] to [form], rewriting exactly the obfuscation fields and the
 * `cohortId` tag. Server, port, and key material are preserved.
 */
fun applyCohortPreset(
    form: AwgProfileForm,
    preset: AwgCohortPreset,
): AwgProfileForm =
    form.copy(
        jc = preset.jc,
        jmin = preset.jmin,
        jmax = preset.jmax,
        s1 = preset.s1,
        s2 = preset.s2,
        s3 = preset.s3 ?: 0,
        s4 = preset.s4 ?: 0,
        h1 = preset.h1,
        h2 = preset.h2,
        h3 = preset.h3,
        h4 = preset.h4,
        i1 = preset.i1.orEmpty(),
        i2 = preset.i2.orEmpty(),
        i3 = preset.i3.orEmpty(),
        i4 = preset.i4.orEmpty(),
        i5 = preset.i5.orEmpty(),
        cohortId = preset.id,
    )

/**
 * Tags an imported AmneziaWG `.conf` with a cohort id when its obfuscation
 * params byte-match a catalog preset, otherwise `"Custom"`.
 *
 * The `.conf` is parsed with the existing [WireGuardConfParser]; a vanilla
 * WireGuard config (no AWG keys) or a structurally malformed config is
 * `"Custom"`. When several presets share the same numeric params (e.g.
 * `default` and `home_isp_broad`), the first matching catalog entry wins.
 */
@Suppress("ReturnCount")
fun matchCohortForConf(
    conf: String,
    catalog: AwgCohortCatalogData,
): String {
    val parsed =
        runCatching { WireGuardConfParser.parse(conf) }.getOrNull()
            ?: return "Custom"
    val awg =
        when (parsed) {
            is com.poyka.ripdpi.data.wireguard.AmneziaWgConfig -> parsed.awg
            else -> return "Custom"
        }
    return catalog.presets
        .firstOrNull { preset -> preset.matches(awg) }
        ?.id
        ?: "Custom"
}

/**
 * True when every obfuscation param this preset *pins* equals the parsed config.
 *
 * The legacy params (`jc`/`jmin`/`jmax`/`s1`/`s2`/`h1`..`h4`) are always
 * compared. The AmneziaWG v1.5 extension params (`s3`/`s4`/`i1`..`i5`) are
 * nullable on the preset and are constrained only when the preset pins them —
 * a preset that predates the extension (all-`null`) keeps matching a `.conf`
 * regardless of whether that `.conf` carries safe zero S3/S4 or I values,
 * preserving the pre-extension matching behaviour.
 */
private fun AwgCohortPreset.matches(awg: com.poyka.ripdpi.data.wireguard.AmneziaWgParameters): Boolean =
    // Legacy params (predate the v1.5 extension) are always compared.
    jc == awg.jc &&
        jmin == awg.jmin &&
        jmax == awg.jmax &&
        s1 == awg.s1 &&
        s2 == awg.s2 &&
        h1 == awg.h1 &&
        h2 == awg.h2 &&
        h3 == awg.h3 &&
        h4 == awg.h4 &&
        // Extension params constrain the match only when this preset pins them.
        pinnedEquals(s3, awg.s3) &&
        pinnedEquals(s4, awg.s4) &&
        pinnedEquals(i1, awg.i1) &&
        pinnedEquals(i2, awg.i2) &&
        pinnedEquals(i3, awg.i3) &&
        pinnedEquals(i4, awg.i4) &&
        pinnedEquals(i5, awg.i5)

/**
 * Cohort-match rule for a nullable extension param: a `null` [pinned] value (the
 * preset does not pin the field) always matches; a non-`null` value must equal
 * the parsed config's [actual].
 */
private fun <T> pinnedEquals(
    pinned: T?,
    actual: T?,
): Boolean = pinned == null || pinned == actual

/**
 * Catalog loader plus the build-time validation entry point.
 *
 * [load] is the runtime hook: it reads the `awg-cohorts.json` Android asset and
 * decodes it, degrading to an empty catalog on any failure. A future remote
 * refresh would plug in by replacing the asset read with a cached remote fetch
 * — that refresh itself is intentionally out of scope here.
 */
@Singleton
class AwgCohortCatalog
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val catalog: AwgCohortCatalogData by lazy {
            runCatching {
                context.assets.open(AwgCohortAssetPath).bufferedReader().use { reader ->
                    decodeAwgCohortCatalog(reader.readText())
                }
            }.getOrElse { AwgCohortCatalogData(presets = emptyList()) }
        }

        /** All shipped presets; empty when the asset is missing or malformed. */
        fun all(): List<AwgCohortPreset> = catalog.presets

        /** Returns the preset with [id], or `null`. */
        fun find(id: String): AwgCohortPreset? = catalog.find(id)

        companion object {
            /**
             * Strictly validates a catalog [json] payload against the
             * [AwgCohortPreset] model. Returns a list of human-readable errors;
             * an empty list means the catalog is valid.
             *
             * This is the logic a Gradle `validateAwgCohortCatalog` task (and
             * its unit-test stand-in) calls: it fails on a missing required
             * field or an unknown key. Unlike [decodeAwgCohortCatalog], it does
             * not silently degrade — strict validation is meant to fail loud.
             */
            @Suppress("ReturnCount")
            fun strictValidate(json: String): List<String> {
                val errors = mutableListOf<String>()
                val root =
                    runCatching { awgCohortStrictJson.parseToJsonElement(json) }.getOrNull()
                        ?: return listOf("catalog JSON is not parseable")
                val presets =
                    (root as? JsonObject)?.get("presets") as? kotlinx.serialization.json.JsonArray
                        ?: return listOf("catalog JSON has no 'presets' array")

                presets.forEachIndexed { index, element ->
                    val obj =
                        element as? JsonObject ?: run {
                            errors += "preset[$index] is not a JSON object"
                            return@forEachIndexed
                        }
                    val label = (obj["id"] as? JsonPrimitive)?.content ?: "index $index"

                    // Unknown keys.
                    obj.keys
                        .filterNot { it in ALLOWED_PRESET_KEYS }
                        .forEach { unknown ->
                            errors += "preset '$label' has unknown key '$unknown'"
                        }

                    // Missing required fields.
                    REQUIRED_PRESET_KEYS
                        .filterNot { it in obj.keys }
                        .forEach { missing ->
                            errors += "preset '$label' is missing required field '$missing'"
                        }

                    // Numeric fields must actually be numbers. s3/s4 are optional
                    // (v1.5 extension) but, when present, must still be integers.
                    listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4").forEach { key ->
                        val primitive = obj[key] as? JsonPrimitive
                        if (primitive != null && primitive.intOrNull == null) {
                            errors += "preset '$label' field '$key' is not an integer"
                        }
                    }
                    listOf("h1", "h2", "h3", "h4").forEach { key ->
                        val primitive = obj[key] as? JsonPrimitive
                        if (primitive != null && primitive.longOrNull == null) {
                            errors += "preset '$label' field '$key' is not an integer"
                        }
                    }
                    listOf("s3", "s4").forEach { key ->
                        val value = (obj[key] as? JsonPrimitive)?.intOrNull
                        if (value != null && value != 0) {
                            errors +=
                                "preset '$label' field '$key' must be 0 for Android arm64 safety " +
                                "(amneziawg-go#110)"
                        }
                    }
                }
                return errors
            }
        }
    }
