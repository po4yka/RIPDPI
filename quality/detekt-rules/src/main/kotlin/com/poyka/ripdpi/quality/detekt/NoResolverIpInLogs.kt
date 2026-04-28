package com.poyka.ripdpi.quality.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Prevents logging resolver/upstream IP addresses in log statements.
 *
 * Logging DNS resolver or upstream endpoint IPs leaks user-visible network
 * state into logs that may be accessible to third parties (crash reporters,
 * adb, bug reports). Use a non-identifying placeholder instead.
 *
 * Compliant:
 *   Logger.v { "DNS configured" }
 *   Logger.v { "DNS configured (${resolvers.size} resolvers)" }
 *
 * Non-compliant:
 *   Logger.v { "DNS: $dns" }
 *   Logger.d { "upstream: $resolverIp" }
 */
class NoResolverIpInLogs(
    config: Config = Config.empty,
) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = "NoResolverIpInLogs",
            severity = Severity.Defect,
            description =
                "Logging resolver/upstream IP can leak user-visible network state. " +
                    "Use a non-identifying placeholder.",
            debt = Debt.FIVE_MINS,
        )

    private val loggerNames = setOf("Logger", "Timber", "Log")

    private val forbiddenIdentifiers =
        setOf("dns", "resolver", "upstream", "dnsaddr", "dnsip", "serverip", "endpointip")

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        if (!isLogCall(expression)) return

        checkForForbiddenIdentifiers(expression)
    }

    private fun isLogCall(expression: KtCallExpression): Boolean {
        // For Logger.v { ... }, the PSI is:
        //   KtDotQualifiedExpression
        //     KtNameReferenceExpression("Logger")  ← receiver
        //     KtCallExpression
        //       KtNameReferenceExpression("v")     ← calleeExpression
        // So calleeExpression.text == "v", not "Logger.v".
        // We must walk up to the parent dot-qualified expression to get the receiver.
        val parent = expression.parent
        val receiverName =
            if (parent is KtDotQualifiedExpression) {
                (parent.receiverExpression as? KtNameReferenceExpression)?.getReferencedName()
            } else {
                null
            }
        // Also handle bare calls like `Log.v(...)` where the whole thing is top-level.
        val calleeText = expression.calleeExpression?.text ?: return false
        return (receiverName != null && loggerNames.contains(receiverName)) ||
            loggerNames.any { name -> calleeText == name || calleeText.startsWith("$name.") }
    }

    private fun checkForForbiddenIdentifiers(expr: KtElement) {
        PsiTreeUtil
            .findChildrenOfType(expr, KtStringTemplateExpression::class.java)
            .forEach { template ->
                template.entries.forEach { entry ->
                    val identifier =
                        when (entry) {
                            is KtSimpleNameStringTemplateEntry -> entry.expression?.text
                            is KtBlockStringTemplateEntry -> entry.expression?.text
                            else -> null
                        } ?: return@forEach

                    if (forbiddenIdentifiers.any { forbidden -> identifier.lowercase().contains(forbidden) }) {
                        report(
                            CodeSmell(
                                issue = issue,
                                entity = Entity.from(entry),
                                message =
                                    "Logging resolver/upstream IP can leak user-visible network state. " +
                                        "Use a non-identifying placeholder instead of '\$$identifier'.",
                            ),
                        )
                    }
                }
            }
    }
}
