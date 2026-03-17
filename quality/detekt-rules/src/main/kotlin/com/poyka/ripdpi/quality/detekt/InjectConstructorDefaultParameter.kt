package com.poyka.ripdpi.quality.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtParameter

class InjectConstructorDefaultParameter(config: Config) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = "InjectConstructorDefaultParameter",
            severity = Severity.Defect,
            description = "@Inject constructors must not declare default parameter values.",
            debt = Debt.FIVE_MINS,
        )

    override fun visitPrimaryConstructor(constructor: KtPrimaryConstructor) {
        super.visitPrimaryConstructor(constructor)
        reportDefaultParameters(constructor)
    }

    override fun visitSecondaryConstructor(constructor: KtSecondaryConstructor) {
        super.visitSecondaryConstructor(constructor)
        reportDefaultParameters(constructor)
    }

    private fun reportDefaultParameters(constructor: KtConstructor<*>) {
        if (!constructor.hasAnnotation("Inject")) return

        constructor.valueParameters
            .filter(KtParameter::hasDefaultValue)
            .forEach { parameter ->
                report(
                    CodeSmell(
                        issue = issue,
                        entity = Entity.from(parameter.defaultValue ?: parameter),
                        message =
                            "Remove the default value from this @Inject constructor parameter. " +
                                "Bind explicitly or move convenience defaults to a non-@Inject constructor or factory.",
                    ),
                )
            }
    }
}
