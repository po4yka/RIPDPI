package com.poyka.ripdpi.quality.detekt

import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtAnnotationEntry

internal fun KtAnnotated.hasAnnotation(simpleName: String): Boolean =
    annotationEntries.any { it.matchesSimpleName(simpleName) }

internal fun KtAnnotated.findAnnotation(simpleName: String): KtAnnotationEntry? =
    annotationEntries.firstOrNull { it.matchesSimpleName(simpleName) }

private fun KtAnnotationEntry.matchesSimpleName(simpleName: String): Boolean =
    shortName?.asString() == simpleName
