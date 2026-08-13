package com.poyka.ripdpi.ui.screens.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.inputs.rememberRipDpiTextFieldState
import com.poyka.ripdpi.ui.components.ripDpiToggleable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Searchable, multi-select sheet of installed apps. Reusable across the rule editor and a later
 * split-tunnel surface. The app list is supplied by the caller (loaded off the main thread in a
 * ViewModel — never reads [android.content.pm.PackageManager] in the composable). [onConfirm]
 * returns the selected package-name set; [onDismiss] cancels without committing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(
    apps: List<InstalledAppItem>,
    initialSelection: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(initialSelection) }
    val spacing = RipDpiThemeTokens.spacing
    val maxListHeight = RipDpiThemeTokens.layout.formMaxWidth

    val filtered =
        remember(apps, query) {
            if (query.isBlank()) {
                apps
            } else {
                apps.filter {
                    it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
            }
        }

    RipDpiBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.app_picker_title),
        testTag = RipDpiTestTags.AppPickerSheet,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            RipDpiTextField(
                state =
                    rememberRipDpiTextFieldState(
                        value = query,
                        onValueChange = { query = it },
                    ),
                decoration =
                    RipDpiTextFieldDecoration(
                        placeholder = stringResource(R.string.app_picker_search),
                        testTag = RipDpiTestTags.AppPickerSearch,
                    ),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    AppPickerRow(
                        app = app,
                        checked = app.packageName in selection,
                        onToggle = {
                            selection =
                                if (app.packageName in selection) {
                                    selection - app.packageName
                                } else {
                                    selection + app.packageName
                                }
                        },
                    )
                }
            }
            RipDpiButton(
                text = stringResource(R.string.app_picker_done),
                onClick = { onConfirm(selection) },
                modifier = Modifier.fillMaxWidth(),
                density = RipDpiControlDensity.Compact,
                trailingIcon = com.poyka.ripdpi.ui.theme.RipDpiIcons.Check,
            )
        }
    }
}

@Composable
private fun AppPickerRow(
    app: InstalledAppItem,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiToggleable(
                    value = checked,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                ).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = app.label, style = type.body, color = colors.foreground)
            Text(text = app.packageName, style = type.caption, color = colors.mutedForeground)
        }
    }
}
