package com.poyka.ripdpi.ui.screens.ssh

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.SshHostKeyProbeFailure
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val Sha256Prefix = "SHA256:"

/**
 * Progress of the "Fetch host key" action in the SSH editor.
 *
 * An observed key is never trusted on arrival: it waits in [PendingConfirmation]
 * until the user confirms it, and only then fills the pinned fingerprint field.
 */
sealed interface SshHostKeyFetchState {
    data object Idle : SshHostKeyFetchState

    data object InProgress : SshHostKeyFetchState

    /** [differsFromPinned] is `true` when a different fingerprint is already pinned in the editor. */
    data class PendingConfirmation(
        val fingerprint: String,
        val algorithm: String,
        val differsFromPinned: Boolean,
    ) : SshHostKeyFetchState

    data class Failed(
        @StringRes val messageRes: Int,
    ) : SshHostKeyFetchState
}

/** Short user-facing text for a probe failure; raw exception text never reaches the UI. */
@StringRes
internal fun SshHostKeyProbeFailure.messageRes(): Int =
    when (this) {
        SshHostKeyProbeFailure.InvalidInput -> R.string.ssh_host_key_fetch_error_invalid

        SshHostKeyProbeFailure.Timeout -> R.string.ssh_host_key_fetch_error_timeout

        SshHostKeyProbeFailure.ConnectFailed -> R.string.ssh_host_key_fetch_error_connect

        SshHostKeyProbeFailure.HandshakeFailed -> R.string.ssh_host_key_fetch_error_handshake

        SshHostKeyProbeFailure.ProtectionDenied -> R.string.ssh_host_key_fetch_error_protection

        SshHostKeyProbeFailure.NoUnderlay,
        SshHostKeyProbeFailure.NetworkChanged,
        -> R.string.ssh_host_key_fetch_error_network

        SshHostKeyProbeFailure.Busy -> R.string.ssh_host_key_fetch_error_busy

        SshHostKeyProbeFailure.InternalFailure -> R.string.ssh_host_key_fetch_error_generic
    }

/**
 * `true` when [pinned] is set and names a different key than [observed].
 *
 * The native pin parser accepts standard and URL-safe base64, padded or not, so both
 * sides are reduced to unpadded standard base64 before the comparison.
 */
internal fun sshFingerprintDiffers(
    pinned: String,
    observed: String,
): Boolean = pinned.isNotBlank() && canonicalSshFingerprint(pinned) != canonicalSshFingerprint(observed)

private fun canonicalSshFingerprint(value: String): String {
    val trimmed = value.trim()
    val raw = trimmed.removePrefix(Sha256Prefix)
    // A body that mixes both alphabets is not a valid pin; keep it as typed so it never matches.
    val canonicalizable = trimmed.startsWith(Sha256Prefix) && !mixesBase64Alphabets(raw)
    return if (canonicalizable) {
        Sha256Prefix + raw.replace('-', '+').replace('_', '/').trimEnd('=')
    } else {
        trimmed
    }
}

private fun mixesBase64Alphabets(body: String): Boolean {
    val standard = body.any { it == '+' || it == '/' }
    val urlSafe = body.any { it == '-' || it == '_' }
    return standard && urlSafe
}

/** "Fetch host key" button plus the last failure message, shown under the fingerprint field. */
@Composable
internal fun SshHostKeyFetchControls(
    state: SshHostKeyFetchState,
    enabled: Boolean,
    onFetch: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    RipDpiButton(
        text = stringResource(R.string.ssh_host_key_fetch_action),
        onClick = onFetch,
        modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.SshHostKeyFetch),
        variant = RipDpiButtonVariant.Outline,
        enabled = enabled,
        loading = state is SshHostKeyFetchState.InProgress,
    )
    if (state is SshHostKeyFetchState.Failed) {
        Text(
            text = stringResource(state.messageRes),
            style = type.caption,
            color = colors.destructive,
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.SshHostKeyFetchError),
        )
    }
}

/** Explicit trust step for an observed host key; dismissing it leaves the pinned fingerprint unchanged. */
@Composable
internal fun SshHostKeyTrustDialog(
    pending: SshHostKeyFetchState.PendingConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.ssh_host_key_trust_title),
        dialogTestTag = RipDpiTestTags.SshHostKeyTrustDialog,
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.ssh_host_key_trust_dismiss),
                onClick = onDismiss,
                testTag = RipDpiTestTags.SshHostKeyTrustDismiss,
            ),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.ssh_host_key_trust_confirm),
                onClick = onConfirm,
                testTag = RipDpiTestTags.SshHostKeyTrustConfirm,
                destructive = pending.differsFromPinned,
            ),
        visuals =
            RipDpiDialogVisuals(
                tone = if (pending.differsFromPinned) RipDpiDialogTone.Destructive else RipDpiDialogTone.Info,
            ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            if (pending.differsFromPinned) {
                Text(
                    text = stringResource(R.string.ssh_host_key_trust_mismatch),
                    style = type.bodyEmphasis,
                    color = colors.destructive,
                )
            }
            Text(text = pending.fingerprint, style = type.monoValue, color = colors.foreground)
            Text(
                text = stringResource(R.string.ssh_host_key_trust_key_type, pending.algorithm),
                style = type.caption,
                color = colors.mutedForeground,
            )
            Text(
                text = stringResource(R.string.ssh_host_key_trust_body),
                style = type.body,
                color = colors.mutedForeground,
            )
        }
    }
}
