package com.poyka.ripdpi.quality.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFileAnnotationList

class DisallowNewSuppression(
    config: Config,
) : Rule(config) {
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
        val effectiveElement = getEffectiveElement(annotationEntry)
        return hasPrecedingSiblingComment(effectiveElement) ||
            hasFollowingSiblingCommentOnSameLine(effectiveElement) ||
            hasPrecedingLineComment(effectiveElement)
    }

    private fun getEffectiveElement(annotationEntry: KtAnnotationEntry): PsiElement {
        val parent = annotationEntry.parent
        return if (parent is KtFileAnnotationList) parent else annotationEntry
    }

    private fun hasPrecedingSiblingComment(element: PsiElement): Boolean {
        var sibling = element.prevSibling
        while (sibling != null) {
            when (sibling) {
                is PsiComment -> {
                    if (ROADMAP_PATTERN.containsMatchIn(sibling.text)) return true
                    sibling = sibling.prevSibling
                }

                is PsiWhiteSpace -> {
                    sibling = sibling.prevSibling
                }

                else -> {
                    break
                }
            }
        }
        return false
    }

    private fun hasFollowingSiblingCommentOnSameLine(element: PsiElement): Boolean {
        var sibling = element.nextSibling
        while (sibling != null) {
            when (sibling) {
                is PsiComment -> {
                    if (ROADMAP_PATTERN.containsMatchIn(sibling.text)) return true
                    sibling = sibling.nextSibling
                }

                is PsiWhiteSpace -> {
                    if (sibling.text.contains('\n')) return false
                    sibling = sibling.nextSibling
                }

                else -> {
                    break
                }
            }
        }
        return false
    }

    private fun hasPrecedingLineComment(element: PsiElement): Boolean {
        val file = element.containingFile ?: return false
        val text = file.text ?: return false
        val offset = element.textOffset

        val lineStart = text.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val annotationLine = text.substring(lineStart, lineEnd)

        if (ROADMAP_PATTERN.containsMatchIn(annotationLine)) return true

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
