package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.AnnotatedString
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.services.relayKindLabels
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiContextMenu
import com.poyka.ripdpi.ui.components.feedback.RipDpiContextMenuAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.profiles.AwgProfileItem
import com.poyka.ripdpi.ui.screens.profiles.ProfileMeasurementUiState
import com.poyka.ripdpi.ui.screens.profiles.awgMeasurementText
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

internal val DedicatedRelayEditors: Map<String, Route> =
    mapOf(
        RelayKindAnyTls to Route.AnyTlsProfile,
        RelayKindMieru to Route.MieruProfile,
        RelayKindSsh to Route.SshProfile,
    )

internal fun relayKindHasEditor(kind: String): Boolean =
    kind in DedicatedRelayEditors || isModeEditorRelayKindSupported(kind)

internal fun addableRelayKinds(): List<Pair<String, String>> = relayKindLabels().filter { relayKindHasEditor(it.first) }

@Composable
internal fun RelayServerRow(
    profile: RelayProfileUiState,
    selected: Boolean,
    actions: ConnectionProfileActions,
) {
    ServerRow(
        server = ServerKey.Relay(profile.id),
        name = profile.selectorLabel,
        renameFrom = profile.name,
        detail = profile.trustLabel,
        kindLabel = profile.kindLabel,
        onOpen = { actions.edit(profile.id) }.takeIf { relayKindHasEditor(profile.kind) },
        onShare = { actions.share(profile.id) },
        actions = actions,
        testTag = RipDpiTestTags.connectionRelayProfile(profile.id),
        rowTestTag = RipDpiTestTags.connectionRelayProfileRow(profile.id),
    ) {
        RipDpiButton(
            text =
                stringResource(
                    if (selected) R.string.profile_variants_cta_selected else R.string.profile_variants_cta_select,
                ),
            onClick = { actions.select(profile.id) },
            modifier = Modifier.weight(1f).ripDpiTestTag(RipDpiTestTags.connectionRelayProfileSelect(profile.id)),
            variant = RipDpiButtonVariant.Outline,
            wrapLabel = true,
            enabled = !selected,
        )
    }
}

@Composable
internal fun AwgServerRow(
    server: AwgProfileItem,
    actions: ConnectionProfileActions,
) {
    ServerRow(
        server = ServerKey.Awg(server.id),
        name = server.name,
        detail =
            if (server.needsPrivateKey) {
                stringResource(R.string.awg_field_private_key)
            } else {
                awgMeasurementText(server.measurement)
            },
        kindLabel = stringResource(R.string.awg_editor_title),
        onOpen = { actions.openAwg(server.id, server.needsPrivateKey) },
        onShare = { actions.shareAwg(server.id) }.takeIf { server.shareable },
        actions = actions,
        testTag = RipDpiTestTags.connectionAwgServer(server.id),
        rowTestTag = RipDpiTestTags.connectionAwgServerRow(server.id),
    ) {
        if (!server.needsPrivateKey) {
            RipDpiButton(
                text = stringResource(R.string.profile_utility_check),
                onClick = { actions.checkAwg(server.id) },
                modifier = Modifier.weight(1f).ripDpiTestTag(RipDpiTestTags.connectionAwgServerCheck(server.id)),
                enabled = server.measurement != ProfileMeasurementUiState.Checking,
                wrapLabel = true,
            )
        }
    }
}

@Composable
private fun ServerRow(
    server: ServerKey,
    name: String,
    detail: String?,
    renameFrom: String = name,
    kindLabel: String,
    onOpen: (() -> Unit)?,
    onShare: (() -> Unit)?,
    actions: ConnectionProfileActions,
    testTag: String,
    rowTestTag: String,
    leadingButtons: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val actionsLabel = stringResource(R.string.server_actions)
    Column(
        modifier = Modifier.ripDpiTestTag(testTag),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        Box(
            modifier =
                Modifier.serverRowClick(
                    tag = rowTestTag,
                    texts = listOfNotNull(name, detail, kindLabel).distinct(),
                    actionsLabel = actionsLabel,
                    onOpen = onOpen ?: { showMenu = true },
                    onActions = { showMenu = true },
                ),
        ) {
            SettingsRow(title = name, subtitle = detail, value = kindLabel, showChevron = onOpen != null)
            RipDpiContextMenu(
                visible = showMenu,
                actions = serverMenuActions(server, onShare, { renaming = true }, { deleting = true }, actions),
                onDismiss = { showMenu = false },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        ) {
            leadingButtons()
            RipDpiButton(
                text = actionsLabel,
                onClick = { showMenu = true },
                modifier = Modifier.weight(1f).ripDpiTestTag(RipDpiTestTags.connectionServerActions(server.id)),
                variant = RipDpiButtonVariant.Outline,
                wrapLabel = true,
                leadingIcon = RipDpiIcons.Overflow,
            )
        }
    }
    if (renaming) {
        RenameServerDialog(
            currentName = renameFrom,
            onRename = { actions.rename(server, it) },
            onDismiss = { renaming = false },
        )
    }
    if (deleting) {
        RipDpiDialog(
            onDismissRequest = { deleting = false },
            title = stringResource(R.string.server_delete_title, name),
            dialogTestTag = RipDpiTestTags.ServerDeleteDialog,
            dismissAction = RipDpiDialogAction(stringResource(R.string.config_cancel), { deleting = false }),
            confirmAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.server_action_delete),
                    onClick = {
                        deleting = false
                        actions.delete(server)
                    },
                    testTag = RipDpiTestTags.ServerDeleteConfirm,
                    destructive = true,
                ),
            visuals =
                RipDpiDialogVisuals(
                    message = stringResource(R.string.server_delete_body),
                    tone = RipDpiDialogTone.Destructive,
                ),
        )
    }
}

private fun Modifier.serverRowClick(
    tag: String,
    texts: List<String>,
    actionsLabel: String,
    onOpen: () -> Unit,
    onActions: () -> Unit,
): Modifier =
    combinedClickable(onLongClickLabel = actionsLabel, onLongClick = onActions, onClick = onOpen)
        .clearAndSetSemantics {
            testTag = tag
            set(SemanticsProperties.Text, texts.map(::AnnotatedString))
            role = Role.Button
            onClick {
                onOpen()
                true
            }
            onLongClick(actionsLabel) {
                onActions()
                true
            }
        }

@Composable
private fun serverMenuActions(
    server: ServerKey,
    onShare: (() -> Unit)?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    actions: ConnectionProfileActions,
): ImmutableList<RipDpiContextMenuAction> =
    listOfNotNull(
        RipDpiContextMenuAction(
            label = stringResource(R.string.server_action_rename),
            icon = RipDpiIcons.Edit,
            testTag = RipDpiTestTags.connectionServerAction(server.id, "rename"),
            onClick = onRename,
        ),
        RipDpiContextMenuAction(
            label = stringResource(R.string.server_action_duplicate),
            icon = RipDpiIcons.Copy,
            testTag = RipDpiTestTags.connectionServerAction(server.id, "duplicate"),
            onClick = { actions.duplicate(server) },
        ),
        onShare?.let {
            RipDpiContextMenuAction(
                label = stringResource(R.string.profile_share_action),
                icon = RipDpiIcons.Share,
                testTag = RipDpiTestTags.connectionServerAction(server.id, "share"),
                onClick = it,
            )
        },
        RipDpiContextMenuAction(
            label = stringResource(R.string.server_action_delete),
            icon = RipDpiIcons.Delete,
            destructive = true,
            testTag = RipDpiTestTags.connectionServerAction(server.id, "delete"),
            onClick = onDelete,
        ),
    ).toImmutableList()

@Composable
private fun RenameServerDialog(
    currentName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    val valid = name.isNotBlank() && name.trim() != currentName
    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.server_rename_title),
        dialogTestTag = RipDpiTestTags.ServerRenameDialog,
        dismissAction = RipDpiDialogAction(stringResource(R.string.config_cancel), onDismiss),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.config_save),
                onClick = {
                    if (valid) {
                        onDismiss()
                        onRename(name)
                    }
                },
                testTag = RipDpiTestTags.ServerRenameConfirm,
            ),
    ) {
        RipDpiTextField(
            value = name,
            onValueChange = { name = it },
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.server_rename_label),
                    testTag = RipDpiTestTags.ServerRenameField,
                ),
        )
    }
}

@Composable
internal fun ServerActionMessage(
    message: String?,
    onDismiss: () -> Unit,
) {
    message ?: return
    com.poyka.ripdpi.ui.components.feedback.WarningBanner(
        title = stringResource(R.string.server_action_refused_title),
        message = message,
        testTag = RipDpiTestTags.ServerActionMessage,
        onDismiss = onDismiss,
    )
}
