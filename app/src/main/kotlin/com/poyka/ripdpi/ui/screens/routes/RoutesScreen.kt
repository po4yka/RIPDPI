package com.poyka.ripdpi.ui.screens.routes

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleNetwork
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButtonStyle
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiSwitch
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.roundToInt

@Composable
fun RoutesRoute(
    onBack: () -> Unit,
    onEditRule: (Long) -> Unit,
    onAddRule: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RoutesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RoutesScreen(
        state = state,
        onBack = onBack,
        onAddRule = onAddRule,
        onEditRule = onEditRule,
        onToggleEnabled = viewModel::toggleEnabled,
        onDelete = viewModel::delete,
        onReorder = viewModel::reorder,
        reorderFailures = viewModel.reorderFailures,
        modifier = modifier,
    )
}

@Composable
internal fun RoutesScreen(
    state: RoutesUiState,
    onBack: () -> Unit,
    onAddRule: () -> Unit,
    onEditRule: (Long) -> Unit,
    onToggleEnabled: (RuleEntity) -> Unit,
    onDelete: (RuleEntity) -> Unit,
    onReorder: (List<Long>) -> Unit,
    modifier: Modifier = Modifier,
    reorderFailures: Flow<Unit> = emptyFlow(),
) {
    // Local working order so a hand-rolled drag can reorder optimistically before persisting.
    var localRows by remember(state.rows) { mutableStateOf(state.rows) }
    val rollbackToPersistedRows by rememberUpdatedState { localRows = state.rows }

    // Roll the optimistic order back to the persisted state when a reorder write fails.
    LaunchedEffect(reorderFailures) {
        reorderFailures.collect { rollbackToPersistedRows() }
    }

    fun move(
        from: Int,
        to: Int,
    ) {
        if (to !in localRows.indices) return
        val mutable = localRows.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        localRows = mutable.toImmutableList()
        onReorder(mutable.map { it.rule.id })
    }

    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.Routes))
                .fillMaxSize(),
        title = stringResource(R.string.title_routes),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        actions = {
            RipDpiIconButton(
                icon = RipDpiIcons.Config,
                contentDescription = stringResource(R.string.routes_add_rule),
                onClick = onAddRule,
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.RoutesAddRule),
            )
        },
    ) {
        if (localRows.isEmpty()) {
            item(key = "routes_empty") {
                WarningBanner(
                    title = stringResource(R.string.routes_empty_title),
                    message = stringResource(R.string.routes_empty_body),
                    tone = WarningBannerTone.Info,
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.RoutesList),
                )
            }
        } else {
            itemsIndexed(localRows, key = { _, row -> row.rule.id }) { index, row ->
                RuleListRow(
                    row = row,
                    index = index,
                    count = localRows.size,
                    onEdit = { onEditRule(row.rule.id) },
                    onToggleEnabled = { onToggleEnabled(row.rule) },
                    onDelete = { onDelete(row.rule) },
                    onMoveUp = { move(index, index - 1) },
                    onMoveDown = { move(index, index + 1) },
                    onDragBy = { delta -> move(index, index + delta) },
                    modifier =
                        Modifier.ripDpiTestTag(
                            if (index == 0) RipDpiTestTags.RoutesList else null,
                        ),
                )
            }
        }
    }
}

@Composable
private fun RuleListRow(
    row: RuleRow,
    index: Int,
    count: Int,
    onEdit: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragBy: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Drag distance (in px) that commits a one-slot reorder. Derived from an existing layout token
    // rather than a literal .dp so the RDS no-literal-token floor holds in the component layer.
    val dragThreshold = RipDpiThemeTokens.components.inputs.multilineFieldMinHeight
    val rowHeightPx = with(LocalDensity.current) { dragThreshold.toPx() }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val moveUpLabel = stringResource(R.string.routes_move_up)
    val moveDownLabel = stringResource(R.string.routes_move_down)

    RipDpiCard(
        onClick = onEdit,
        modifier =
            modifier
                .semantics {
                    customActions =
                        buildList {
                            if (index > 0) {
                                add(
                                    CustomAccessibilityAction(moveUpLabel) {
                                        onMoveUp()
                                        true
                                    },
                                )
                            }
                            if (index < count - 1) {
                                add(
                                    CustomAccessibilityAction(moveDownLabel) {
                                        onMoveDown()
                                        true
                                    },
                                )
                            }
                        }
                }.offset { IntOffset(0, dragOffsetY.roundToInt()) }
                .pointerInput(index, count) {
                    detectDragGesturesAfterLongPress(
                        onDragEnd = { dragOffsetY = 0f },
                        onDragCancel = { dragOffsetY = 0f },
                    ) { change, dragAmount ->
                        change.consume()
                        dragOffsetY += dragAmount.y
                        // Once the accumulated drag crosses a row height, commit a one-slot move.
                        if (dragOffsetY > rowHeightPx) {
                            onDragBy(1)
                            dragOffsetY = 0f
                        } else if (dragOffsetY < -rowHeightPx) {
                            onDragBy(-1)
                            dragOffsetY = 0f
                        }
                    }
                },
    ) {
        RuleListRowSummary(row = row, onToggleEnabled = onToggleEnabled)
        RuleListRowControls(
            index = index,
            count = count,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun RuleListRowSummary(
    row: RuleRow,
    onToggleEnabled: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    val newRuleName = stringResource(R.string.routes_add_rule)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.rule.name.ifBlank { newRuleName },
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = ruleSummaryLine(row),
                style = type.secondaryBody,
                color = colors.mutedForeground,
            )
        }
        RipDpiSwitch(checked = row.rule.enabled, onCheckedChange = { onToggleEnabled() })
    }
}

@Composable
private fun RuleListRowControls(
    index: Int,
    count: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        RipDpiIconButton(
            icon = RipDpiIcons.KeyboardArrowUp,
            contentDescription = stringResource(R.string.routes_move_up),
            onClick = onMoveUp,
            enabled = index > 0,
        )
        RipDpiIconButton(
            icon = RipDpiIcons.KeyboardArrowDown,
            contentDescription = stringResource(R.string.routes_move_down),
            onClick = onMoveDown,
            enabled = index < count - 1,
        )
        RipDpiIconButton(
            icon = RipDpiIcons.Delete,
            contentDescription = stringResource(R.string.rule_editor_delete),
            onClick = onDelete,
            style = RipDpiIconButtonStyle.Destructive,
        )
    }
}

@Composable
private fun ruleSummaryLine(row: RuleRow): String {
    val separator = stringResource(R.string.routes_rule_summary_separator)
    val counts = ruleSummaryCounts(row.rule)
    val parts =
        buildList {
            if (counts.domains > 0) {
                add(pluralStringResource(R.plurals.rule_summary_domains, counts.domains, counts.domains))
            }
            if (counts.ips > 0) {
                add(pluralStringResource(R.plurals.rule_summary_ips, counts.ips, counts.ips))
            }
            if (counts.ports > 0) {
                add(pluralStringResource(R.plurals.rule_summary_ports, counts.ports, counts.ports))
            }
            if (counts.sourcePorts > 0) {
                add(pluralStringResource(R.plurals.rule_summary_src_ports, counts.sourcePorts, counts.sourcePorts))
            }
            if (counts.hasProcess) {
                add(stringResource(R.string.rule_summary_process))
            }
            if (counts.apps > 0) {
                add(pluralStringResource(R.plurals.rule_summary_apps, counts.apps, counts.apps))
            }
            add(
                when (counts.network) {
                    RuleNetwork.TCP -> stringResource(R.string.rule_summary_net_tcp)
                    RuleNetwork.UDP -> stringResource(R.string.rule_summary_net_udp)
                    RuleNetwork.BOTH -> stringResource(R.string.rule_summary_net_both)
                },
            )
            add("→ ${row.outboundLabel}")
        }
    return parts.joinToString(separator)
}

@Composable
private fun previewRows() =
    listOf(
        RuleRow(
            RuleEntity(
                id = 1L,
                name = "Streaming direct",
                domains = "domain_suffix:netflix.com\ngeosite:netflix",
                network = RuleNetwork.BOTH,
                outboundTag = OutboundTag.Bypass,
            ),
            outboundLabel = "Bypass (direct)",
        ),
        RuleRow(
            RuleEntity(
                id = 2L,
                name = "Ads block",
                domains = "geosite:category-ads-all",
                network = RuleNetwork.TCP,
                outboundTag = OutboundTag.Block,
            ),
            outboundLabel = "Block",
        ),
    )

@Preview(showBackground = true)
@Composable
private fun previewRoutesScreen() {
    RipDpiTheme(themePreference = "light") {
        RoutesScreen(
            state = RoutesUiState(rows = previewRows().toImmutableList()),
            onBack = {},
            onAddRule = {},
            onEditRule = {},
            onToggleEnabled = {},
            onDelete = {},
            onReorder = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun previewRoutesScreenDark() {
    RipDpiTheme(themePreference = "dark") {
        RoutesScreen(
            state = RoutesUiState(rows = persistentListOf()),
            onBack = {},
            onAddRule = {},
            onEditRule = {},
            onToggleEnabled = {},
            onDelete = {},
            onReorder = {},
        )
    }
}
