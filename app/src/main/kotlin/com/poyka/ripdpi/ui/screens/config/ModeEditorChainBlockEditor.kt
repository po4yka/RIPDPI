package com.poyka.ripdpi.ui.screens.config

import androidx.annotation.StringRes
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
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
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
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
            onChainChanged = actions.onChainDslReplaced,
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
                state =
                    rememberRipDpiTextFieldState(
                        value = draft.chainDsl,
                        onValueChange = actions.onChainDslChanged,
                    ),
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
                lineLimits = TextFieldLineLimits.MultiLine(),
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
    val shape = RipDpiThemeTokens.shapes.sm

    Column(
        modifier =
            Modifier
                .ripDpiTestTag(RipDpiTestTags.ModeEditorChainVisual)
                .alpha(if (enabled) 1f else ChainOverriddenAlpha)
                .then(if (enabled) Modifier else Modifier.semantics { disabled() })
                .fillMaxWidth()
                .border(RipDpiStroke.Thin, colors.border, shape)
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
    val detailRes =
        if (step.kind.isTlsPrelude) {
            R.string.config_chain_step_detail_tls_prelude
        } else {
            R.string.config_chain_step_detail_tcp_send
        }
    ModeEditorChainBlock(
        section = "tcp",
        index = index,
        count = count,
        descriptor = descriptor,
        value = step.marker,
        detail =
            androidx.compose.ui.res
                .stringResource(detailRes),
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
        value =
            if (step.kind == UdpChainStepKind.IpFrag2Udp) {
                androidx.compose.ui.res
                    .pluralStringResource(R.plurals.config_chain_step_byte_count, step.splitBytes, step.splitBytes)
            } else {
                androidx.compose.ui.res
                    .pluralStringResource(R.plurals.config_chain_step_packet_count, step.count, step.count)
            },
        detail =
            androidx.compose.ui.res
                .stringResource(R.string.config_chain_step_detail_udp_quic),
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
    val shape = RipDpiThemeTokens.shapes.sm

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(modeEditorChainBlockTag(section, index))
                .border(RipDpiStroke.Thin, colors.border, shape)
                .background(colors.background, shape)
                .padding(spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text =
                    androidx.compose.ui.res
                        .stringResource(descriptor.labelRes),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text =
                    androidx.compose.ui.res
                        .stringResource(descriptor.explanationRes),
                style = RipDpiThemeTokens.type.caption,
                color = colors.mutedForeground,
            )
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
        text =
            androidx.compose.ui.res
                .stringResource(descriptor.addLabelRes),
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
    @StringRes val labelRes: Int,
    @StringRes val addLabelRes: Int,
    @StringRes val explanationRes: Int,
    val registryId: String = id,
)

private val TcpSplitDescriptor =
    ChainStepDescriptor(
        id = "split",
        registryId = "split",
        labelRes = R.string.config_chain_step_split_label,
        addLabelRes = R.string.config_chain_step_split_add,
        explanationRes = R.string.config_chain_step_split_explanation,
    )

private val TcpFakeDescriptor =
    ChainStepDescriptor(
        id = "fake",
        registryId = "fake",
        labelRes = R.string.config_chain_step_fake_label,
        addLabelRes = R.string.config_chain_step_fake_add,
        explanationRes = R.string.config_chain_step_fake_explanation,
    )

private val TcpTlsRecDescriptor =
    ChainStepDescriptor(
        id = "tlsrec",
        registryId = "tls_rec",
        labelRes = R.string.config_chain_step_tlsrec_label,
        addLabelRes = R.string.config_chain_step_tlsrec_add,
        explanationRes = R.string.config_chain_step_tlsrec_explanation,
    )

private val UdpFakeBurstDescriptor =
    ChainStepDescriptor(
        id = "fake_burst",
        registryId = "udplen",
        labelRes = R.string.config_chain_step_fake_burst_label,
        addLabelRes = R.string.config_chain_step_fake_burst_add,
        explanationRes = R.string.config_chain_step_fake_burst_explanation,
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
                labelRes = R.string.config_chain_step_tlsrandrec_label,
                addLabelRes = R.string.config_chain_step_tlsrandrec_add,
                explanationRes = R.string.config_chain_step_tlsrandrec_explanation,
            ),
        TcpChainStepKind.SynData to
            ChainStepDescriptor(
                id = "syndata",
                registryId = "split",
                labelRes = R.string.config_chain_step_syndata_label,
                addLabelRes = R.string.config_chain_step_syndata_add,
                explanationRes = R.string.config_chain_step_syndata_explanation,
            ),
        TcpChainStepKind.SeqOverlap to
            ChainStepDescriptor(
                id = "seqovl",
                registryId = "seq_overlap",
                labelRes = R.string.config_chain_step_seqovl_label,
                addLabelRes = R.string.config_chain_step_seqovl_add,
                explanationRes = R.string.config_chain_step_seqovl_explanation,
            ),
        TcpChainStepKind.Disorder to
            ChainStepDescriptor(
                id = "disorder",
                registryId = "disorder",
                labelRes = R.string.config_chain_step_disorder_label,
                addLabelRes = R.string.config_chain_step_disorder_add,
                explanationRes = R.string.config_chain_step_disorder_explanation,
            ),
        TcpChainStepKind.MultiDisorder to
            ChainStepDescriptor(
                id = "multidisorder",
                registryId = "multi_disorder",
                labelRes = R.string.config_chain_step_multidisorder_label,
                addLabelRes = R.string.config_chain_step_multidisorder_add,
                explanationRes = R.string.config_chain_step_multidisorder_explanation,
            ),
        TcpChainStepKind.FakeSplit to
            ChainStepDescriptor(
                id = "fakedsplit",
                registryId = "fake",
                labelRes = R.string.config_chain_step_fakedsplit_label,
                addLabelRes = R.string.config_chain_step_fakedsplit_add,
                explanationRes = R.string.config_chain_step_fakedsplit_explanation,
            ),
        TcpChainStepKind.FakeDisorder to
            ChainStepDescriptor(
                id = "fakeddisorder",
                registryId = "fake",
                labelRes = R.string.config_chain_step_fakeddisorder_label,
                addLabelRes = R.string.config_chain_step_fakeddisorder_add,
                explanationRes = R.string.config_chain_step_fakeddisorder_explanation,
            ),
        TcpChainStepKind.HostFake to
            ChainStepDescriptor(
                id = "hostfake",
                registryId = "fake",
                labelRes = R.string.config_chain_step_hostfake_label,
                addLabelRes = R.string.config_chain_step_hostfake_add,
                explanationRes = R.string.config_chain_step_hostfake_explanation,
            ),
        TcpChainStepKind.FakeRst to
            ChainStepDescriptor(
                id = "fakerst",
                registryId = "fake_rst",
                labelRes = R.string.config_chain_step_fakerst_label,
                addLabelRes = R.string.config_chain_step_fakerst_add,
                explanationRes = R.string.config_chain_step_fakerst_explanation,
            ),
        TcpChainStepKind.Oob to
            ChainStepDescriptor(
                id = "oob",
                registryId = "oob",
                labelRes = R.string.config_chain_step_oob_label,
                addLabelRes = R.string.config_chain_step_oob_add,
                explanationRes = R.string.config_chain_step_oob_explanation,
            ),
        TcpChainStepKind.Disoob to
            ChainStepDescriptor(
                id = "disoob",
                registryId = "oob",
                labelRes = R.string.config_chain_step_disoob_label,
                addLabelRes = R.string.config_chain_step_disoob_add,
                explanationRes = R.string.config_chain_step_disoob_explanation,
            ),
        TcpChainStepKind.IpFrag2 to
            ChainStepDescriptor(
                id = "ipfrag2",
                registryId = "ip_frag",
                labelRes = R.string.config_chain_step_ipfrag2_label,
                addLabelRes = R.string.config_chain_step_ipfrag2_add,
                explanationRes = R.string.config_chain_step_ipfrag2_explanation,
            ),
    )

private val UdpChainDescriptors =
    mapOf(
        UdpChainStepKind.FakeBurst to UdpFakeBurstDescriptor,
        UdpChainStepKind.DummyPrepend to
            ChainStepDescriptor(
                id = "dummy_prepend",
                registryId = "udplen",
                labelRes = R.string.config_chain_step_dummy_prepend_label,
                addLabelRes = R.string.config_chain_step_dummy_prepend_add,
                explanationRes = R.string.config_chain_step_dummy_prepend_explanation,
            ),
        UdpChainStepKind.QuicSniSplit to
            ChainStepDescriptor(
                id = "quic_sni_split",
                registryId = "udplen",
                labelRes = R.string.config_chain_step_quic_sni_split_label,
                addLabelRes = R.string.config_chain_step_quic_sni_split_add,
                explanationRes = R.string.config_chain_step_quic_sni_split_explanation,
            ),
        UdpChainStepKind.QuicFakeVersion to
            ChainStepDescriptor(
                id = "quic_fake_version",
                registryId = "udplen",
                labelRes = R.string.config_chain_step_quic_fake_version_label,
                addLabelRes = R.string.config_chain_step_quic_fake_version_add,
                explanationRes = R.string.config_chain_step_quic_fake_version_explanation,
            ),
        UdpChainStepKind.QuicCryptoSplit to
            ChainStepDescriptor(
                id = "quic_crypto_split",
                registryId = "udplen",
                labelRes = R.string.config_chain_step_quic_crypto_split_label,
                addLabelRes = R.string.config_chain_step_quic_crypto_split_add,
                explanationRes = R.string.config_chain_step_quic_crypto_split_explanation,
            ),
        UdpChainStepKind.QuicPaddingLadder to
            ChainStepDescriptor(
                id = "quic_padding_ladder",
                registryId = "udplen",
                labelRes = R.string.config_chain_step_quic_padding_ladder_label,
                addLabelRes = R.string.config_chain_step_quic_padding_ladder_add,
                explanationRes = R.string.config_chain_step_quic_padding_ladder_explanation,
            ),
        UdpChainStepKind.QuicCidChurn to
            ChainStepDescriptor(
                id = "quic_cid_churn",
                registryId = "udplen",
                labelRes = R.string.config_chain_step_quic_cid_churn_label,
                addLabelRes = R.string.config_chain_step_quic_cid_churn_add,
                explanationRes = R.string.config_chain_step_quic_cid_churn_explanation,
            ),
        UdpChainStepKind.QuicPacketNumberGap to
            ChainStepDescriptor(
                id = "quic_packet_number_gap",
                registryId = "udplen",
                labelRes = R.string.config_chain_step_quic_pn_gap_label,
                addLabelRes = R.string.config_chain_step_quic_pn_gap_add,
                explanationRes = R.string.config_chain_step_quic_pn_gap_explanation,
            ),
        UdpChainStepKind.QuicVersionNegotiationDecoy to
            ChainStepDescriptor(
                id = "quic_version_negotiation_decoy",
                registryId = "udplen",
                labelRes = R.string.config_chain_step_quic_vn_decoy_label,
                addLabelRes = R.string.config_chain_step_quic_vn_decoy_add,
                explanationRes = R.string.config_chain_step_quic_vn_decoy_explanation,
            ),
        UdpChainStepKind.QuicMultiInitialRealistic to
            ChainStepDescriptor(
                id = "quic_multi_initial_realistic",
                registryId = "udplen",
                labelRes = R.string.config_chain_step_quic_multi_initial_label,
                addLabelRes = R.string.config_chain_step_quic_multi_initial_add,
                explanationRes = R.string.config_chain_step_quic_multi_initial_explanation,
            ),
        UdpChainStepKind.IpFrag2Udp to
            ChainStepDescriptor(
                id = "ipfrag2_udp",
                registryId = "ip_frag",
                labelRes = R.string.config_chain_step_ipfrag2_udp_label,
                addLabelRes = R.string.config_chain_step_ipfrag2_udp_add,
                explanationRes = R.string.config_chain_step_ipfrag2_udp_explanation,
            ),
    )

private fun descriptorForTcp(kind: TcpChainStepKind): ChainStepDescriptor = TcpChainDescriptors.getValue(kind)

private fun descriptorForUdp(kind: UdpChainStepKind): ChainStepDescriptor = UdpChainDescriptors.getValue(kind)

private const val ChainOverriddenAlpha = 0.55f
