package com.poyka.ripdpi.quality.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

class HiltViewModelApplicationContext(config: Config) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = "HiltViewModelApplicationContext",
            severity = Severity.Defect,
            description = "@HiltViewModel constructors must not depend on @ApplicationContext.",
            debt = Debt.FIVE_MINS,
        )

    override fun visitPrimaryConstructor(constructor: KtPrimaryConstructor) {
        super.visitPrimaryConstructor(constructor)
        reportApplicationContextUsage(constructor)
    }

    override fun visitSecondaryConstructor(constructor: KtSecondaryConstructor) {
        super.visitSecondaryConstructor(constructor)
        reportApplicationContextUsage(constructor)
    }

    private fun reportApplicationContextUsage(constructor: KtConstructor<*>) {
        val owningClass = constructor.getStrictParentOfType<KtClassOrObject>() ?: return
        if (!owningClass.hasAnnotation("HiltViewModel")) return

        constructor.valueParameters.forEach { parameter ->
            val applicationContextAnnotation = parameter.findAnnotation("ApplicationContext") ?: return@forEach
            report(
                CodeSmell(
                    issue = issue,
                    entity = Entity.from(applicationContextAnnotation),
                    message =
                        "Do not inject @ApplicationContext into a @HiltViewModel constructor. " +
                            "Inject a narrower abstraction or a collaborator that owns the context usage.",
                ),
            )
        }
    }
}
