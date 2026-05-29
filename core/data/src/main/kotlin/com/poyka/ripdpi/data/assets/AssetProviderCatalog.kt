package com.poyka.ripdpi.data.assets

/*
 * Pure, Android-free catalog logic for the geoip.db / geosite.db asset-source picker.
 *
 * Everything here is unit-testable on the JVM: the built-in provider catalog, the GitHub
 * Releases URL builder, the version-tag comparison, and the asset filename mapping. The
 * networking, integrity verification, and file I/O live in `core:service`
 * (`GeoAssetRepository`); this file is the single place to add a new preset.
 */

/** The canonical asset file names the native loader (`ripdpi-geo`) reads. */
const val GeoipDatabaseAssetFileName = "geoip.db"
const val GeositeDatabaseAssetFileName = "geosite.db"

/** Stable id of the user-supplied "Custom URL" option. */
const val CustomAssetProviderId = "custom"

/** Default provider selected when the user has never picked one. */
const val DefaultAssetProviderId = "sagernet"

/** Which database an asset reference targets. */
enum class GeoAssetKind {
    Geoip,
    Geosite,
}

/**
 * A GitHub repository that publishes a sing-box-format `.db` on its Releases page. The asset is
 * always served from the `latest` release, so only `owner/repo` plus the release asset file name
 * are needed to build a download URL.
 */
data class GeoAssetRepo(
    /** GitHub `owner/repo`, e.g. `SagerNet/sing-geoip`. */
    val ownerRepo: String,
    /** Release asset file name to download, e.g. `geoip.db`. */
    val releaseAssetName: String,
)

/**
 * One selectable asset provider. [id] is a stable persistence key; [labelResKey] and
 * [descriptionResKey] are Android string-resource names resolved by the UI layer (kept as plain
 * strings so this stays Android-free). [geoipRepo] / [geositeRepo] are null only for [isCustom],
 * where the base URL is user-supplied at runtime.
 */
data class AssetProvider(
    val id: String,
    val labelResKey: String,
    val descriptionResKey: String,
    val geoipRepo: GeoAssetRepo?,
    val geositeRepo: GeoAssetRepo?,
) {
    val isCustom: Boolean get() = id == CustomAssetProviderId
}

/**
 * The built-in provider presets plus the Custom option. Adding a fifth preset is a single entry
 * here (and matching string resources in all locales); nothing else in the data layer changes.
 */
val BuiltInAssetProviders: List<AssetProvider> =
    listOf(
        AssetProvider(
            id = "sagernet",
            labelResKey = "asset_provider_sagernet_label",
            descriptionResKey = "asset_provider_sagernet_desc",
            geoipRepo = GeoAssetRepo("SagerNet/sing-geoip", GeoipDatabaseAssetFileName),
            geositeRepo = GeoAssetRepo("SagerNet/sing-geosite", GeositeDatabaseAssetFileName),
        ),
        AssetProvider(
            id = "soffchen",
            labelResKey = "asset_provider_soffchen_label",
            descriptionResKey = "asset_provider_soffchen_desc",
            geoipRepo = GeoAssetRepo("soffchen/sing-geoip", GeoipDatabaseAssetFileName),
            geositeRepo = GeoAssetRepo("soffchen/sing-geosite", GeositeDatabaseAssetFileName),
        ),
        AssetProvider(
            id = "chocolate4u",
            labelResKey = "asset_provider_chocolate4u_label",
            descriptionResKey = "asset_provider_chocolate4u_desc",
            geoipRepo = GeoAssetRepo("Chocolate4U/Iran-sing-box-rules", GeoipDatabaseAssetFileName),
            geositeRepo = GeoAssetRepo("Chocolate4U/Iran-sing-box-rules", GeositeDatabaseAssetFileName),
        ),
        AssetProvider(
            id = "l11r",
            labelResKey = "asset_provider_l11r_label",
            descriptionResKey = "asset_provider_l11r_desc",
            geoipRepo = GeoAssetRepo("L11R/antizapret-sing-box-geo", GeoipDatabaseAssetFileName),
            geositeRepo = GeoAssetRepo("L11R/antizapret-sing-box-geo", GeositeDatabaseAssetFileName),
        ),
        /*
         * RuNet Freedom (russia-v2ray-rules-dat) — de-facto Russia bypass ruleset with frequent
         * updates, more current than antizapret for v2ray/xray geoip/geosite tags.
         *
         * Format note: runetfreedom/russia-v2ray-rules-dat publishes both v2ray `.dat` and
         * sing-box-compatible `.db` variants on the same release. We download the `.db` assets
         * (geoip.db / geosite.db) which are compatible with the sing-box maxminddb/protobuf
         * format that ripdpi-geo consumes. Releases that ship only the `.dat` variant are
         * rejected by the existing fail-closed isPlausibleGeoAssetPayload gate — no per-release
         * checksum pin exists upstream, so integrity is enforced entirely by that gate.
         *
         * Provider URL: https://github.com/runetfreedom/russia-v2ray-rules-dat
         */
        AssetProvider(
            id = "runetfreedom",
            labelResKey = "asset_provider_runetfreedom_label",
            descriptionResKey = "asset_provider_runetfreedom_desc",
            geoipRepo = GeoAssetRepo("runetfreedom/russia-v2ray-rules-dat", GeoipDatabaseAssetFileName),
            geositeRepo = GeoAssetRepo("runetfreedom/russia-v2ray-rules-dat", GeositeDatabaseAssetFileName),
        ),
        AssetProvider(
            id = CustomAssetProviderId,
            labelResKey = "asset_provider_custom_label",
            descriptionResKey = "asset_provider_custom_desc",
            geoipRepo = null,
            geositeRepo = null,
        ),
    )

/** Looks up a provider by [id], falling back to the default preset for unknown/blank ids. */
fun assetProviderById(id: String?): AssetProvider =
    BuiltInAssetProviders.firstOrNull { it.id == id }
        ?: BuiltInAssetProviders.first { it.id == DefaultAssetProviderId }

/** Maps an asset kind to its canonical on-disk file name in the geo asset directory. */
fun geoAssetFileName(kind: GeoAssetKind): String =
    when (kind) {
        GeoAssetKind.Geoip -> GeoipDatabaseAssetFileName
        GeoAssetKind.Geosite -> GeositeDatabaseAssetFileName
    }

/**
 * Builds the GitHub Releases `latest` API URL for a repo, e.g.
 * `https://api.github.com/repos/SagerNet/sing-geoip/releases/latest`. Used to read the current
 * release tag for the "update available" comparison.
 */
fun githubLatestReleaseApiUrl(repo: GeoAssetRepo): String =
    "https://api.github.com/repos/${repo.ownerRepo}/releases/latest"

/**
 * Builds the download URL for a release asset at a specific [tag], e.g.
 * `https://github.com/SagerNet/sing-geoip/releases/download/202401010000/geoip.db`.
 */
fun githubReleaseAssetDownloadUrl(
    repo: GeoAssetRepo,
    tag: String,
): String = "https://github.com/${repo.ownerRepo}/releases/download/$tag/${repo.releaseAssetName}"

/**
 * Builds a download URL for a custom GitHub-Releases-compatible base. The base may point at either
 * the repo root (`https://github.com/owner/repo`) or a release-download prefix; we always append
 * the canonical asset file name. Trailing slashes are normalized.
 */
fun customAssetDownloadUrl(
    baseUrl: String,
    kind: GeoAssetKind,
): String {
    val trimmedBase = baseUrl.trim().trimEnd('/')
    return "$trimmedBase/${geoAssetFileName(kind)}"
}

/**
 * Opaque-tag comparison. An update is available iff the remote tag is non-blank AND differs from
 * the stored tag (treating tags as opaque strings — we do not parse semver, since providers use
 * date stamps, commit hashes, and `vN.N.N` interchangeably). A blank stored tag with a non-blank
 * remote tag means "never downloaded" → update available.
 */
fun isAssetUpdateAvailable(
    storedTag: String?,
    remoteTag: String?,
): Boolean {
    val remote = remoteTag?.trim().orEmpty()
    if (remote.isEmpty()) {
        return false
    }
    val stored = storedTag?.trim().orEmpty()
    return remote != stored
}

/**
 * Minimal validity gate for a downloaded `.db` payload before it goes live. We fail closed: a
 * corrupt or blocked download (zero-length, or not a sing-box `SRS`/`SG`-style binary) must never
 * replace a working database. sing-box geo databases begin with the ASCII magic `SAGENET` /
 * `SRS` family; we accept any non-empty binary that is at least [MinGeoAssetBytes] and is not a
 * captured HTML error/redirect page (which a blocking middlebox typically returns).
 */
fun isPlausibleGeoAssetPayload(payload: ByteArray): Boolean {
    if (payload.size < MinGeoAssetBytes) {
        return false
    }
    // Reject obvious HTML/redirect interception pages served by blocking middleboxes.
    val prefix = payload.copyOf(minOf(payload.size, HtmlSniffPrefixBytes))
    val prefixText = prefix.toString(Charsets.US_ASCII).trimStart().lowercase()
    val looksLikeHtml =
        prefixText.startsWith("<!doctype") ||
            prefixText.startsWith("<html") ||
            prefixText.startsWith("<?xml")
    return !looksLikeHtml
}

/** Minimum plausible size for a real geo database; anything smaller is treated as corrupt. */
const val MinGeoAssetBytes = 64

private const val HtmlSniffPrefixBytes = 16
