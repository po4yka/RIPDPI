package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.protocolDisplayName
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun DnsActiveConfigurationCard(
    uiState: SettingsUiState,
    selectedResolver: DnsResolverOption?,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val motion = RipDpiThemeTokens.motion
    val title =
        selectedResolver?.let { stringResource(it.titleRes) }
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
        modifier =
            Modifier.animateContentSize(
                animationSpec =
                    tween(
                        durationMillis = motion.duration(motion.stateDurationMillis),
                        easing = RipDpiMotion.StandardEasing,
                    ),
            ),
    ) {
        Text(
            text = stringResource(R.string.dns_active_section_title),
            style = type.sectionTitle,
            color = colors.mutedForeground,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .background(
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
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = title,
                    style = type.screenTitle,
                    color = colors.foreground,
                )
                Text(
                    text = uiState.dns.dnsSummary,
                    style = type.body,
                    color = colors.mutedForeground,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            DnsBadge(
                text =
                    if (uiState.dns.dnsMode == DnsModeEncrypted) {
                        stringResource(R.string.dns_mode_doh)
                    } else {
                        stringResource(R.string.dns_mode_plain)
                    },
            )
            if (uiState.dns.dnsMode == DnsModeEncrypted) {
                DnsBadge(text = protocolDisplayName(uiState.dns.encryptedDnsProtocol))
            }
            DnsBadge(
                text =
                    if (selectedResolver != null || uiState.dns.dnsMode == DnsModePlainUdp) {
                        stringResource(R.string.dns_selected_badge)
                    } else {
                        stringResource(R.string.dns_resolver_custom_active)
                    },
                highlighted = true,
            )
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
            value =
                when {
                    !uiState.isVpn -> stringResource(R.string.dns_active_route_saved)
                    uiState.dns.dnsMode == DnsModeEncrypted -> stringResource(R.string.dns_active_route_encrypted_vpn)
                    else -> stringResource(R.string.dns_active_route_plain_vpn)
                },
            monospace = false,
        )
    }
}

@Composable
internal fun DnsOptionCard(
    icon: ImageVector,
    title: String,
    body: String,
    selected: Boolean,
    badges: List<String>,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val motion = RipDpiThemeTokens.motion

    RipDpiCard(
        variant = if (selected) RipDpiCardVariant.Elevated else RipDpiCardVariant.Outlined,
        onClick = onClick,
        modifier =
            Modifier.animateContentSize(
                animationSpec =
                    tween(
                        durationMillis = motion.duration(motion.stateDurationMillis),
                        easing = RipDpiMotion.StandardEasing,
                    ),
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .background(
                            if (selected) colors.infoContainer else colors.inputBackground,
                            RipDpiThemeTokens.shapes.full,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) colors.infoContainerForeground else colors.mutedForeground,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = title,
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                Text(
                    text = body,
                    style = type.body,
                    color = colors.mutedForeground,
                )
            }
            if (selected) {
                DnsBadge(
                    text = stringResource(R.string.dns_selected_badge),
                    highlighted = true,
                )
            }
        }
        if (badges.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                badges.forEach { badge ->
                    DnsBadge(text = badge)
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
                .background(
                    if (highlighted) colors.infoContainer else colors.inputBackground,
                    RipDpiThemeTokens.shapes.full,
                ).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
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
            style = type.smallLabel,
            color = colors.mutedForeground,
        )
        Text(
            text = value,
            style = if (monospace) type.monoSmall else type.body,
            color = colors.foreground,
        )
    }
}

@Composable
internal fun DnsResolverCard(
    resolver: DnsResolverOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val motion = RipDpiThemeTokens.motion
    RipDpiCard(
        variant = if (selected) RipDpiCardVariant.Elevated else RipDpiCardVariant.Outlined,
        onClick = onClick,
        modifier =
            Modifier
                .animateContentSize(
                    animationSpec =
                        tween(
                            durationMillis = motion.duration(motion.stateDurationMillis),
                            easing = RipDpiMotion.StandardEasing,
                        ),
                ).ripDpiTestTag(RipDpiTestTags.dnsResolver(resolver.providerId)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = stringResource(resolver.titleRes),
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                Text(
                    text = stringResource(resolver.descriptionRes),
                    style = type.body,
                    color = colors.mutedForeground,
                )
            }
            if (selected) {
                DnsBadge(
                    text = stringResource(R.string.dns_selected_badge),
                    highlighted = true,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
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
