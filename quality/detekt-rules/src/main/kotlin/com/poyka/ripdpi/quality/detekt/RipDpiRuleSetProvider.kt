package com.poyka.ripdpi.quality.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class RipDpiRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = RULE_SET_ID

    override fun instance(config: Config): RuleSet =
        RuleSet(
            ruleSetId,
            listOf(
                InjectConstructorDefaultParameter(config),
                HiltViewModelApplicationContext(config),
                DisallowNewSuppression(config),
            ),
        )

    private companion object {
        const val RULE_SET_ID = "diGuardrails"
    }
}
