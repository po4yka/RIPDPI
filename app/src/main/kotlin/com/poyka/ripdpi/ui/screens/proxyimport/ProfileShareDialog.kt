package com.poyka.ripdpi.ui.screens.proxyimport

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * "Share profile" dialog host: QR + plain-URI generation for a saved profile.
 *
 * Generation is fully offline. The first thing every share session shows is a
 * secrets-redaction warning whose acknowledge action is disabled for a 5-second hold and
 * which cannot be permanently suppressed. Only after acknowledgement is the share content
 * (QR image + canonical URI) revealed, with a two-action sheet: copy the URI, or share
 * the QR image.
 */
@Composable
fun ProfileShareDialog(
    onDismiss: () -> Unit,
    onCopyUri: (String) -> Unit,
    onShareImage: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileShareViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    if (uiState.profile == null) return

    LaunchedEffect(uiState.warningVisible) {
        if (uiState.warningVisible) {
            kotlinx.coroutines.delay(ProfileShareViewModel.WARNING_HOLD_MILLIS)
            viewModel.onWarningHoldElapsed()
        }
    }

    Dialog(onDismissRequest = {
        viewModel.onShareDismissed()
        onDismiss()
    }) {
        RipDpiCard(modifier = modifier) {
            val shareUri = uiState.shareUri
            when {
                uiState.warningVisible -> {
                    ShareWarningContent(
                        acknowledgeEnabled = uiState.warningAcknowledgeEnabled,
                        onAcknowledge = viewModel::onWarningAcknowledged,
                    )
                }

                uiState.unshareable -> {
                    ShareUnshareableContent()
                }

                shareUri != null -> {
                    ShareContent(
                        shareUri = shareUri,
                        onCopyUri = onCopyUri,
                        onShareImage = onShareImage,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareWarningContent(
    acknowledgeEnabled: Boolean,
    onAcknowledge: () -> Unit,
) {
    WarningBanner(
        title = stringResource(R.string.profile_share_warning_title),
        message = stringResource(R.string.profile_share_warning_body),
        tone = WarningBannerTone.Warning,
        icon = RipDpiIcons.Warning,
    )
    RipDpiButton(
        text =
            if (acknowledgeEnabled) {
                stringResource(R.string.profile_share_warning_acknowledge)
            } else {
                stringResource(R.string.profile_share_warning_wait)
            },
        onClick = onAcknowledge,
        enabled = acknowledgeEnabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ShareUnshareableContent() {
    RipDpiPanelHeader(
        title = stringResource(R.string.profile_share_unshareable_title),
        supporting = stringResource(R.string.profile_share_unshareable_body),
    )
}

@Composable
private fun ShareContent(
    shareUri: String,
    onCopyUri: (String) -> Unit,
    onShareImage: (Bitmap) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val qrState by rememberShareQrBitmap(shareUri)
    val qrBitmap = qrState.bitmap

    RipDpiPanelHeader(title = stringResource(R.string.profile_share_title))
    qrBitmap?.let { bitmap ->
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.profile_share_qr_content_description),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
        )
    }
    RipDpiTextField(
        value = shareUri,
        onValueChange = {},
        modifier = Modifier.fillMaxWidth(),
        decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.profile_share_uri_label)),
        behavior = RipDpiTextFieldBehavior(readOnly = true, singleLine = false),
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        RipDpiButton(
            text = stringResource(R.string.profile_share_copy_uri_action),
            onClick = { onCopyUri(shareUri) },
            variant = RipDpiButtonVariant.Secondary,
            leadingIcon = RipDpiIcons.Copy,
            modifier = Modifier.fillMaxWidth(),
        )
        RipDpiButton(
            text = stringResource(R.string.profile_share_share_image_action),
            onClick = { qrBitmap?.let(onShareImage) },
            enabled = qrBitmap != null,
            leadingIcon = RipDpiIcons.Share,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
