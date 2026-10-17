@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.Fill
import androidx.compose.foundation.style.StyleResolver
import androidx.compose.foundation.style.StyleResolverScope
import androidx.compose.foundation.style.StyleState
import androidx.compose.foundation.style.appearance
import androidx.compose.foundation.style.placement
import androidx.compose.foundation.style.styleResolver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.foundation.style.graphicsLayer as styleGraphicsLayer

/**
 * Merge RDS defaults at layer zero with caller values at layer one.
 *
 * Defaults must read state directly and provide resolved values without state helpers or
 * [androidx.compose.foundation.style.StyleLayerScope.styleLayer]. Caller styles may use state
 * helpers to increase their own priority. This contract does not cover defaults at higher layers.
 * Create and bind the resolver within the same static composition-local provider scope.
 */
@Composable
internal fun rememberRipDpiStyleResolver(
    defaultStyle: CommonStyle,
    visualStyle: CommonStyle,
    styleState: StyleState,
): StyleResolver {
    val currentContext = currentCompositionLocalContext
    val compositionLocalContext = remember { mutableStateOf(currentContext) }
    // Notify the node after applyChanges has installed its new composition-local map.
    SideEffect { compositionLocalContext.value = currentContext }
    val mergedStyle =
        remember(defaultStyle, visualStyle) {
            CommonStyle {
                // Static locals replace their map without snapshot reads at the bound node.
                // Observe map changes without replacing the resolver or its active animations.
                compositionLocalContext.value.let {
                    with(defaultStyle) { applyStyle() }
                    styleLayer { with(visualStyle) { applyStyle() } }
                }
            }
        }
    return remember(mergedStyle, styleState) { StyleResolver(mergedStyle, styleState) }
}

/** Bind once on the outer component; child content uses [ripDpiStyleContent]. */
fun Modifier.ripDpiStyleSurface(
    resolver: StyleResolver,
    shape: Shape = RectangleShape,
    clip: Boolean = false,
): Modifier = styleResolver(resolver).ripDpiStyleAppearance(resolver, shape, clip)

/**
 * Apply an already bound resolver. Keep required sizes, behavior, and semantics outside this
 * helper. Separate binding lets components keep transforms and shadows in their original order.
 */
fun Modifier.ripDpiStyleAppearance(
    resolver: StyleResolver,
    shape: Shape = RectangleShape,
    clip: Boolean = false,
): Modifier {
    val fallbackShape = shape
    val clipContent = clip
    return styleGraphicsLayer(
        layerRequired = {
            resolver.resolve { clipContent || RipDpiStyleProperties.containerAlpha.value.coerceIn(0f, 1f) != 1f }
        },
    ) {
        val layer = this
        resolver.resolve {
            layer.alpha = RipDpiStyleProperties.containerAlpha.value.coerceIn(0f, 1f)
            layer.shape = resolvedShape(fallbackShape)
            layer.clip = clipContent
        }
    }.appearance {
        val appearance = this
        resolver.resolve {
            appearance.shape = resolvedShape(fallbackShape)
            val fill = RipDpiStyleProperties.background.value
            val fillAlpha = getOrNull(RipDpiStyleProperties.backgroundAlpha)
            appearance.background =
                if (fill is Fill.Color && fillAlpha != null) {
                    Fill(fill.color.copy(alpha = fillAlpha.coerceIn(0f, 1f)))
                } else {
                    fill
                }
            val color = RipDpiStyleProperties.borderColor.value
            val width = RipDpiStyleProperties.borderWidth.value
            // 0.dp is a hairline in appearance, but RDS uses it to mean no border.
            if (color.isSpecified && width.isSpecified && width > 0.dp) {
                appearance.border = Fill(color)
                appearance.borderWidth = width
            }
        }
    }.placement {
        val placement = this
        left = 0.dp
        right = 0.dp
        top = 0.dp
        bottom = 0.dp
        resolver.resolve { placement.minHeight = RipDpiStyleProperties.minHeight.value }
    }
}

/** Transform content without changing the outer hit target or rebinding its resolver. */
fun Modifier.ripDpiStyleContent(
    resolver: StyleResolver,
    applyPadding: Boolean = false,
): Modifier {
    val content =
        styleGraphicsLayer(
            layerRequired = {
                resolver.resolve {
                    RipDpiStyleProperties.contentScale.value != 1f ||
                        RipDpiStyleProperties.contentAlpha.value.coerceIn(0f, 1f) != 1f
                }
            },
        ) {
            val layer = this
            resolver.resolve {
                val scale = RipDpiStyleProperties.contentScale.value
                layer.scaleX = scale
                layer.scaleY = scale
                layer.alpha = RipDpiStyleProperties.contentAlpha.value.coerceIn(0f, 1f)
            }
        }
    return if (applyPadding) content.ripDpiStylePadding(resolver) else content
}

/** Apply logical inner padding separately when transforms must stay before the shell. */
fun Modifier.ripDpiStylePadding(resolver: StyleResolver): Modifier = padding(RipDpiStylePadding(resolver))

private fun StyleResolverScope.resolvedShape(fallback: Shape): Shape {
    val radius = getOrNull(RipDpiStyleProperties.cornerRadius)
    return if (radius != null && radius.isSpecified) RoundedCornerShape(radius.coerceAtLeast(0.dp)) else fallback
}

/** PaddingValues reads happen during measurement, after the surface resolver is bound. */
private class RipDpiStylePadding(
    private val resolver: StyleResolver,
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        resolver.resolve {
            if (layoutDirection == LayoutDirection.Ltr) {
                RipDpiStyleProperties.paddingStart.value
            } else {
                RipDpiStyleProperties.paddingEnd.value
            }
        }

    override fun calculateTopPadding(): Dp = resolver.resolve { RipDpiStyleProperties.paddingTop.value }

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        resolver.resolve {
            if (layoutDirection == LayoutDirection.Ltr) {
                RipDpiStyleProperties.paddingEnd.value
            } else {
                RipDpiStyleProperties.paddingStart.value
            }
        }

    override fun calculateBottomPadding(): Dp = resolver.resolve { RipDpiStyleProperties.paddingBottom.value }
}
