package com.poyka.ripdpi.ui.components.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons

data class RipDpiOverflowMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val testTag: String? = null,
)

/** The overflow icon of a top app bar with the secondary actions in a Material menu. */
@Composable
fun RipDpiOverflowMenu(
    contentDescription: String,
    items: List<RipDpiOverflowMenuItem>,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        RipDpiIconButton(
            icon = RipDpiIcons.Overflow,
            contentDescription = contentDescription,
            onClick = { expanded = true },
            modifier = Modifier.ripDpiTestTag(testTag),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    enabled = item.enabled,
                    onClick = {
                        expanded = false
                        item.onClick()
                    },
                    modifier = Modifier.ripDpiTestTag(item.testTag),
                )
            }
        }
    }
}
