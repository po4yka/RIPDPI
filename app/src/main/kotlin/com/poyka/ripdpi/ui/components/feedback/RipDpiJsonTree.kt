package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val JsonIndentPerLevel = 16
private val JsonRowVerticalPadding = 2.dp
private val JsonChevronSize = 14.dp

sealed interface RipDpiJsonNode {
    data class Leaf(
        val key: String?,
        val value: String,
        val kind: Kind,
    ) : RipDpiJsonNode {
        enum class Kind { String, Number, Boolean, Null }
    }

    data class Branch(
        val key: String?,
        val children: List<RipDpiJsonNode>,
        val isArray: Boolean,
    ) : RipDpiJsonNode
}

/**
 * Collapsible JSON tree renderer with key/value tokens colored by
 * kind. Branches collapse via a leading chevron. All values in
 * monoLog type. Suitable for diagnostic dumps and config preview.
 *
 * Matches `components-json-tree.html`.
 */
@Composable
fun RipDpiJsonTree(
    root: RipDpiJsonNode,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        JsonNodeRow(node = root, depth = 0)
    }
}

@Composable
private fun JsonNodeRow(
    node: RipDpiJsonNode,
    depth: Int,
) {
    val colors = RipDpiThemeTokens.colors
    val indent = (depth * JsonIndentPerLevel).dp
    when (node) {
        is RipDpiJsonNode.Leaf -> {
            Row(
                modifier =
                    Modifier.padding(
                        start = indent,
                        top = JsonRowVerticalPadding,
                        bottom = JsonRowVerticalPadding,
                    ),
            ) {
                if (node.key != null) {
                    Text(
                        text = "\"${node.key}\": ",
                        style = RipDpiThemeTokens.type.monoLog.copy(color = colors.accent),
                    )
                }
                Text(
                    text =
                        when (node.kind) {
                            RipDpiJsonNode.Leaf.Kind.String -> "\"${node.value}\""
                            else -> node.value
                        },
                    style =
                        RipDpiThemeTokens.type.monoLog.copy(
                            color =
                                when (node.kind) {
                                    RipDpiJsonNode.Leaf.Kind.String -> colors.success
                                    RipDpiJsonNode.Leaf.Kind.Number -> colors.foreground
                                    RipDpiJsonNode.Leaf.Kind.Boolean -> colors.warning
                                    RipDpiJsonNode.Leaf.Kind.Null -> colors.mutedForeground
                                },
                        ),
                )
            }
        }

        is RipDpiJsonNode.Branch -> {
            var open by remember(node.key ?: node.hashCode()) { mutableStateOf(true) }
            val (openBracket, closeBracket) = if (node.isArray) "[" to "]" else "{" to "}"
            Row(
                modifier =
                    Modifier
                        .padding(start = indent, top = JsonRowVerticalPadding, bottom = JsonRowVerticalPadding)
                        .ripDpiClickable(enabled = true, role = Role.Button) { open = !open },
            ) {
                Icon(
                    imageVector = if (open) RipDpiIcons.KeyboardArrowDown else RipDpiIcons.ChevronRight,
                    contentDescription = stringResource(if (open) R.string.cd_collapse else R.string.cd_expand),
                    tint = colors.mutedForeground,
                    modifier = Modifier.size(JsonChevronSize),
                )
                if (node.key != null) {
                    Text(
                        text = "\"${node.key}\": ",
                        style = RipDpiThemeTokens.type.monoLog.copy(color = colors.accent),
                    )
                }
                Text(
                    text = openBracket + if (!open) " … $closeBracket" else "",
                    style = RipDpiThemeTokens.type.monoLog.copy(color = colors.foreground),
                )
            }
            if (open) {
                node.children.forEach { JsonNodeRow(node = it, depth = depth + 1) }
                Text(
                    text = closeBracket,
                    modifier = Modifier.padding(start = indent),
                    style = RipDpiThemeTokens.type.monoLog.copy(color = colors.foreground),
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "RipDpiJsonTree (light)")
@Composable
private fun RipDpiJsonTreeLightPreview() {
    val sample =
        RipDpiJsonNode.Branch(
            key = null,
            isArray = false,
            children =
                listOf(
                    RipDpiJsonNode.Leaf("strategy", "tlsrec", RipDpiJsonNode.Leaf.Kind.String),
                    RipDpiJsonNode.Leaf("ttl", "4", RipDpiJsonNode.Leaf.Kind.Number),
                    RipDpiJsonNode.Leaf("enabled", "true", RipDpiJsonNode.Leaf.Kind.Boolean),
                    RipDpiJsonNode.Branch(
                        key = "dns",
                        isArray = true,
                        children =
                            listOf(
                                RipDpiJsonNode.Leaf(null, "1.1.1.1", RipDpiJsonNode.Leaf.Kind.String),
                                RipDpiJsonNode.Leaf(null, "8.8.8.8", RipDpiJsonNode.Leaf.Kind.String),
                            ),
                    ),
                    RipDpiJsonNode.Leaf("notes", "null", RipDpiJsonNode.Leaf.Kind.Null),
                ),
        )
    RipDpiComponentPreview { RipDpiJsonTree(root = sample) }
}

@Preview(showBackground = true, name = "RipDpiJsonTree (dark)")
@Composable
private fun RipDpiJsonTreeDarkPreview() {
    val sample =
        RipDpiJsonNode.Branch(
            key = null,
            isArray = false,
            children = listOf(RipDpiJsonNode.Leaf("ok", "true", RipDpiJsonNode.Leaf.Kind.Boolean)),
        )
    RipDpiComponentPreview(themePreference = "dark") { RipDpiJsonTree(root = sample) }
}
