package com.poyka.ripdpi.proxyimport

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.IntentCompat
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.subscription.profileTitleOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Trampoline activity for inbound proxy share links, subscription deep links, and shared
 * text or files.
 *
 * Registered in the manifest for inbound proxy URI schemes (`vless://`, `trojan://`,
 * `ss://`, `hysteria2://`, `hy2://`, `tuic://`, `anytls://`, `ssh://`, `mieru://`, `mierus://`), for the
 * subscription deep links [SingBoxDeepLinkParser] knows and `ripdpi://import`, and for
 * `ACTION_SEND` of text or a file (a link, a subscription URL, a WireGuard / AmneziaWG
 * `.conf`, or an AmneziaVPN `vpn://` key). Manifest registration is broader than parser
 * support; unsupported content is rejected after dispatch rather than documented as imported.
 *
 * The activity is a thin classifier: it parses the inbound URI via the shared
 * [ProxyUriShareDispatcher] / [SingBoxDeepLinkParser], builds a forwarding [Intent] into
 * [MainActivity] carrying a typed import payload, and finishes. A shared file is read on a
 * worker thread with a timeout, and only from another app's `content://` provider. Malformed
 * input surfaces a typed [Toast] and finishes without forwarding — never a crash.
 */
class ImportHandlerActivity : Activity() {
    private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val readingFile =
            runCatching { handleInboundIntent() }
                .getOrElse {
                    Logger.e { "Import handler failed to process inbound intent" }
                    toast(R.string.import_error_unsupported)
                    false
                }
        if (!readingFile) finish()
    }

    override fun onDestroy() {
        importScope.cancel()
        super.onDestroy()
    }

    /** Returns `true` while a shared file is read in the background; the read then finishes the activity. */
    private fun handleInboundIntent(): Boolean {
        if (intent?.action == Intent.ACTION_SEND) return handleSharedContent()
        val document = intent?.data?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
        // An opened document (a bundle `.json` from a file manager): same bounded read as a shared file.
        val readsDocument = document != null && isForeignContentUri(document)
        when {
            document == null -> handleViewLink()
            readsDocument -> readSharedFile(document)
            else -> toast(R.string.import_error_unsupported)
        }
        return readsDocument
    }

    private fun handleViewLink() {
        val data = intent?.data?.toString()?.takeIf { it.isNotBlank() }
        if (data == null) {
            toast(R.string.import_error_unsupported)
            return
        }
        // `ripdpi://import?sub=…` / `ripdpi://import?url=…` takes priority over the
        // legacy SingBox-style `ripdpi://import-remote-profile` handler.
        if (!tryHandleRipDpiImport(data)) handleLegacyViewLink(data)
    }

    private fun handleLegacyViewLink(data: String) {
        ImportPayloadClassifier.embeddedShareProfile(data)?.let { request ->
            forwardProfile(request)
            return
        }
        when (val result = SingBoxDeepLinkParser.parse(data)) {
            is SingBoxDeepLinkResult.Success -> {
                forwardSubscription(PendingSubscriptionImportRequest(result.url, result.name, result.bootstrap))
            }

            SingBoxDeepLinkResult.Error.MissingUrl -> {
                toast(R.string.import_error_missing_url)
            }

            SingBoxDeepLinkResult.Error.BadEncoding -> {
                toast(R.string.import_error_bad_encoding)
            }

            SingBoxDeepLinkResult.Error.Unsupported -> {
                handleProxyShareLink(data)
            }
        }
    }

    /**
     * Dispatches a `ripdpi://import` deep link. Returns true when the link was
     * consumed -- forwarded to the confirm flow or reported as a definitive
     * error -- and false when it is not a ripdpi import link, so the caller
     * falls through to the legacy SingBox / proxy-share handlers.
     */
    private fun tryHandleRipDpiImport(data: String): Boolean {
        when (val ripDpiResult = RipDpiImportDeepLinkParser.parse(data)) {
            is RipDpiImportDeepLinkResult.Success -> handleRipDpiImportDeepLink(ripDpiResult)
            RipDpiImportDeepLinkResult.Error.NonHttps -> toast(R.string.import_error_bad_encoding)
            RipDpiImportDeepLinkResult.Error.BadEncoding -> toast(R.string.import_error_bad_encoding)
            RipDpiImportDeepLinkResult.Error.MissingTarget -> toast(R.string.import_error_missing_url)
            RipDpiImportDeepLinkResult.Error.Unsupported -> return false
        }
        return true
    }

    private fun handleRipDpiImportDeepLink(result: RipDpiImportDeepLinkResult.Success) {
        val bootstrap = result.kind == RipDpiImportDeepLinkResult.Success.Kind.OneShot
        forwardSubscription(
            PendingSubscriptionImportRequest(result.targetUrl, name = "", bootstrap = bootstrap),
        )
    }

    private fun forwardSubscription(request: PendingSubscriptionImportRequest) {
        val importToken = PendingProxyImportStore.process.stageSubscription(request)
        if (importToken == null) {
            toast(R.string.import_error_bad_encoding)
            return
        }
        forwardToMain(
            Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_IMPORT_ROUTE, ImportLaunchRoute.SUBSCRIPTION_CONFIRM)
                putExtra(EXTRA_SUBSCRIPTION_IMPORT_TOKEN, importToken)
            },
        )
    }

    private fun handleProxyShareLink(data: String) {
        when (val request = ProxyUriShareDispatcher.dispatch(data)) {
            is ProxyImportRequest.Profile -> {
                forwardProfile(request)
            }

            is ProxyImportRequest.UnsupportedScheme -> {
                toast(R.string.import_error_unsupported)
            }
        }
    }

    /**
     * Classifies text or a file shared through the system share sheet. Text that is not
     * importable (a caption, say) falls back to the attached file. Returns `true` when a file
     * read is pending.
     */
    private fun handleSharedContent(): Boolean {
        val textPayload =
            intent
                .getCharSequenceExtra(Intent.EXTRA_TEXT)
                ?.toString()
                ?.let(ImportPayloadClassifier::classify)
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        return when {
            textPayload != null -> {
                forward(textPayload)
                false
            }

            stream != null && isForeignContentUri(stream) -> {
                readSharedFile(stream)
                true
            }

            else -> {
                toast(R.string.import_error_unsupported)
                false
            }
        }
    }

    /**
     * Opens only another app's `content://` URI: a `file://` path or one of RIPDPI's own
     * providers would make RIPDPI read its private files for the sender (confused deputy).
     */
    private fun isForeignContentUri(uri: Uri): Boolean {
        val authority = uri.authority ?: return false
        val owner = packageManager.resolveContentProvider(authority, 0)?.packageName
        return uri.scheme == ContentResolver.SCHEME_CONTENT && owner != packageName
    }

    /** Cancelling the Activity also cancels its bounded import operation. */
    private fun readSharedFile(uri: Uri) {
        importScope.launch {
            val resolver = applicationContext.contentResolver
            // The larger bundle bound; the classifier keeps the import-text bound for every other format.
            val text = readImportText(resolver, uri, MaxLocalBundleBytes)
            val payload =
                text?.let {
                    withContext(
                        Dispatchers.Default,
                    ) { ImportPayloadClassifier.classify(it, MaxLocalBundleBytes) }
                }
            val named =
                (payload as? ImportPayload.Subscription)
                    ?.takeIf { it.request.bundle != null }
                    ?.let { bundle ->
                        val name = withContext(Dispatchers.IO) { bundleNameOf(resolver, uri) }
                        ImportPayload.Subscription(bundle.request.copy(name = name))
                    }
                    ?: payload
            named?.let(::forward) ?: toast(R.string.import_error_unsupported)
            finish()
        }
    }

    /**
     * The document's display name without `.json`, cleaned and bounded like a server title; empty when
     * the provider has none.
     * shortcut: an unresponsive provider can stall this query and hold this trampoline, bound it if that shows up.
     */
    private fun bundleNameOf(
        resolver: ContentResolver,
        uri: Uri,
    ): String {
        val displayName =
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull().orEmpty().trim()
        val hasSuffix = displayName.endsWith(JsonSuffix, ignoreCase = true)
        return profileTitleOf(if (hasSuffix) displayName.dropLast(JsonSuffix.length) else displayName)
    }

    private fun forward(payload: ImportPayload) {
        when (payload) {
            is ImportPayload.Profile -> forwardProfile(payload.request)
            is ImportPayload.Subscription -> forwardSubscription(payload.request)
            is ImportPayload.WireGuardConf -> forwardWireGuardConf(payload.conf)
        }
    }

    private fun forwardProfile(request: ProxyImportRequest.Profile) {
        val importToken = PendingProxyImportStore.process.stage(request.profile)
        forwardToMain(
            Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_IMPORT_ROUTE, ImportLaunchRoute.PROFILE_CONFIRM)
                putExtra(EXTRA_PROFILE_IMPORT_TOKEN, importToken)
            },
        )
    }

    private fun forwardWireGuardConf(conf: String) {
        val importToken = PendingProxyImportStore.process.stageAmneziaWgConf(conf)
        if (importToken == null) {
            toast(R.string.import_error_unsupported)
            return
        }
        forwardToMain(
            Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_IMPORT_ROUTE, ImportLaunchRoute.AMNEZIAWG_EDITOR)
                putExtra(EXTRA_AMNEZIAWG_IMPORT_TOKEN, importToken)
            },
        )
    }

    private fun forwardToMain(forward: Intent) {
        forward.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(forward)
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val JsonSuffix = ".json"

        /** Which import-confirmation destination [MainActivity] should open. */
        const val EXTRA_IMPORT_ROUTE: String = "com.poyka.ripdpi.extra.IMPORT_ROUTE"

        /** Opaque process-local hand-off token for [ImportLaunchRoute.PROFILE_CONFIRM]. */
        const val EXTRA_PROFILE_IMPORT_TOKEN: String = "com.poyka.ripdpi.extra.IMPORT_PROFILE_TOKEN"

        /** Opaque process-local hand-off token for [ImportLaunchRoute.SUBSCRIPTION_CONFIRM]. */
        const val EXTRA_SUBSCRIPTION_IMPORT_TOKEN: String = "com.poyka.ripdpi.extra.IMPORT_SUBSCRIPTION_TOKEN"

        /** Opaque process-local hand-off token for [ImportLaunchRoute.AMNEZIAWG_EDITOR]. */
        const val EXTRA_AMNEZIAWG_IMPORT_TOKEN: String = "com.poyka.ripdpi.extra.IMPORT_AMNEZIAWG_TOKEN"
    }
}
