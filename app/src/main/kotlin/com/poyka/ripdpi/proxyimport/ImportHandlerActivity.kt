package com.poyka.ripdpi.proxyimport

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainActivity

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
 * [MainActivity] carrying a typed import payload, and finishes immediately. Malformed
 * input surfaces a typed [Toast] and finishes without forwarding — never a crash.
 */
class ImportHandlerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { handleInboundIntent() }
            .onFailure { error ->
                Logger.e(error) { "Import handler failed to process inbound intent" }
                toast(R.string.import_error_unsupported)
            }
        finish()
    }

    private fun handleInboundIntent() {
        if (intent?.action == Intent.ACTION_SEND) {
            handleSharedContent()
        } else {
            handleViewLink()
        }
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
                toast(R.string.import_error_unsupported)
            }
        }
    }

    /** Classifies text or a file shared through the system share sheet. */
    private fun handleSharedContent() {
        // shortcut: reads a shared file (at most MaxImportFileBytes) on the main thread; move it to a
        // worker if a slow document provider shows up in practice.
        val text =
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
                ?: IntentCompat
                    .getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { uri -> readImportText(contentResolver, uri) }
        when (val payload = text?.let(ImportPayloadClassifier::classify)) {
            is ImportPayload.Profile -> forwardProfile(payload.request)
            is ImportPayload.Subscription -> forwardSubscription(payload.request)
            is ImportPayload.WireGuardConf -> forwardWireGuardConf(payload.conf)
            null -> toast(R.string.import_error_unsupported)
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
