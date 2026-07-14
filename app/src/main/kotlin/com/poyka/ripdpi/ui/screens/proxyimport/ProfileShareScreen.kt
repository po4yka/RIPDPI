package com.poyka.ripdpi.ui.screens.proxyimport

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinner
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinnerSize
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun ProfileShareScreen(
    uiState: ProfileShareRouteUiState,
    qrBitmap: Bitmap?,
    qrLoading: Boolean,
    onBack: () -> Unit,
    onCopyLink: (String) -> Unit,
    onShareLink: (String) -> Unit,
    onCopySheet: (String) -> Unit,
    onShareSheet: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.profile_share_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.ProfileShare())),
    ) {
        when {
            uiState.loading -> {
                RipDpiCard {
                    RipDpiPanelHeader(
                        title = stringResource(R.string.profile_share_loading_title),
                        supporting = stringResource(R.string.profile_share_loading_body),
                    )
                }
            }

            uiState.unshareable || uiState.shareUri == null || uiState.setupSheet == null -> {
                RipDpiCard {
                    RipDpiPanelHeader(
                        title = stringResource(R.string.profile_share_unshareable_title),
                        supporting = stringResource(R.string.profile_share_unshareable_body),
                    )
                }
            }

            else -> {
                ProfileShareContent(
                    uiState = uiState,
                    shareUri = uiState.shareUri,
                    setupSheet = uiState.setupSheet,
                    qrBitmap = qrBitmap,
                    qrLoading = qrLoading,
                    onCopyLink = onCopyLink,
                    onShareLink = onShareLink,
                    onCopySheet = onCopySheet,
                    onShareSheet = onShareSheet,
                )
            }
        }
    }
}

@Composable
private fun ProfileShareContent(
    uiState: ProfileShareRouteUiState,
    shareUri: String,
    setupSheet: String,
    qrBitmap: Bitmap?,
    qrLoading: Boolean,
    onCopyLink: (String) -> Unit,
    onShareLink: (String) -> Unit,
    onCopySheet: (String) -> Unit,
    onShareSheet: (String) -> Unit,
) {
    WarningBanner(
        title = stringResource(R.string.profile_share_warning_title),
        message = stringResource(R.string.profile_share_warning_body),
        tone = WarningBannerTone.Warning,
        icon = RipDpiIcons.Warning,
    )
    ProfileShareQrCard(
        uiState = uiState,
        qrBitmap = qrBitmap,
        qrLoading = qrLoading,
    )
    ProfileShareLinkCard(
        shareUri = shareUri,
        onCopyLink = onCopyLink,
        onShareLink = onShareLink,
    )
    ProfileShareSetupSheetCard(
        setupSheet = setupSheet,
        onCopySheet = onCopySheet,
        onShareSheet = onShareSheet,
    )
}

@Composable
private fun ProfileShareQrCard(
    uiState: ProfileShareRouteUiState,
    qrBitmap: Bitmap?,
    qrLoading: Boolean,
) {
    RipDpiCard(modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ProfileShareQrCard)) {
        RipDpiPanelHeader(
            title = uiState.profile?.displayName ?: stringResource(R.string.profile_share_title),
            supporting = stringResource(R.string.profile_share_qr_body),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            when {
                qrBitmap != null -> {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.profile_share_qr_content_description),
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .ripDpiTestTag(RipDpiTestTags.ProfileShareQrImage),
                    )
                }

                qrLoading -> {
                    RipDpiSpinner(size = RipDpiSpinnerSize.Large)
                }

                else -> {
                    Text(
                        text = stringResource(R.string.profile_share_unshareable_body),
                        style = RipDpiThemeTokens.type.body,
                        color = RipDpiThemeTokens.colors.mutedForeground,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileShareLinkCard(
    shareUri: String,
    onCopyLink: (String) -> Unit,
    onShareLink: (String) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing

    RipDpiCard(modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ProfileShareLinkCard)) {
        RipDpiPanelHeader(
            title = stringResource(R.string.profile_share_link_title),
            supporting = stringResource(R.string.profile_share_link_body),
        )
        RipDpiTextField(
            value = shareUri,
            onValueChange = {},
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.ProfileShareLinkField),
            decoration = RipDpiTextFieldDecoration(label = stringResource(R.string.profile_share_subscription_label)),
            behavior = RipDpiTextFieldBehavior(readOnly = true, singleLine = false),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            RipDpiButton(
                text = stringResource(R.string.profile_share_copy_uri_action),
                onClick = { onCopyLink(shareUri) },
                modifier =
                    Modifier
                        .weight(1f)
                        .ripDpiTestTag(RipDpiTestTags.ProfileShareCopyLink),
                variant = RipDpiButtonVariant.Secondary,
                leadingIcon = RipDpiIcons.Copy,
            )
            RipDpiButton(
                text = stringResource(R.string.profile_share_share_link_action),
                onClick = { onShareLink(shareUri) },
                modifier =
                    Modifier
                        .weight(1f)
                        .ripDpiTestTag(RipDpiTestTags.ProfileShareShareLink),
                leadingIcon = RipDpiIcons.Share,
            )
        }
    }
}

@Composable
private fun ProfileShareSetupSheetCard(
    setupSheet: String,
    onCopySheet: (String) -> Unit,
    onShareSheet: (String) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing

    RipDpiCard(modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ProfileShareSheetCard)) {
        RipDpiPanelHeader(
            title = stringResource(R.string.profile_share_sheet_title),
            supporting = stringResource(R.string.profile_share_sheet_body),
        )
        Text(
            text = setupSheet,
            style = RipDpiThemeTokens.type.monoValue,
            color = RipDpiThemeTokens.colors.foreground,
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ProfileShareSheetText),
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            RipDpiButton(
                text = stringResource(R.string.profile_share_copy_sheet_action),
                onClick = { onCopySheet(setupSheet) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.ProfileShareCopySheet),
                variant = RipDpiButtonVariant.Secondary,
                leadingIcon = RipDpiIcons.Copy,
            )
            RipDpiButton(
                text = stringResource(R.string.profile_share_share_sheet_action),
                onClick = { onShareSheet(setupSheet) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.ProfileShareShareSheet),
                leadingIcon = RipDpiIcons.Share,
            )
        }
    }
}

internal fun previewProfileShareUiState(): ProfileShareRouteUiState {
    val profile =
        ProxyProfile.VlessReality(
            id = "preview",
            displayName = "Family setup",
            groupId = "",
            server = "edge.example.com",
            serverPort = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            realityPublicKey = "public-key",
            realityShortId = "ab12",
            serverName = "www.example.com",
        )
    val shareUri =
        "vless://11111111-2222-3333-4444-555555555555@edge.example.com:443" +
            "?security=reality&pbk=public-key&sid=ab12&sni=www.example.com&flow=xtls-rprx-vision#Family%20setup"
    return ProfileShareRouteUiState(
        loading = false,
        profileId = "preview",
        profile = profile,
        shareUri = shareUri,
        // Preview-only sample text. Mirrors the English render of
        // R.string.profile_share_setup_sheet_template (see ProfileSetupSheetFormatter)
        // so this catalog preview matches the production setup sheet without a
        // StringResolver in a non-composable function.
        setupSheet =
            """
            RIPDPI setup sheet

            Profile: ${profile.displayName}
            Type: ${profileKindLabel(profile)}

            Subscription/profile link:
            $shareUri

            Hiddify:
            1. Open Hiddify.
            2. Choose Add profile.
            3. Scan the QR code or paste the link above.

            AmneziaVPN:
            1. Open AmneziaVPN.
            2. Choose Add server from link or QR.
            3. Scan the QR code or paste the link above.

            Keep this sheet private. The QR code and link contain credentials.
            """.trimIndent(),
    )
}

@Preview(showBackground = true)
@Composable
private fun ProfileShareScreenPreview() {
    RipDpiTheme {
        ProfileShareScreen(
            uiState = previewProfileShareUiState(),
            qrBitmap = null,
            qrLoading = true,
            onBack = {},
            onCopyLink = {},
            onShareLink = {},
            onCopySheet = {},
            onShareSheet = {},
        )
    }
}
