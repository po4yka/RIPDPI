package com.poyka.ripdpi.proxyimport

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Trampoline activity for inbound proxy share links, subscription deep links, and shared
 * text or files.
 *
 * Registered in the manifest for inbound proxy URI schemes (`vless://`, `trojan://`,
 * `ss://`, `hysteria2://`, `hy2://`, `tuic://`, `anytls://`, `ssh://`, `mieru://`), for the
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
                .getOrElse { error ->
                    Logger.e(error) { "Import handler failed to process inbound intent" }
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
        if (intent?.action != Intent.ACTION_SEND) {
            handleViewLink()
            return false
        }
        return handleSharedContent()
    }

    private fun handleViewLink() {
        val data = intent?.data?.toString()?.takeIf { it.isNotBlank() }
        if (data == null) {
            toast(R.string.import_error_unsupported)
            return
        }
        // `ripdpi://import?sub=…` / `ripdpi://import?url=…` takes priority over the
        // legacy SingBox-style `ripdpi://import-remote-profile` handler.
        if (tryHandleRipDpiImport(data)) {
            return
        }
        when (SingBoxDeepLinkParser.parse(data)) {
            is SingBoxDeepLinkResult.Success -> {
                handleSubscriptionDeepLink(data)
            }

            is SingBoxDeepLinkResult.Error -> {
                // Not a subscription deep link — fall through to single-profile dispatch.
                // A genuine malformed subscription deep link is reported inside the handler.
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

    private fun handleSubscriptionDeepLink(data: String) {
        when (val result = SingBoxDeepLinkParser.parse(data)) {
            is SingBoxDeepLinkResult.Success -> {
                forwardSubscription(
                    PendingSubscriptionImportRequest(result.url, result.name, result.bootstrap),
                )
            }

            SingBoxDeepLinkResult.Error.MissingUrl -> {
                toast(R.string.import_error_missing_url)
            }

            SingBoxDeepLinkResult.Error.BadEncoding -> {
                toast(R.string.import_error_bad_encoding)
            }

            SingBoxDeepLinkResult.Error.Unsupported -> {
                toast(R.string.import_error_unsupported)
            }
        }
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
                toast(unsupportedMessage(request.scheme))
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
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
                toast(unsupportedMessage(ProxyUriShareDispatcher.schemeOf(text)))
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
            val text = readImportText(applicationContext.contentResolver, uri)
            text?.let(ImportPayloadClassifier::classify)?.let(::forward) ?: toast(R.string.import_error_unsupported)
            finish()
        }
    }

    private fun unsupportedMessage(scheme: String): Int = unsupportedImportMessage(scheme)

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
