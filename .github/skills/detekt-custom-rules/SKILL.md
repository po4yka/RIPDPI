---
name: detekt-custom-rules
description: Use when writing custom detekt rules, modifying the DI guardrails rule set, configuring detekt.yml, or debugging false positives from static analysis. Triggers on: detekt rule, custom lint, static analysis rule, DI guardrail, Hilt check, code smell detection, detekt.yml, RuleSetProvider.
---

# Custom Detekt Rules

Custom rule set `diGuardrails` in module `:quality:detekt-rules`. Enforces DI and Hilt conventions at compile time.

## Existing Rules

| Rule | What It Catches | Why |
|------|-----------------|-----|
| `InjectConstructorDefaultParameter` | Default values on `@Inject` constructor params | Hilt ignores defaults, causing silent behavior mismatch |
| `HiltViewModelApplicationContext` | `@ApplicationContext` in `@HiltViewModel` constructors | Forces narrower abstractions, improves testability |

## Module Structure

```
quality/detekt-rules/
  src/main/kotlin/com/poyka/ripdpi/quality/detekt/
    RipDpiRuleSetProvider.kt           -- Registers all rules (id: "diGuardrails")
    AnnotationMatchers.kt              -- Shared helpers: hasAnnotation(), findAnnotation()
    InjectConstructorDefaultParameter.kt
    HiltViewModelApplicationContext.kt
  src/test/kotlin/com/poyka/ripdpi/quality/detekt/
    InjectConstructorDefaultParameterTest.kt
    HiltViewModelApplicationContextTest.kt
```

## Creating a New Rule

### Step 1: Write the Rule Class

Create `src/main/kotlin/com/poyka/ripdpi/quality/detekt/YourRuleName.kt`:

```kotlin
package com.poyka.ripdpi.quality.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtClass

class YourRuleName(config: Config) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = "YourRuleName",
            severity = Severity.Defect,
            description = "One-line description of what this catches.",
            debt = Debt.FIVE_MINS,
        )

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        // Use hasAnnotation() from AnnotationMatchers.kt
        if (!klass.hasAnnotation("SomeAnnotation")) return

        // Check condition and report
        report(
            CodeSmell(
                issue = issue,
                entity = Entity.from(klass),
                message = "Actionable message: what to do instead.",
            ),
        )
    }
}
```

### Step 2: Register in RuleSetProvider

Add to `RipDpiRuleSetProvider.kt`:

```kotlin
override fun instance(config: Config): RuleSet =
    RuleSet(
        ruleSetId,
        listOf(
            InjectConstructorDefaultParameter(config),
            HiltViewModelApplicationContext(config),
            YourRuleName(config),  // <-- add here
        ),
    )
```

### Step 3: Activate in detekt.yml

Add to `config/detekt/detekt.yml`:

```yaml
diGuardrails:
  active: true
  InjectConstructorDefaultParameter:
    active: true
  HiltViewModelApplicationContext:
    active: true
  YourRuleName:               # <-- add here
    active: true
```

### Step 4: Write Tests

Create `src/test/kotlin/com/poyka/ripdpi/quality/detekt/YourRuleNameTest.kt`:

```kotlin
package com.poyka.ripdpi.quality.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class YourRuleNameTest {
    private val rule = YourRuleName(Config.empty)

    @Test
    fun `reports violation on annotated class`() {
        val code = """
            @SomeAnnotation
            class Bad { /* violating pattern */ }
        """.trimIndent()
        val findings = rule.compileAndLint(code)
        assertEquals(1, findings.size)
    }

    @Test
    fun `allows non-annotated class`() {
        val code = """
            class Fine { /* same pattern but no annotation */ }
        """.trimIndent()
        val findings = rule.compileAndLint(code)
        assertEquals(0, findings.size)
    }
}
```

Cover: positive detection, negative (no false positive), edge cases (primary vs secondary constructors, nested classes).

## Annotation Matching

Use the shared helpers from `AnnotationMatchers.kt`:

```kotlin
// Check if a KtAnnotated element has an annotation by simple name
klass.hasAnnotation("HiltViewModel")        // matches @HiltViewModel
constructor.hasAnnotation("Inject")          // matches @Inject

// Find the annotation entry (for Entity.from targeting)
parameter.findAnnotation("ApplicationContext")  // returns KtAnnotationEntry?
```

**Important:** These match by **simple name**, not fully-qualified name. `hasAnnotation("Inject")` matches both `@javax.inject.Inject` and `@com.google.inject.Inject`.

## Common PSI Visitor Methods

| Method | When To Use |
|--------|-------------|
| `visitClass(KtClass)` | Checking class-level patterns |
| `visitPrimaryConstructor(KtPrimaryConstructor)` | Constructor parameter rules |
| `visitSecondaryConstructor(KtSecondaryConstructor)` | Must handle alongside primary |
| `visitNamedFunction(KtNamedFunction)` | Function-level rules |
| `visitProperty(KtProperty)` | Property declaration rules |

Use `getStrictParentOfType<KtClassOrObject>()` to navigate from a constructor to its owning class.

## Convention Plugin

`ripdpi.android.detekt.gradle.kts` configures detekt for all modules:

- **Parallel:** true
- **Config:** `config/detekt/detekt.yml`
- **Reports:** HTML + XML
- **JVM target:** 17
- **Excludes:** `**/build/**`, `**/generated/**`, `**/jni/**`, `**/cpp/**`
- **Baseline:** Auto-detected from `detekt-baseline.xml` if present in module
- **Plugin deps:** Custom rules (`:quality:detekt-rules`) + `detekt-compose-rules`

Run detekt:
```bash
./gradlew staticAnalysis          # detekt + ktlint + lint
./gradlew detektDebug             # detekt only (single variant)
```

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Forgetting to register in `RipDpiRuleSetProvider` | Rule exists but never runs. Add to the `listOf(...)` in `instance()`. |
| Using FQN for annotation matching | `hasAnnotation("javax.inject.Inject")` won't match. Use simple name: `"Inject"`. |
| Not activating in `detekt.yml` | Rule is registered but inactive. Add `YourRuleName: { active: true }` under `diGuardrails:`. |
| Testing only primary constructors | Many rules apply to both primary and secondary. Override both visitor methods. |
| Reporting on wrong entity | Use `Entity.from(specificElement)` not `Entity.from(constructor)` -- highlights the exact problem location. |
| Missing `super.visitX()` call | Always call `super.visitPrimaryConstructor(constructor)` etc. to continue traversal. |

## See Also

- `config/detekt/detekt.yml` -- Full detekt configuration
- `build-logic/convention/src/main/kotlin/ripdpi.android.detekt.gradle.kts` -- Convention plugin
