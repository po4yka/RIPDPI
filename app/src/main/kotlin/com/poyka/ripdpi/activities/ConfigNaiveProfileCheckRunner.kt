package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.NaiveProxyCheckOutcome
import com.poyka.ripdpi.services.NaiveProxyProfileCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Runs one NaiveProxy server check for the editor draft and reports the result as a message. */
internal class ConfigNaiveProfileCheckRunner(
    private val scope: CoroutineScope,
    private val check: NaiveProxyProfileCheck,
    private val effects: MutableSharedFlow<ConfigEffect>,
    private val strings: StringResolver,
) {
    private val running = AtomicBoolean(false)

    fun start(draft: ConfigDraft) {
        if (!running.compareAndSet(false, true)) return
        effects.tryEmit(ConfigEffect.Message(strings.getString(R.string.config_relay_naive_check_running)))
        scope.launch {
            try {
                val outcome =
                    try {
                        check.check(
                            draft.toRelayProfileRecord(draft.relayProfileId),
                            draft.toRelayCredentialRecord(draft.relayProfileId),
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // A draft that cannot map to a profile must not crash the editor.
                        NaiveProxyCheckOutcome.Failed
                    }
                effects.tryEmit(ConfigEffect.Message(strings.getString(outcome.messageResource())))
            } finally {
                running.set(false)
            }
        }
    }
}

private fun NaiveProxyCheckOutcome.messageResource(): Int =
    when (this) {
        NaiveProxyCheckOutcome.Ok -> R.string.config_relay_naive_check_ok
        NaiveProxyCheckOutcome.Auth -> R.string.config_relay_naive_check_auth
        NaiveProxyCheckOutcome.Tls -> R.string.config_relay_naive_check_tls
        NaiveProxyCheckOutcome.Dns -> R.string.config_relay_naive_check_dns
        NaiveProxyCheckOutcome.Connect -> R.string.config_relay_naive_check_connect
        NaiveProxyCheckOutcome.Timeout -> R.string.config_relay_naive_check_timeout
        NaiveProxyCheckOutcome.Failed -> R.string.config_relay_naive_check_failed
    }
