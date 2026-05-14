package com.poyka.ripdpi.proxyimport

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.data.ProxyProfile
import kotlinx.serialization.json.Json

/**
 * Trampoline activity for inbound proxy share links and subscription deep links.
 *
 * Registered in the manifest for the single-profile proxy URI schemes (`vless://`,
 * `vmess://`, `trojan://`, `ss://`, `hysteria://`, `hysteria2://`, `tuic://`,
 * `anytls://`, `ssh://`) and for the `singbox`/`ripdpi`/`sn` `import-remote-profile`
 * subscription deep link.
 *
 * The activity is a thin classifier: it parses the inbound URI via the shared
 * [ProxyUriShareDispatcher] / [SingBoxDeepLinkParser], builds a forwarding [Intent] into
 * [MainActivity] carrying a typed import payload, and finishes immediately. Malformed
 * input surfaces a typed [Toast] and finishes without forwarding — never a crash.
 */
class ImportHandlerActivity : Activity() {
    private val json = Json { ignoreUnknownKeys = true }

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
                forwardToMain(
                    Intent(this, MainActivity::class.java).apply {
                        putExtra(EXTRA_IMPORT_ROUTE, ImportLaunchRoute.PROFILE_CONFIRM)
                        putExtra(EXTRA_PROFILE_JSON, json.encodeToString(ProxyProfile.serializer(), request.profile))
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

        /** JSON-encoded [ProxyProfile] for [ImportLaunchRoute.PROFILE_CONFIRM]. */
        const val EXTRA_PROFILE_JSON: String = "com.poyka.ripdpi.extra.IMPORT_PROFILE_JSON"

        /** Subscription URL for [ImportLaunchRoute.SUBSCRIPTION_CONFIRM]. */
        const val EXTRA_SUBSCRIPTION_URL: String = "com.poyka.ripdpi.extra.IMPORT_SUBSCRIPTION_URL"

        /** Optional subscription display name for [ImportLaunchRoute.SUBSCRIPTION_CONFIRM]. */
        const val EXTRA_SUBSCRIPTION_NAME: String = "com.poyka.ripdpi.extra.IMPORT_SUBSCRIPTION_NAME"

        /** `true` when the subscription URL is a one-time `/bootstrap/` token import. */
        const val EXTRA_SUBSCRIPTION_BOOTSTRAP: String = "com.poyka.ripdpi.extra.IMPORT_SUBSCRIPTION_BOOTSTRAP"
    }
}
