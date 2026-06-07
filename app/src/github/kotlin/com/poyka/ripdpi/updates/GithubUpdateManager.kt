@file:Suppress("TooGenericExceptionCaught")

package com.poyka.ripdpi.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GithubUpdateManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val dispatchers: AppCoroutineDispatchers,
    ) : AppUpdateManager {
        private val owner = BuildConfig.GITHUB_UPDATE_OWNER
        private val repo = BuildConfig.GITHUB_UPDATE_REPO
        private val client = defaultClient()
        private val json = RipDpiJson

        @Volatile
        private var latestPlan: GithubUpdatePlan? = null

        override val channelInfo: UpdateChannelInfo =
            UpdateChannelInfo(
                channel = DistributionChannel.Github,
                canCheckInApp = true,
                canInstallInApp = true,
            )

        override suspend fun checkForUpdate(): UpdateCheckResult =
            withContext(dispatchers.io) {
                try {
                    val release = fetchLatestRelease()
                    val metadataAsset =
                        release.assets.firstOrNull { it.name == UpdateMetadataAssetName }
                            ?: return@withContext UpdateCheckResult.Failed(UpdateFailureReason.InvalidMetadata)
                    if (!metadataAsset.isAllowedReleaseAsset(owner, repo)) {
                        return@withContext UpdateCheckResult.Failed(UpdateFailureReason.InvalidRepositoryAsset)
                    }
                    val metadata = fetchMetadata(metadataAsset)
                    val validationFailure = validateMetadata(metadata)
                    if (validationFailure != null) {
                        return@withContext UpdateCheckResult.Failed(validationFailure)
                    }
                    val selectedArtifact =
                        metadata.selectArtifact(Build.SUPPORTED_ABIS)
                            ?: return@withContext UpdateCheckResult.Failed(UpdateFailureReason.InvalidMetadata)
                    if (selectedArtifact.versionCode <= BuildConfig.VERSION_CODE.toLong()) {
                        latestPlan = null
                        return@withContext UpdateCheckResult.NoUpdate
                    }
                    val apkAsset =
                        release.assets.firstOrNull { it.name == selectedArtifact.apkName }
                            ?: return@withContext UpdateCheckResult.Failed(UpdateFailureReason.InvalidRepositoryAsset)
                    if (!apkAsset.isAllowedReleaseAsset(owner, repo)) {
                        return@withContext UpdateCheckResult.Failed(UpdateFailureReason.InvalidRepositoryAsset)
                    }
                    val enrichedArtifact =
                        selectedArtifact.copy(sizeBytes = selectedArtifact.sizeBytes ?: apkAsset.size)
                    val plan =
                        GithubUpdatePlan(
                            update = metadata.toAvailableUpdate(enrichedArtifact),
                            metadata = metadata,
                            artifact = enrichedArtifact,
                            apkAsset = apkAsset,
                        )
                    latestPlan = plan
                    UpdateCheckResult.Available(plan.update)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    UpdateCheckResult.Failed(e.toFailureReason())
                }
            }

        override suspend fun install(update: AvailableAppUpdate): UpdateInstallResult =
            withContext(dispatchers.io) {
                try {
                    val plan =
                        latestPlan?.takeIf { it.update.artifactName == update.artifactName }
                            ?: return@withContext UpdateInstallResult.Failed(UpdateFailureReason.NotAvailable)
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        !context.packageManager.canRequestPackageInstalls()
                    ) {
                        return@withContext UpdateInstallResult.PermissionRequired(unknownSourcesIntent())
                    }
                    val apkFile = downloadApk(plan)
                    val validationFailure = validateDownloadedApk(plan.metadata, plan.artifact, apkFile)
                    if (validationFailure != null) {
                        apkFile.delete()
                        return@withContext UpdateInstallResult.Failed(validationFailure)
                    }
                    val installIntent = installIntent(apkFile)
                    if (installIntent.resolveActivity(context.packageManager) == null) {
                        return@withContext UpdateInstallResult.Failed(UpdateFailureReason.InstallerUnavailable)
                    }
                    context.startActivity(installIntent)
                    UpdateInstallResult.Started
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    UpdateInstallResult.Failed(e.toFailureReason())
                }
            }

        private fun fetchLatestRelease(): GithubReleaseResponse {
            val request =
                Request
                    .Builder()
                    .url("https://api.github.com/repos/$owner/$repo/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", UserAgent)
                    .get()
                    .build()
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("GitHub release request failed with HTTP ${response.code}")
                }
                json.decodeFromString<GithubReleaseResponse>(response.body.string())
            }
        }

        private fun fetchMetadata(asset: GithubReleaseAsset): GithubUpdateMetadata {
            val body = fetchAssetString(asset)
            return json.decodeFromString<GithubUpdateMetadata>(body)
        }

        private fun fetchAssetString(asset: GithubReleaseAsset): String {
            val request =
                Request
                    .Builder()
                    .url(asset.browserDownloadUrl)
                    .header("User-Agent", UserAgent)
                    .get()
                    .build()
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("GitHub asset request failed with HTTP ${response.code}")
                }
                response.body.string()
            }
        }

        private fun validateMetadata(metadata: GithubUpdateMetadata): UpdateFailureReason? =
            when {
                !metadata.isStructurallyValid() -> UpdateFailureReason.InvalidMetadata
                metadata.packageName != BuildConfig.APPLICATION_ID -> UpdateFailureReason.InvalidPackageName
                metadata.minSdk != null && Build.VERSION.SDK_INT < metadata.minSdk -> UpdateFailureReason.UnsupportedSdk
                else -> null
            }

        private fun downloadApk(plan: GithubUpdatePlan): File {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val finalFile = File(updatesDir, plan.artifact.apkName)
            val tempFile = File(updatesDir, "${plan.artifact.apkName}.tmp")
            tempFile.delete()

            val request =
                Request
                    .Builder()
                    .url(plan.apkAsset.browserDownloadUrl)
                    .header("User-Agent", UserAgent)
                    .get()
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("GitHub APK request failed with HTTP ${response.code}")
                }
                tempFile.sink().buffer().use { sink ->
                    sink.writeAll(response.body.source())
                }
            }
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            return finalFile
        }

        private fun validateDownloadedApk(
            metadata: GithubUpdateMetadata,
            artifact: GithubUpdateArtifact,
            apkFile: File,
        ): UpdateFailureReason? {
            if (artifact.sha256.lowercase(Locale.US) != apkFile.sha256()) {
                return UpdateFailureReason.ChecksumMismatch
            }
            val packageInfo =
                context.packageManager.packageInfoForArchive(apkFile)
                    ?: return UpdateFailureReason.PackageReadFailed
            if (
                packageInfo.packageName != BuildConfig.APPLICATION_ID ||
                packageInfo.packageName != metadata.packageName
            ) {
                return UpdateFailureReason.InvalidPackageName
            }
            if (
                packageInfo.longVersionCodeCompat != artifact.versionCode ||
                packageInfo.longVersionCodeCompat <= BuildConfig.VERSION_CODE.toLong()
            ) {
                return UpdateFailureReason.InvalidVersionCode
            }
            metadata.minSdk?.let { minSdk ->
                val packageMinSdk = packageInfo.applicationInfo?.minSdkVersion ?: 0
                if (
                    Build.VERSION.SDK_INT < minSdk ||
                    packageMinSdk > Build.VERSION.SDK_INT
                ) {
                    return UpdateFailureReason.UnsupportedSdk
                }
            }
            return null
        }

        private fun installIntent(apkFile: File): Intent {
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${BuildConfig.APPLICATION_ID}.updates.fileprovider",
                    apkFile,
                )
            return Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, ApkMimeType)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        private fun unknownSourcesIntent(): Intent =
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${BuildConfig.APPLICATION_ID}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        companion object {
            private const val UpdateMetadataAssetName = "update.json"
            private const val UserAgent = "RIPDPI GitHub updater"
            private const val ApkMimeType = "application/vnd.android.package-archive"

            private fun defaultClient(): OkHttpClient =
                OkHttpClient
                    .Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .callTimeout(180, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class GithubUpdateModule {
    @Binds
    @Singleton
    abstract fun bindAppUpdateManager(manager: GithubUpdateManager): AppUpdateManager
}

private fun Exception.toFailureReason(): UpdateFailureReason =
    when (this) {
        is java.net.UnknownHostException,
        is java.net.SocketTimeoutException,
        is java.io.IOException,
        -> UpdateFailureReason.Network

        is kotlinx.serialization.SerializationException,
        is IllegalArgumentException,
        -> UpdateFailureReason.InvalidMetadata

        else -> UpdateFailureReason.Unknown
    }

@Suppress("DEPRECATION")
private fun PackageManager.packageInfoForArchive(apkFile: File): PackageInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
    }

private val PackageInfo.longVersionCodeCompat: Long
    get() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}
