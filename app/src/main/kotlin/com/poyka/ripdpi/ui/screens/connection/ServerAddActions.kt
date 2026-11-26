package com.poyka.ripdpi.ui.screens.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiContextMenu
import com.poyka.ripdpi.ui.components.feedback.RipDpiContextMenuAction
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.awg.rememberAwgConfFileImport
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun ServerAddActions(
    onPasteLink: () -> Unit,
    onScanQr: () -> Unit,
    onOpenRoute: (Route) -> Unit,
    modifier: Modifier = Modifier,
    onCreateRelay: ((kind: String) -> Unit)? = null,
) {
    val importFile = rememberAwgConfFileImport(onOpenRoute)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
        ServerAddButton(
            R.string.config_vpn_add_server_paste,
            RipDpiIcons.Paste,
            onPasteLink,
            RipDpiTestTags.ConnectionRelayAddServerPaste,
        )
        ServerAddButton(
            R.string.config_vpn_add_server_scan,
            RipDpiIcons.QrCodeScanner,
            onScanQr,
            RipDpiTestTags.ConnectionRelayAddServerScan,
        )
        ServerAddButton(
            R.string.config_vpn_import_awg_conf,
            RipDpiIcons.Vpn,
            importFile,
            RipDpiTestTags.ConnectionRelayImportAwgConf,
        )
        AddManuallyAction(onOpenRoute, onCreateRelay)
    }
}

@Composable
private fun ServerAddButton(
    labelRes: Int,
    icon: ImageVector,
    onClick: () -> Unit,
    testTag: String,
) {
    RipDpiButton(
        text = stringResource(labelRes),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().ripDpiTestTag(testTag),
        variant = RipDpiButtonVariant.Outline,
        leadingIcon = icon,
        wrapLabel = true,
    )
}

@Composable
private fun AddManuallyAction(
    onOpenRoute: (Route) -> Unit,
    onCreateRelay: ((kind: String) -> Unit)?,
) {
    var showMenu by rememberSaveable { mutableStateOf(false) }

    fun action(
        label: String,
        icon: ImageVector,
        testTag: String,
        onClick: () -> Unit,
    ) = RipDpiContextMenuAction(label = label, icon = icon, testTag = testTag) {
        showMenu = false
        onClick()
    }
    val relayActions =
        addableRelayKinds().mapNotNull { (kind, fallbackLabel) ->
            val label = modeEditorRelayKindLabel(kind) ?: fallbackLabel
            val editor = DedicatedRelayEditors[kind]
            when {
                editor != null -> {
                    action(label, RipDpiIcons.Public, RipDpiTestTags.connectionRelayCreateProfile(editor)) {
                        onOpenRoute(editor)
                    }
                }

                onCreateRelay != null -> {
                    action(label, RipDpiIcons.Public, RipDpiTestTags.connectionAddServerKind(kind)) {
                        onCreateRelay(kind)
                    }
                }

                else -> {
                    null
                }
            }
        }
    val otherActions =
        listOf(
            action(
                stringResource(R.string.awg_editor_title),
                RipDpiIcons.Vpn,
                RipDpiTestTags.connectionRelayCreateProfile(Route.AmneziaWgProfile()),
            ) { onOpenRoute(Route.AmneziaWgProfile()) },
            action(
                stringResource(R.string.config_vpn_add_subscription),
                RipDpiIcons.Add,
                RipDpiTestTags.connectionRelayCreateProfile(Route.SubscriptionAdd),
            ) { onOpenRoute(Route.SubscriptionAdd) },
        )
    Box {
        ServerAddButton(
            R.string.config_vpn_add_server_manual,
            RipDpiIcons.Add,
            { showMenu = true },
            RipDpiTestTags.ConnectionRelayAddProfile,
        )
        RipDpiContextMenu(
            visible = showMenu,
            actions = (otherActions + relayActions).toImmutableList(),
            onDismiss = { showMenu = false },
        )
    }
}
