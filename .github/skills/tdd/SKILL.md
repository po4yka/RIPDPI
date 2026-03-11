---
name: tdd
description: Use when implementing features, fixing bugs, refactoring code, or adding tests. Enforces test-first red-green-refactor discipline with project-specific patterns.
---

# Test-Driven Development

This skill is **RIGID**. Follow every step exactly. Do not skip RED. Do not write implementation before a failing test exists.

Full test stack documentation: `docs/testing.md`. Do not duplicate it here -- read it when you need runner details, CI lanes, or fixture locations.

## Workflow

Repeat this cycle for every behavior change:

1. **RED** -- Write exactly one failing test. Run it. Confirm it fails for the expected reason.
2. **GREEN** -- Write the minimum implementation to make the test pass. Run the test again.
3. **REFACTOR** -- Clean up test and implementation. Run tests to confirm nothing broke.
4. **LINT** -- Run `./gradlew staticAnalysis` before considering the cycle complete.

Never batch multiple behaviors into one cycle. One test, one behavior, one cycle.

## Running Tests

### Kotlin/JVM (fast iteration)

```bash
# Single test method
./gradlew :core:engine:testDebugUnitTest --tests "ClassName.method name"

# Single test class
./gradlew :core:engine:testDebugUnitTest --tests "ClassName"

# Single module
./gradlew :core:engine:testDebugUnitTest
```

Replace `:core:engine` with the target module (`:core:service`, `:core:data`, `:core:diagnostics`).

### Rust

```bash
# Single test
cargo nextest run -p crate_name test_name

# Single crate
cargo nextest run -p crate_name

# Full workspace
cargo nextest run --workspace
```

### Golden contracts

Golden tests are read-only by default. If your change intentionally alters a contract:

```bash
# Bless all golden fixtures
bash scripts/tests/bless-telemetry-goldens.sh

# Manual single-suite bless
RIPDPI_BLESS_GOLDENS=1 ./gradlew :core:engine:testDebugUnitTest
RIPDPI_BLESS_GOLDENS=1 cargo test -p crate_name
```

Always review blessed diffs before committing. Golden changes require explanation in the commit message.

## Test Double Conventions

### Fake* classes (no mocking frameworks)

All test doubles are hand-written Fakes in `core/engine/src/test/java/com/poyka/ripdpi/core/TestDoubles.kt`. The project does not use MockK, Mockito, or any mocking library.

Pattern:
- Name: `Fake` + interface name (e.g., `FakeRipDpiProxyRuntime`)
- Track call counts and last arguments as public properties
- Return configurable values set before the test runs

### FaultQueue for fault injection

Fault injection uses `FaultQueue<T>` from `core/engine/src/main/java/com/poyka/ripdpi/core/testing/FaultModel.kt`.

Key types:
- `FaultQueue<T>` -- ordered queue of faults matched by target enum
- `FaultSpec<T>` -- target + outcome + scope + optional message/payload
- `FaultScope.ONE_SHOT` -- fires once then is consumed
- `FaultScope.PERSISTENT` -- fires on every matching call until cleared
- `FaultOutcome` -- `EXCEPTION`, `TIMEOUT`, `DROP`, `RESET`, `MALFORMED_PAYLOAD`, `BLANK_PAYLOAD`, `PANIC`

Usage in tests:

```kotlin
val bindings = FakeRipDpiProxyBindings()
bindings.faults.enqueue(
    FaultSpec(
        target = ProxyBindingFaultTarget.START,
        outcome = FaultOutcome.EXCEPTION,
        message = "simulated native crash",
    )
)
```

## Test Organization by Layer

| What you test | Location | Runner |
|--------------|----------|--------|
| Kotlin business logic | `core/*/src/test/` | `./gradlew :core:*:testDebugUnitTest` |
| Rust native logic | `native/rust/crates/*/tests/` | `cargo nextest run -p crate` |
| JNI integration | `app/src/androidTest/.../integration/` | `connectedDebugAndroidTest` |
| Network E2E | `app/src/androidTest/.../e2e/` | `connectedDebugAndroidTest` |
| Rust network E2E | `native/rust/crates/*/tests/` | `bash scripts/ci/run-rust-network-e2e.sh` |

## Subagent Strategy

For non-trivial features, use context isolation:

1. **Subagent writes the test** -- Launch an `Explore` or `general-purpose` agent to write the failing test. This keeps test design independent of implementation bias.
2. **Main context implements** -- Read the test the subagent wrote, then implement the minimum code to pass it.
3. **Main context refactors** -- Clean up both test and implementation in the same context.

This prevents the "write test and implementation together" anti-pattern.

## Rules

1. **Never skip RED.** Every test must fail before you write implementation.
2. **One test at a time.** Do not write the next test until the current cycle is complete.
3. **Run staticAnalysis before commit.** `./gradlew staticAnalysis` covers detekt, ktlint, and Android lint. It applies to test code too.
4. **Test and implementation in one commit.** Never commit a test without its implementation or vice versa.
5. **Fake* doubles only.** No mocking frameworks. Add new Fakes to `TestDoubles.kt`.
6. **FaultQueue for error paths.** Use `FaultSpec` + `FaultQueue`, not ad-hoc exception throwing.
7. **Golden contracts are read-only by default.** Bless intentionally, review the diff, explain in the commit message.
8. **Use backtick names in Kotlin tests.** `@Test fun `proxy start propagates native exception`()`.
9. **Use snake_case names in Rust tests.** `fn proxy_start_propagates_native_exception()`.
10. **Prefer unit tests.** Only escalate to integration/E2E when the behavior requires real Android or network components.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Using MockK or Mockito | Write a Fake* class in `TestDoubles.kt` |
| Skipping the RED step | Run the test first. If it passes, your test is wrong. |
| Multiple assertions per cycle | Split into separate test methods, one behavior each |
| `runBlocking` in coroutine tests | Use `runTest` from `kotlinx-coroutines-test` |
| Blessing goldens without review | Run `git diff` on fixture files before committing |
| Testing private internals | Test through the public API of the class under test |
| Putting helpers in test classes | Add shared helpers to `TestDoubles.kt` |
| `FaultScope.PERSISTENT` when `ONE_SHOT` suffices | Default to `ONE_SHOT`; use `PERSISTENT` only for repeated-call scenarios |

For deeper coverage of anti-patterns, see `references/testing-anti-patterns.md`.
