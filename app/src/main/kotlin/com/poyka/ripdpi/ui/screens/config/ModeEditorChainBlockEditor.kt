package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigFieldStrategyChain
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.data.StrategyChainSet
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.formatStrategyChainDsl
import com.poyka.ripdpi.data.isTlsPrelude
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.validateStrategyChainUsage
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButtonStyle
import com.poyka.ripdpi.ui.components.inputs.RipDpiConfigTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun ModeEditorChainBlockEditor(
    draft: ConfigDraft,
    uiState: ConfigUiState,
    actions: ModeEditorActions,
) {
    val chainOverridden = draft.useCommandLineSettings
    var showRaw by rememberSaveable { mutableStateOf(false) }
    val chainState = remember(draft.chainDsl, draft.mode, draft.useCommandLineSettings) { draft.chainValidationState() }
    val showRawEditor = showRaw || chainState.chain == null

    Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
        ModeEditorChainBlockSurface(
            draft = draft,
            chainState = chainState,
            enabled = !chainOverridden,
            onChainChanged = actions.onChainDslChanged,
        )
        if (chainOverridden) {
            Text(
                text =
                    androidx.compose.ui.res
                        .stringResource(R.string.config_chain_cli_override_note),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
        ModeEditorRawToggle(
            expanded = showRawEditor,
            enabled = !chainOverridden,
            onToggle = { showRaw = !showRaw },
        )
        if (showRawEditor) {
            RipDpiConfigTextField(
                value = draft.chainDsl,
                onValueChange = actions.onChainDslChanged,
                decoration =
                    RipDpiTextFieldDecoration(
                        label =
                            androidx.compose.ui.res
                                .stringResource(R.string.config_chain_editor_label),
                        placeholder =
                            androidx.compose.ui.res
                                .stringResource(R.string.config_placeholder_chain_dsl),
                        helperText =
                            androidx.compose.ui.res
                                .stringResource(R.string.config_chain_editor_helper_brief),
                        errorText =
                            validationMessage(uiState.validationErrors[ConfigFieldStrategyChain] ?: chainState.error),
                        testTag = RipDpiTestTags.ModeEditorChainDsl,
                    ),
                multiline = true,
                behavior =
                    RipDpiTextFieldBehavior(
                        enabled = !chainOverridden,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeEditorChainBlockSurface(
    draft: ConfigDraft,
    chainState: ChainValidationState,
    enabled: Boolean,
    onChainChanged: (String) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val shape = RoundedCornerShape(8.dp)

    Column(
        modifier =
            Modifier
                .ripDpiTestTag(RipDpiTestTags.ModeEditorChainVisual)
                .alpha(if (enabled) 1f else ChainOverriddenAlpha)
                .then(if (enabled) Modifier else Modifier.semantics { disabled() })
                .fillMaxWidth()
                .border(1.dp, colors.border, shape)
                .background(colors.card, shape)
                .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(
            text =
                androidx.compose.ui.res
                    .stringResource(R.string.config_chain_summary_label, draft.chainSummary),
            style = RipDpiThemeTokens.type.caption,
            color = colors.mutedForeground,
        )
        ModeEditorChainValidationLine(chainState = chainState)
        val chain = chainState.chain
        if (chain == null) {
            Text(
                text =
                    androidx.compose.ui.res
                        .stringResource(R.string.config_chain_block_invalid_body),
                style = RipDpiThemeTokens.type.body,
                color = colors.mutedForeground,
            )
        } else {
            ModeEditorParsedChainBlocks(
                chain = chain,
                enabled = enabled,
                onChainChanged = onChainChanged,
            )
            ModeEditorAddBlockButtons(
                chain = chain,
                enabled = enabled,
                onChainChanged = onChainChanged,
            )
        }
    }
}

@Composable
private fun ModeEditorParsedChainBlocks(
    chain: StrategyChainSet,
    enabled: Boolean,
    onChainChanged: (String) -> Unit,
) {
    Column(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ModeEditorChainBlockList),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        chain.tcpSteps.forEachIndexed { index, step ->
            ModeEditorTcpBlock(
                step = step,
                index = index,
                count = chain.tcpSteps.size,
                enabled = enabled,
                onMove = { target -> onChainChanged(chain.moveTcp(index, target).toDsl()) },
                onRemove = { onChainChanged(chain.removeTcp(index).toDsl()) },
            )
        }
        chain.udpSteps.forEachIndexed { index, step ->
            ModeEditorUdpBlock(
                step = step,
                index = index,
                count = chain.udpSteps.size,
                enabled = enabled,
                onMove = { target -> onChainChanged(chain.moveUdp(index, target).toDsl()) },
                onRemove = { onChainChanged(chain.removeUdp(index).toDsl()) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeEditorAddBlockButtons(
    chain: StrategyChainSet,
    enabled: Boolean,
    onChainChanged: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        ModeEditorAddBlockButton(
            descriptor = TcpSplitDescriptor,
            enabled = enabled,
            onClick = {
                onChainChanged(
                    chain.addTcp(TcpChainStepModel(TcpChainStepKind.Split, "midsld")).toDsl(),
                )
            },
        )
        ModeEditorAddBlockButton(
            descriptor = TcpFakeDescriptor,
            enabled = enabled,
            onClick = {
                onChainChanged(
                    chain.addTcp(TcpChainStepModel(TcpChainStepKind.Fake, "host")).toDsl(),
                )
            },
        )
        ModeEditorAddBlockButton(
            descriptor = TcpTlsRecDescriptor,
            enabled = enabled,
            onClick = {
                onChainChanged(
                    chain.addTcpPrelude(TcpChainStepModel(TcpChainStepKind.TlsRec, "sniext+1")).toDsl(),
                )
            },
        )
        ModeEditorAddBlockButton(
            descriptor = UdpFakeBurstDescriptor,
            enabled = enabled,
            onClick = {
                onChainChanged(
                    chain.addUdp(UdpChainStepModel(kind = UdpChainStepKind.FakeBurst, count = 3)).toDsl(),
                )
            },
        )
    }
}

@Composable
private fun ModeEditorChainValidationLine(chainState: ChainValidationState) {
    val colors = RipDpiThemeTokens.colors
    val text =
        if (chainState.error == null) {
            androidx.compose.ui.res
                .stringResource(R.string.config_chain_block_valid)
        } else {
            androidx.compose.ui.res
                .stringResource(R.string.config_chain_block_invalid, chainState.error)
        }
    Text(
        text = text,
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = if (chainState.error == null) colors.success else colors.destructive,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ModeEditorChainValidation),
    )
}

@Composable
private fun ModeEditorRawToggle(
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    RipDpiButton(
        text =
            androidx.compose.ui.res.stringResource(
                if (expanded) R.string.config_chain_raw_hide else R.string.config_chain_raw_show,
            ),
        onClick = onToggle,
        enabled = enabled,
        variant = RipDpiButtonVariant.Outline,
        density = RipDpiControlDensity.Compact,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.ModeEditorChainRawToggle),
    )
}

@Composable
private fun ModeEditorTcpBlock(
    step: TcpChainStepModel,
    index: Int,
    count: Int,
    enabled: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val descriptor = descriptorForTcp(step.kind)
    ModeEditorChainBlock(
        section = "tcp",
        index = index,
        count = count,
        descriptor = descriptor,
        value = step.marker,
        detail = if (step.kind.isTlsPrelude) "TLS prelude" else "TCP send step",
        enabled = enabled,
        onMove = onMove,
        onRemove = onRemove,
    )
}

@Composable
private fun ModeEditorUdpBlock(
    step: UdpChainStepModel,
    index: Int,
    count: Int,
    enabled: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val descriptor = descriptorForUdp(step.kind)
    ModeEditorChainBlock(
        section = "udp",
        index = index,
        count = count,
        descriptor = descriptor,
        value = if (step.kind == UdpChainStepKind.IpFrag2Udp) "${step.splitBytes} bytes" else "${step.count} packets",
        detail = "UDP/QUIC step",
        enabled = enabled,
        onMove = onMove,
        onRemove = onRemove,
    )
}

@Composable
private fun ModeEditorChainBlock(
    section: String,
    index: Int,
    count: Int,
    descriptor: ChainStepDescriptor,
    value: String,
    detail: String,
    enabled: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(modeEditorChainBlockTag(section, index))
                .border(1.dp, colors.border, shape)
                .background(colors.background, shape)
                .padding(spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(text = descriptor.label, style = RipDpiThemeTokens.type.bodyEmphasis, color = colors.foreground)
            Text(text = descriptor.explanation, style = RipDpiThemeTokens.type.caption, color = colors.mutedForeground)
            Text(
                text = "$detail - registry:${descriptor.registryId} - $value",
                style = RipDpiThemeTokens.type.monoValue,
                color = colors.foreground,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            RipDpiIconButton(
                icon = RipDpiIcons.KeyboardArrowUp,
                contentDescription =
                    androidx.compose.ui.res
                        .stringResource(R.string.config_chain_move_up),
                onClick = { onMove(index - 1) },
                enabled = enabled && index > 0,
                density = RipDpiControlDensity.Compact,
                style = RipDpiIconButtonStyle.Outline,
                modifier = Modifier.ripDpiTestTag(modeEditorChainMoveUpTag(section, index)),
            )
            RipDpiIconButton(
                icon = RipDpiIcons.KeyboardArrowDown,
                contentDescription =
                    androidx.compose.ui.res
                        .stringResource(R.string.config_chain_move_down),
                onClick = { onMove(index + 1) },
                enabled = enabled && index < count - 1,
                density = RipDpiControlDensity.Compact,
                style = RipDpiIconButtonStyle.Outline,
                modifier = Modifier.ripDpiTestTag(modeEditorChainMoveDownTag(section, index)),
            )
            RipDpiIconButton(
                icon = RipDpiIcons.Delete,
                contentDescription =
                    androidx.compose.ui.res
                        .stringResource(R.string.config_chain_remove_block),
                onClick = onRemove,
                enabled = enabled,
                density = RipDpiControlDensity.Compact,
                style = RipDpiIconButtonStyle.Ghost,
                modifier = Modifier.ripDpiTestTag(modeEditorChainRemoveTag(section, index)),
            )
        }
    }
}

@Composable
private fun ModeEditorAddBlockButton(
    descriptor: ChainStepDescriptor,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    RipDpiButton(
        text = descriptor.addLabel,
        onClick = onClick,
        enabled = enabled,
        variant = RipDpiButtonVariant.Secondary,
        density = RipDpiControlDensity.Compact,
        leadingIcon = Icons.Outlined.Add,
        modifier = Modifier.ripDpiTestTag(modeEditorChainAddTag(descriptor.id)),
    )
}

private fun modeEditorChainAddTag(stepId: String): String = RipDpiTestTags.ModeEditorChainAddPrefix + stepId

private fun modeEditorChainBlockTag(
    section: String,
    index: Int,
): String = RipDpiTestTags.ModeEditorChainBlockPrefix + section + "-" + index

private fun modeEditorChainMoveUpTag(
    section: String,
    index: Int,
): String = RipDpiTestTags.ModeEditorChainMoveUpPrefix + section + "-" + index

private fun modeEditorChainMoveDownTag(
    section: String,
    index: Int,
): String = RipDpiTestTags.ModeEditorChainMoveDownPrefix + section + "-" + index

private fun modeEditorChainRemoveTag(
    section: String,
    index: Int,
): String = RipDpiTestTags.ModeEditorChainRemovePrefix + section + "-" + index

private data class ChainValidationState(
    val chain: StrategyChainSet?,
    val error: String?,
)

private fun ConfigDraft.chainValidationState(): ChainValidationState {
    val parsed =
        parseStrategyChainDsl(chainDsl).fold(
            onSuccess = { it },
            onFailure = { return ChainValidationState(chain = null, error = it.message ?: "invalid_chain") },
        )
    return runCatching {
        validateStrategyChainUsage(
            tcpSteps = parsed.tcpSteps,
            udpSteps = parsed.udpSteps,
            mode = mode,
            useCommandLineSettings = useCommandLineSettings,
        )
    }.fold(
        onSuccess = { ChainValidationState(chain = parsed, error = null) },
        onFailure = { ChainValidationState(chain = parsed, error = it.message ?: "invalid_chain") },
    )
}

private fun StrategyChainSet.addTcp(step: TcpChainStepModel): StrategyChainSet = copy(tcpSteps = tcpSteps + step)

private fun StrategyChainSet.addTcpPrelude(step: TcpChainStepModel): StrategyChainSet {
    val insertAt = tcpSteps.indexOfFirst { !it.kind.isTlsPrelude }.let { if (it < 0) tcpSteps.size else it }
    return copy(tcpSteps = tcpSteps.toMutableList().apply { add(insertAt, step) })
}

private fun StrategyChainSet.addUdp(step: UdpChainStepModel): StrategyChainSet = copy(udpSteps = udpSteps + step)

private fun StrategyChainSet.moveTcp(
    from: Int,
    to: Int,
): StrategyChainSet = copy(tcpSteps = tcpSteps.move(from, to))

private fun StrategyChainSet.moveUdp(
    from: Int,
    to: Int,
): StrategyChainSet = copy(udpSteps = udpSteps.move(from, to))

private fun StrategyChainSet.removeTcp(index: Int): StrategyChainSet =
    copy(
        tcpSteps =
            tcpSteps.toMutableList().apply {
                removeAt(index)
            },
    )

private fun StrategyChainSet.removeUdp(index: Int): StrategyChainSet =
    copy(
        udpSteps =
            udpSteps.toMutableList().apply {
                removeAt(index)
            },
    )

private fun StrategyChainSet.toDsl(): String = formatStrategyChainDsl(tcpSteps, udpSteps)

private fun <T> List<T>.move(
    from: Int,
    to: Int,
): List<T> {
    if (from !in indices || to !in indices || from == to) {
        return this
    }
    return toMutableList().apply {
        val item = removeAt(from)
        add(to, item)
    }
}

private data class ChainStepDescriptor(
    val id: String,
    val label: String,
    val addLabel: String,
    val explanation: String,
    val registryId: String = id,
)

private val TcpSplitDescriptor =
    ChainStepDescriptor(
        id = "split",
        registryId = "split",
        label = "Split",
        addLabel = "Add split",
        explanation = "Splits the first payload at the selected Host/SNI marker.",
    )

private val TcpFakeDescriptor =
    ChainStepDescriptor(
        id = "fake",
        registryId = "fake",
        label = "Fake packet",
        addLabel = "Add fake",
        explanation = "Injects a fake low-TTL packet before the real payload.",
    )

private val TcpTlsRecDescriptor =
    ChainStepDescriptor(
        id = "tlsrec",
        registryId = "tls_rec",
        label = "TLS record split",
        addLabel = "Add tlsrec",
        explanation = "Fragments the TLS record before TCP send steps run.",
    )

private val UdpFakeBurstDescriptor =
    ChainStepDescriptor(
        id = "fake_burst",
        registryId = "udplen",
        label = "QUIC fake burst",
        addLabel = "Add UDP fake",
        explanation = "Sends fake QUIC Initial packets before the real datagram.",
    )

private val TcpChainDescriptors =
    mapOf(
        TcpChainStepKind.Split to TcpSplitDescriptor,
        TcpChainStepKind.Fake to TcpFakeDescriptor,
        TcpChainStepKind.TlsRec to TcpTlsRecDescriptor,
        TcpChainStepKind.TlsRandRec to
            ChainStepDescriptor(
                id = "tlsrandrec",
                registryId = "tls_rand_rec",
                label = "Random TLS records",
                addLabel = "Add tlsrandrec",
                explanation = "Randomizes TLS record fragments before TCP send steps.",
            ),
        TcpChainStepKind.SynData to
            ChainStepDescriptor(
                id = "syndata",
                registryId = "split",
                label = "SYN data",
                addLabel = "Add syndata",
                explanation = "Places initial data on the SYN path when supported.",
            ),
        TcpChainStepKind.SeqOverlap to
            ChainStepDescriptor(
                id = "seqovl",
                registryId = "seq_overlap",
                label = "Sequence overlap",
                addLabel = "Add seqovl",
                explanation = "Overlaps TCP sequence bytes to confuse reassembly.",
            ),
        TcpChainStepKind.Disorder to
            ChainStepDescriptor(
                id = "disorder",
                registryId = "disorder",
                label = "Disorder",
                addLabel = "Add disorder",
                explanation = "Sends an early low-TTL segment before the real ordering.",
            ),
        TcpChainStepKind.MultiDisorder to
            ChainStepDescriptor(
                id = "multidisorder",
                registryId = "multi_disorder",
                label = "Multi-disorder",
                addLabel = "Add multidisorder",
                explanation = "Uses multiple disorder markers as a dedicated send family.",
            ),
        TcpChainStepKind.FakeSplit to
            ChainStepDescriptor(
                id = "fakedsplit",
                registryId = "fake",
                label = "Fake split",
                addLabel = "Add fakedsplit",
                explanation = "Combines split delivery with a fake second fragment.",
            ),
        TcpChainStepKind.FakeDisorder to
            ChainStepDescriptor(
                id = "fakeddisorder",
                registryId = "fake",
                label = "Fake disorder",
                addLabel = "Add fakeddisorder",
                explanation = "Combines disorder delivery with a fake fragment.",
            ),
        TcpChainStepKind.HostFake to
            ChainStepDescriptor(
                id = "hostfake",
                registryId = "fake",
                label = "Host fake",
                addLabel = "Add hostfake",
                explanation = "Targets the Host/SNI region with a fake hostname.",
            ),
        TcpChainStepKind.FakeRst to
            ChainStepDescriptor(
                id = "fakerst",
                registryId = "fake_rst",
                label = "Fake RST",
                addLabel = "Add fakerst",
                explanation = "Injects a fake reset packet for middlebox state poisoning.",
            ),
        TcpChainStepKind.Oob to
            ChainStepDescriptor(
                id = "oob",
                registryId = "oob",
                label = "Out-of-band",
                addLabel = "Add oob",
                explanation = "Sends the selected byte through TCP urgent data.",
            ),
        TcpChainStepKind.Disoob to
            ChainStepDescriptor(
                id = "disoob",
                registryId = "oob",
                label = "Disorder OOB",
                addLabel = "Add disoob",
                explanation = "Combines low-TTL disorder with urgent data.",
            ),
        TcpChainStepKind.IpFrag2 to
            ChainStepDescriptor(
                id = "ipfrag2",
                registryId = "ip_frag",
                label = "IP fragmentation",
                addLabel = "Add ipfrag2",
                explanation = "Fragments the TCP payload at the IP layer.",
            ),
    )

private val UdpChainDescriptors =
    mapOf(
        UdpChainStepKind.FakeBurst to UdpFakeBurstDescriptor,
        UdpChainStepKind.DummyPrepend to
            ChainStepDescriptor(
                id = "dummy_prepend",
                registryId = "udplen",
                label = "Dummy prepend",
                addLabel = "Add dummy",
                explanation = "Prepends non-QUIC dummy datagrams.",
            ),
        UdpChainStepKind.QuicSniSplit to
            ChainStepDescriptor(
                id = "quic_sni_split",
                registryId = "udplen",
                label = "QUIC SNI split",
                addLabel = "Add SNI split",
                explanation = "Splits the QUIC Initial SNI view.",
            ),
        UdpChainStepKind.QuicFakeVersion to
            ChainStepDescriptor(
                id = "quic_fake_version",
                registryId = "udplen",
                label = "QUIC fake version",
                addLabel = "Add fake version",
                explanation = "Poisons QUIC version detection.",
            ),
        UdpChainStepKind.QuicCryptoSplit to
            ChainStepDescriptor(
                id = "quic_crypto_split",
                registryId = "udplen",
                label = "QUIC crypto split",
                addLabel = "Add crypto split",
                explanation = "Splits QUIC crypto frame parsing.",
            ),
        UdpChainStepKind.QuicPaddingLadder to
            ChainStepDescriptor(
                id = "quic_padding_ladder",
                registryId = "udplen",
                label = "QUIC padding ladder",
                addLabel = "Add padding",
                explanation = "Varies Initial packet padding sizes.",
            ),
        UdpChainStepKind.QuicCidChurn to
            ChainStepDescriptor(
                id = "quic_cid_churn",
                registryId = "udplen",
                label = "QUIC CID churn",
                addLabel = "Add CID churn",
                explanation = "Varies QUIC connection IDs across decoys.",
            ),
        UdpChainStepKind.QuicPacketNumberGap to
            ChainStepDescriptor(
                id = "quic_packet_number_gap",
                registryId = "udplen",
                label = "QUIC PN gap",
                addLabel = "Add PN gap",
                explanation = "Creates packet-number gaps in decoy traffic.",
            ),
        UdpChainStepKind.QuicVersionNegotiationDecoy to
            ChainStepDescriptor(
                id = "quic_version_negotiation_decoy",
                registryId = "udplen",
                label = "Version negotiation decoy",
                addLabel = "Add VN decoy",
                explanation = "Adds QUIC version-negotiation decoys.",
            ),
        UdpChainStepKind.QuicMultiInitialRealistic to
            ChainStepDescriptor(
                id = "quic_multi_initial_realistic",
                registryId = "udplen",
                label = "Multi Initial",
                addLabel = "Add multi Initial",
                explanation = "Sends realistic alternate QUIC Initial packets.",
            ),
        UdpChainStepKind.IpFrag2Udp to
            ChainStepDescriptor(
                id = "ipfrag2_udp",
                registryId = "ip_frag",
                label = "UDP IP fragmentation",
                addLabel = "Add UDP ipfrag2",
                explanation = "Fragments the UDP datagram at the IP layer.",
            ),
    )

private fun descriptorForTcp(kind: TcpChainStepKind): ChainStepDescriptor = TcpChainDescriptors.getValue(kind)

private fun descriptorForUdp(kind: UdpChainStepKind): ChainStepDescriptor = UdpChainDescriptors.getValue(kind)

private const val ChainOverriddenAlpha = 0.55f
