package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.protocolDisplayName
import com.poyka.ripdpi.ui.components.cards.RadioIndicator
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.ripDpiFeatureSurface

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod")
internal fun DnsActiveConfigurationCard(
    uiState: SettingsUiState,
    selectedResolver: DnsResolverOption?,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val title =
        selectedResolver?.let { it.title ?: it.titleRes?.let { res -> stringResource(res) } }
            ?: if (uiState.dns.dnsMode == DnsModeEncrypted) {
                stringResource(R.string.dns_custom_title)
            } else {
                stringResource(R.string.dns_mode_plain)
            }
    val endpointSummary =
        remember(
            uiState.dns.dnsMode,
            uiState.dns.encryptedDnsProtocol,
            uiState.dns.encryptedDnsDohUrl,
            uiState.dns.odoh.proxyUrl,
            uiState.dns.encryptedDnsHost,
            uiState.dns.encryptedDnsPort,
            uiState.dns.dnsIp,
        ) {
            activeEndpointSummary(uiState)
        }
    val bootstrapSummary =
        remember(uiState.dns.dnsMode, uiState.dns.encryptedDnsBootstrapIps) {
            if (uiState.dns.dnsMode == DnsModeEncrypted) {
                formatBootstrapPreview(uiState.dns.encryptedDnsBootstrapIps)
            } else {
                ""
            }
        }

    RipDpiCard(
        variant = if (uiState.dns.dnsMode == DnsModeEncrypted) RipDpiCardVariant.Elevated else RipDpiCardVariant.Tonal,
        animateContentSize = true,
    ) {
        Text(
            text = stringResource(R.string.dns_active_section_title),
            style = type.sectionTitle,
            color = colors.mutedForeground,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterVertically)
                        .size(RipDpiThemeTokens.components.feedback.decorativeBadgeSize)
                        .ripDpiFeatureSurface(
                            if (uiState.dns.dnsMode == DnsModeEncrypted) {
                                colors.infoContainer
                            } else {
                                colors.inputBackground
                            },
                            RipDpiThemeTokens.shapes.full,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (uiState.dns.dnsMode == DnsModeEncrypted) RipDpiIcons.Lock else RipDpiIcons.Dns,
                    contentDescription = null,
                    tint =
                        if (uiState.dns.dnsMode == DnsModeEncrypted) {
                            colors.infoContainerForeground
                        } else {
                            colors.foreground
                        },
                    modifier = Modifier.size(RipDpiIconSizes.Large),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.width(IntrinsicSize.Max),
                    style = type.screenTitle,
                    color = colors.foreground,
                )
            }
        }
        // The title names the resolver; the chips add only mode and protocol, never "Selected".
        if (uiState.dns.dnsMode == DnsModeEncrypted) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                DnsBadge(text = stringResource(R.string.dns_mode_doh))
                DnsBadge(text = protocolDisplayName(uiState.dns.encryptedDnsProtocol))
            }
        }
        DnsDetailRow(
            label = stringResource(R.string.dns_active_detail_endpoint),
            value = endpointSummary,
        )
        DnsDetailRow(
            label = stringResource(R.string.dns_active_detail_bootstrap),
            value =
                if (uiState.dns.dnsMode == DnsModeEncrypted) {
                    bootstrapSummary
                } else {
                    stringResource(R.string.dns_active_bootstrap_not_required)
                },
            monospace = uiState.dns.dnsMode == DnsModeEncrypted,
        )
        DnsDetailRow(
            label = stringResource(R.string.dns_active_detail_route),
            value = dnsActiveRouteDescription(uiState.isVpn, uiState.dns.dnsMode == DnsModeEncrypted),
            monospace = false,
        )
    }
}

@Composable
private fun dnsActiveRouteDescription(
    isVpn: Boolean,
    isEncrypted: Boolean,
): String =
    when {
        !isVpn && isEncrypted -> stringResource(R.string.dns_active_route_saved)
        !isVpn -> stringResource(R.string.config_dns_disabled_helper)
        isEncrypted -> stringResource(R.string.dns_active_route_encrypted_vpn)
        else -> stringResource(R.string.dns_active_route_plain_vpn)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DnsOptionCard(
    icon: ImageVector,
    title: String,
    body: String,
    selected: Boolean,
    badges: List<String>,
    onClick: () -> Unit,
    testTag: String? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    RipDpiCard(
        onClick = onClick,
        modifier =
            Modifier
                .semantics {
                    contentDescription = title
                    role = Role.RadioButton
                    this.selected = selected
                }.ripDpiTestTag(testTag),
        animateContentSize = true,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Box(Modifier.align(Alignment.CenterVertically)) { RadioIndicator(selected = selected) }
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterVertically)
                        .size(RipDpiThemeTokens.components.feedback.decorativeBadgeSize)
                        .ripDpiFeatureSurface(
                            if (selected) colors.infoContainer else colors.inputBackground,
                            RipDpiThemeTokens.shapes.full,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) colors.infoContainerForeground else colors.mutedForeground,
                    modifier = Modifier.size(RipDpiIconSizes.Large),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.width(IntrinsicSize.Max),
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                Text(
                    text = body,
                    modifier = Modifier.fillMaxWidth(),
                    style = type.body,
                    color = colors.mutedForeground,
                )
                // Badges belong to the text column so they never wrap under the radio and icon.
                if (badges.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        badges.forEach { badge ->
                            DnsBadge(text = badge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DnsBadge(
    text: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Box(
        modifier =
            modifier
                .ripDpiFeatureSurface(
                    if (highlighted) colors.infoContainer else colors.inputBackground,
                    RipDpiThemeTokens.shapes.full,
                ).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            modifier = Modifier.width(IntrinsicSize.Max),
            style = type.smallLabel,
            color = if (highlighted) colors.infoContainerForeground else colors.mutedForeground,
        )
    }
}

@Composable
internal fun DnsDetailRow(
    label: String,
    value: String,
    monospace: Boolean = true,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            text = label,
            modifier = Modifier.width(IntrinsicSize.Max),
            style = type.smallLabel,
            color = colors.mutedForeground,
        )
        Text(
            text = value,
            modifier = Modifier.width(IntrinsicSize.Max),
            style = if (monospace) type.monoSmall else type.body,
            color = colors.foreground,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DnsResolverCard(
    resolver: DnsResolverOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val resolverTitle = resolver.title ?: resolver.titleRes?.let { stringResource(it) } ?: ""
    val resolverBody = resolver.description ?: resolver.descriptionRes?.let { stringResource(it) } ?: ""
    RipDpiCard(
        onClick = onClick,
        modifier =
            Modifier
                .semantics {
                    contentDescription = resolverTitle
                    role = Role.RadioButton
                    this.selected = selected
                }.ripDpiTestTag(RipDpiTestTags.dnsResolver(resolver.providerId)),
        animateContentSize = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioIndicator(selected = selected)
                Text(
                    text = resolverTitle,
                    modifier = Modifier.weight(1f),
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
            }
            if (resolverBody.isNotEmpty()) {
                Text(
                    text = resolverBody,
                    modifier = Modifier.width(IntrinsicSize.Max),
                    style = type.body,
                    color = colors.mutedForeground,
                )
            }
            if (resolver.jurisdictionRu) {
                Text(
                    text = stringResource(R.string.detection_dns_provider_jurisdiction_ru_warning),
                    style = type.caption,
                    color = colors.warning,
                )
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            DnsBadge(text = resolver.address)
            DnsBadge(text = protocolDisplayName(resolver.protocol))
        }
        DnsDetailRow(
            label = stringResource(R.string.dns_active_detail_endpoint),
            value = resolver.dohUrl,
        )
        DnsDetailRow(
            label = stringResource(R.string.dns_active_detail_bootstrap),
            value = formatBootstrapPreview(resolver.bootstrapIps),
        )
    }
}
