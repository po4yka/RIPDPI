package com.poyka.ripdpi.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RipDpiMotionTest {
    @Test
    fun `semantic tweens use the expected duration buckets`() {
        assertEquals(DefaultRipDpiMotion.quickDurationMillis, DefaultRipDpiMotion.quickTween<Float>().durationMillis)
        assertEquals(DefaultRipDpiMotion.stateDurationMillis, DefaultRipDpiMotion.stateTween<Float>().durationMillis)
        assertEquals(
            DefaultRipDpiMotion.emphasizedDurationMillis,
            DefaultRipDpiMotion.emphasizedTween<Float>().durationMillis,
        )
        assertEquals(DefaultRipDpiMotion.routeDurationMillis, DefaultRipDpiMotion.routeTween<Float>().durationMillis)
    }

    @Test
    fun `reduced motion clamps durations and disables infinite motion`() {
        val reducedMotion = DefaultRipDpiMotion.copy(reducedMotion = true)

        assertEquals(80, reducedMotion.quickTween<Float>().durationMillis)
        assertEquals(110, reducedMotion.stateTween<Float>().durationMillis)
        assertEquals(160, reducedMotion.emphasizedTween<Float>().durationMillis)
        assertEquals(130, reducedMotion.routeTween<Float>().durationMillis)
        assertFalse(reducedMotion.allowsInfiniteMotion)
    }

    @Test
    fun `disabled animations collapse durations and disable infinite motion`() {
        val disabledMotion = DefaultRipDpiMotion.copy(animationsEnabled = false)

        assertEquals(0, disabledMotion.quickTween<Float>().durationMillis)
        assertEquals(0, disabledMotion.stateTween<Float>().durationMillis)
        assertEquals(0, disabledMotion.routeTween<Float>().durationMillis)
        assertFalse(disabledMotion.allowsInfiniteMotion)
    }

    @Test
    fun `motion aware spring removes bounce when reduced motion is active`() {
        val reducedMotion = DefaultRipDpiMotion.copy(reducedMotion = true)
        val spring = reducedMotion.motionAwareSpring<Float>(expressive = true)

        assertEquals(1f, spring.dampingRatio)
        assertEquals(RipDpiMotion.StandardSpringStiffness, spring.stiffness)
    }

    @Test
    fun `semantic tweens preserve default easing categories`() {
        assertSame(RipDpiMotion.StandardEasing, DefaultRipDpiMotion.quickTween<Float>().easing)
        assertSame(RipDpiMotion.StandardEasing, DefaultRipDpiMotion.stateTween<Float>().easing)
        assertSame(RipDpiMotion.EmphasizedDecelerate, DefaultRipDpiMotion.emphasizedTween<Float>().easing)
        assertSame(RipDpiMotion.EmphasizedDecelerate, DefaultRipDpiMotion.routeTween<Float>().easing)
        assertTrue(DefaultRipDpiMotion.allowsInfiniteMotion)
    }

    /**
     * Enforces the RDS rule: raw `tween(`, `spring(`, `cubicBezier(` /
     * `CubicBezierEasing(` literals MAY appear ONLY inside `ui/theme/`.
     * Component / screen code must consume the named motion specs
     * (stateTween, motionAwareSpring, shimmerSpec, pulseSpec, etc.) so
     * the reduced-motion clamp + token discipline stays centralized.
     *
     * Sentinel patterns use a negative lookbehind to allow method calls
     * like `motion.stateTween()` (preceded by `.`) and identifier prefixes
     * like `motionAwareSpring(` (preceded by a letter).
     */
    @Test
    fun `no raw tween or spring or cubicBezier literals outside ui-theme`() {
        val moduleRoot = File(System.getProperty("user.dir"))
        val uiRoot = File(moduleRoot, "src/main/kotlin/com/poyka/ripdpi/ui")
        check(uiRoot.isDirectory) { "ui root not found: $uiRoot" }

        // Negative lookbehind: don't match `.tween(`, `xyzTween(`, etc.
        // infiniteRepeatable() is intentionally NOT in this list — it is a
        // *container* that wraps a curve; the curve itself is what must come
        // from a named motion helper.
        val sentinels =
            listOf(
                Regex("""(?<![A-Za-z.])tween\s*\("""),
                Regex("""(?<![A-Za-z.])spring\s*\("""),
                Regex("""(?<![A-Za-z.])cubicBezier\s*\("""),
                Regex("""(?<![A-Za-z.])CubicBezierEasing\s*\("""),
            )

        val themeDir = File(uiRoot, "theme").canonicalFile
        val offenders = mutableListOf<String>()

        uiRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.canonicalFile.startsWith(themeDir) }
            .forEach { file ->
                file.useLines { lines ->
                    lines.forEachIndexed { lineIndex, raw ->
                        // Skip comments to reduce false positives.
                        val trimmed = raw.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                        sentinels.firstOrNull { it.containsMatchIn(raw) }?.let { hit ->
                            val rel = file.relativeTo(moduleRoot).path
                            offenders += "$rel:${lineIndex + 1}: ${raw.trim()}  (matched: ${hit.pattern})"
                        }
                    }
                }
            }

        assertTrue(
            buildString {
                appendLine(
                    "Raw motion literals found outside ui/theme/ — promote them to a " +
                        "named RipDpiMotion helper (stateTween, motionAwareSpring, shimmerSpec, " +
                        "pulseSpec, pageEnterSpec, toastEnterSpec, etc.):",
                )
                offenders.forEach { appendLine("  $it") }
            },
            offenders.isEmpty(),
        )
    }

    /**
     * Enforces the RDS rule: literal `Color(0x...)` hex constants MAY
     * appear ONLY inside `ui/theme/`. Component / screen code must
     * consume `RipDpiThemeTokens.colors.*` so the contrast tests + dark-
     * mode token mappings stay centralized. Strict `.dp` literal check
     * is intentionally NOT enforced — many spec-mandated component
     * dimensions are correctly inlined per the component's own size
     * token.
     */
    @Test
    fun `no raw Color hex literals outside ui-theme`() {
        val moduleRoot = File(System.getProperty("user.dir"))
        val uiRoot = File(moduleRoot, "src/main/kotlin/com/poyka/ripdpi/ui")
        check(uiRoot.isDirectory) { "ui root not found: $uiRoot" }
        val sentinel = Regex("""Color\s*\(\s*0[xX][0-9a-fA-F]+""")
        val themeDir = File(uiRoot, "theme").canonicalFile
        val offenders = mutableListOf<String>()
        uiRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.canonicalFile.startsWith(themeDir) }
            .forEach { file ->
                file.useLines { lines ->
                    lines.forEachIndexed { lineIndex, raw ->
                        val trimmed = raw.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                        if (sentinel.containsMatchIn(raw)) {
                            offenders +=
                                "${file.relativeTo(moduleRoot).path}:${lineIndex + 1}: ${raw.trim()}"
                        }
                    }
                }
            }
        assertTrue(
            buildString {
                appendLine(
                    "Raw `Color(0x...)` hex literals found outside ui/theme/ — every " +
                        "color must come from RipDpiThemeTokens.colors.*:",
                )
                offenders.forEach { appendLine("  $it") }
            },
            offenders.isEmpty(),
        )
    }

    /**
     * Enforces the RDS rule: components consume the design-system
     * extended-color tokens (`RipDpiThemeTokens.colors.*`), not
     * Material 3 `colorScheme.*` directly. Bypassing the extended
     * palette breaks the brand contract and the contrast tests in
     * `RipDpiColorContrastTest`.
     */
    @Test
    fun `no direct MaterialTheme colorScheme reads outside ui-theme`() {
        val moduleRoot = File(System.getProperty("user.dir"))
        val uiRoot = File(moduleRoot, "src/main/kotlin/com/poyka/ripdpi/ui")
        check(uiRoot.isDirectory) { "ui root not found: $uiRoot" }
        // Match `MaterialTheme.colorScheme.<anything>` outside theme files.
        // Allow `MaterialTheme(colorScheme = …)` (constructor call) by anchoring
        // on the `.colorScheme.` field-access form.
        val sentinel = Regex("""MaterialTheme\s*\.\s*colorScheme\s*\.""")
        val themeDir = File(uiRoot, "theme").canonicalFile
        val offenders = mutableListOf<String>()
        uiRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.canonicalFile.startsWith(themeDir) }
            .forEach { file ->
                file.useLines { lines ->
                    lines.forEachIndexed { lineIndex, raw ->
                        val trimmed = raw.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                        if (sentinel.containsMatchIn(raw)) {
                            offenders +=
                                "${file.relativeTo(moduleRoot).path}:${lineIndex + 1}: ${raw.trim()}"
                        }
                    }
                }
            }
        assertTrue(
            buildString {
                appendLine(
                    "Direct `MaterialTheme.colorScheme.*` reads found outside ui/theme/ — " +
                        "components must read from RipDpiThemeTokens.colors.*:",
                )
                offenders.forEach { appendLine("  $it") }
            },
            offenders.isEmpty(),
        )
    }
}
