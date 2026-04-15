package com.poyka.ripdpi.quality.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFileAnnotationList
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace

class DisallowNewSuppression(config: Config) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = "DisallowNewSuppression",
            severity = Severity.Warning,
            description =
                "@Suppress / @file:Suppress must be accompanied by a " +
                    "// ROADMAP-architecture-refactor comment on the same or preceding line.",
            debt = Debt.TEN_MINS,
        )

    override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
        super.visitAnnotationEntry(annotationEntry)

        val shortName = annotationEntry.shortName?.asString() ?: return
        if (shortName != "Suppress") return

        if (hasRoadmapComment(annotationEntry)) return

        report(
            CodeSmell(
                issue = issue,
                entity = Entity.from(annotationEntry),
                message =
                    "Found @Suppress without a ROADMAP-architecture-refactor allowlist comment. " +
                        "Add `// ROADMAP-architecture-refactor <reason>` on the same or preceding line, " +
                        "or remove the suppression.",
            ),
        )
    }

    private fun hasRoadmapComment(annotationEntry: KtAnnotationEntry): Boolean {
        // Check for a comment on the same line as the annotation by scanning
        // preceding siblings at the parent level, then check the line before.
        val effectiveElement = getEffectiveElement(annotationEntry)
        return hasPrecedingSiblingComment(effectiveElement) ||
            hasFollowingSiblingCommentOnSameLine(effectiveElement) ||
            hasPrecedingLineComment(effectiveElement)
    }

    /**
     * For file-level annotations the annotation entry is wrapped in a KtFileAnnotationList.
     * We want to check siblings of that list, not siblings of the entry within it.
     */
    private fun getEffectiveElement(annotationEntry: KtAnnotationEntry): PsiElement {
        val parent = annotationEntry.parent
        return if (parent is KtFileAnnotationList) parent else annotationEntry
    }

    /**
     * Walk backwards through preceding siblings skipping whitespace.
     * If we find a comment before a non-whitespace/non-comment element, check it.
     */
    private fun hasPrecedingSiblingComment(element: PsiElement): Boolean {
        var sibling = element.prevSibling
        while (sibling != null) {
            when (sibling) {
                is PsiComment -> {
                    if (ROADMAP_PATTERN.containsMatchIn(sibling.text)) return true
                    // Keep going backwards — multiple comments may precede the annotation.
                    sibling = sibling.prevSibling
                }
                is PsiWhiteSpace -> sibling = sibling.prevSibling
                else -> break
            }
        }
        return false
    }

    /**
     * Walk forward through following siblings on the same line (no newline in whitespace).
     * If we find a line comment before a newline, check it.
     */
    private fun hasFollowingSiblingCommentOnSameLine(element: PsiElement): Boolean {
        var sibling = element.nextSibling
        while (sibling != null) {
            when (sibling) {
                is PsiComment -> {
                    if (ROADMAP_PATTERN.containsMatchIn(sibling.text)) return true
                    sibling = sibling.nextSibling
                }
                is PsiWhiteSpace -> {
                    // Stop if whitespace contains a newline — comment is on a different line
                    if (sibling.text.contains('\n')) return false
                    sibling = sibling.nextSibling
                }
                else -> break
            }
        }
        return false
    }

    /**
     * Fallback: check via raw document text. Find the line that contains the annotation
     * and the line immediately above it; look for ROADMAP_PATTERN in either.
     */
    private fun hasPrecedingLineComment(element: PsiElement): Boolean {
        val file = element.containingFile ?: return false
        val text = file.text ?: return false
        val offset = element.textOffset

        // Find start and end of the annotation's line
        val lineStart = text.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val annotationLine = text.substring(lineStart, lineEnd)

        // Check same line (full line including text after the annotation)
        if (ROADMAP_PATTERN.containsMatchIn(annotationLine)) return true

        // Check the line immediately above
        if (lineStart == 0) return false
        val prevLineEnd = lineStart - 1
        val prevLineStart = text.lastIndexOf('\n', prevLineEnd - 1).let { if (it < 0) 0 else it + 1 }
        val prevLine = text.substring(prevLineStart, prevLineEnd)

        return ROADMAP_PATTERN.containsMatchIn(prevLine)
    }

    private companion object {
        val ROADMAP_PATTERN = Regex("ROADMAP-architecture-refactor")
    }
}
