package com.poyka.ripdpi.proxyimport

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainActivity

/**
 * Trampoline activity for inbound proxy share links and subscription deep links.
 *
 * Registered in the manifest for inbound proxy-like URI schemes (`vless://`,
 * `trojan://`, `ss://`, `hysteria2://`, `tuic://`,
 * `anytls://`, `ssh://`) and for the `singbox`/`ripdpi`/`sn` `import-remote-profile`
 * subscription deep link. Manifest registration is broader than parser support; unsupported
 * schemes are rejected after dispatch rather than documented as imported.
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
        forwardToMain(
            Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_IMPORT_ROUTE, ImportLaunchRoute.SUBSCRIPTION_CONFIRM)
                putExtra(EXTRA_SUBSCRIPTION_URL, result.targetUrl)
                putExtra(EXTRA_SUBSCRIPTION_BOOTSTRAP, bootstrap)
            },
        )
    }

    private fun handleSubscriptionDeepLink(data: String) {
        when (val result = SingBoxDeepLinkParser.parse(data)) {
            is SingBoxDeepLinkResult.Success -> {
                forwardToMain(
                    Intent(this, MainActivity::class.java).apply {
                        putExtra(EXTRA_IMPORT_ROUTE, ImportLaunchRoute.SUBSCRIPTION_CONFIRM)
                        putExtra(EXTRA_SUBSCRIPTION_URL, result.url)
                        putExtra(EXTRA_SUBSCRIPTION_NAME, result.name)
                        putExtra(EXTRA_SUBSCRIPTION_BOOTSTRAP, result.bootstrap)
                    },
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

    private fun handleProxyShareLink(data: String) {
        when (val request = ProxyUriShareDispatcher.dispatch(data)) {
            is ProxyImportRequest.Profile -> {
                val importToken = PendingProxyImportStore.process.stage(request.profile)
                forwardToMain(
                    Intent(this, MainActivity::class.java).apply {
                        putExtra(EXTRA_IMPORT_ROUTE, ImportLaunchRoute.PROFILE_CONFIRM)
                        putExtra(EXTRA_PROFILE_IMPORT_TOKEN, importToken)
                    },
                )
            }

            is ProxyImportRequest.UnsupportedScheme -> {
                toast(R.string.import_error_unsupported)
            }
        }
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

        /** Subscription URL for [ImportLaunchRoute.SUBSCRIPTION_CONFIRM]. */
        const val EXTRA_SUBSCRIPTION_URL: String = "com.poyka.ripdpi.extra.IMPORT_SUBSCRIPTION_URL"

        /** Optional subscription display name for [ImportLaunchRoute.SUBSCRIPTION_CONFIRM]. */
        const val EXTRA_SUBSCRIPTION_NAME: String = "com.poyka.ripdpi.extra.IMPORT_SUBSCRIPTION_NAME"

        /** `true` when the subscription URL is a one-time `/bootstrap/` token import. */
        const val EXTRA_SUBSCRIPTION_BOOTSTRAP: String = "com.poyka.ripdpi.extra.IMPORT_SUBSCRIPTION_BOOTSTRAP"
    }
}
