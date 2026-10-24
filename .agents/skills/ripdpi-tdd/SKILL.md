---
name: ripdpi-tdd
description: "Red-green-refactor workflow for RIPDPI plus test-double and FaultQueue conventions. Use when the user asks for test-first work or a defect needs a reproducing test. Not for Kotlin test syntax (use kotlin-test-patterns)."
---

# Test-Driven Development

Full test stack documentation: `docs/testing.md`. Do not duplicate it here -- read it when you need runner details, CI lanes, or fixture locations. The test rule and the verification policy in `AGENTS.md` take precedence over this skill.

## When to use the cycle

Use red-green-refactor when the user asks for test-first work, or when a defect or new behavior is not caught by an existing test. Search the owning module's tests first: if an existing test covers the behavior, extend it or rely on it instead of adding one. Refactors, renames, and wiring changes need no new test; the existing tests and the module gate prove them.

## Workflow

1. **RED** -- Write one test for one behavior and run only that test. Confirm it fails for the expected reason. If it passes, the behavior is already covered or the test is wrong.
2. **GREEN** -- Write the minimum implementation that makes it pass. Run the same test again.
3. **REFACTOR** -- Clean up the test and the implementation, then run the owning module's tests once.

Run the gate that `AGENTS.md` assigns to the change once, after the last cycle of a batch, not after every cycle.

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

Replace `:core:engine` with the target module (`:core:service`, `:core:data`, `:core:diagnostics`). `:app` tasks are flavor-qualified (`:app:testGithubFullDebugUnitTest`).

### Rust

```bash
# Single test
cargo nextest run --locked -p crate_name test_name

# Single crate
cargo nextest run --locked -p crate_name
```

### Golden contracts

Golden tests are read-only by default. Blessing needs explicit user approval for the affected fixture family; follow `golden-bless-discipline.md` and the `golden-test-management` skill.

## Test Double Conventions

### Fake* classes (no mocking frameworks)

The project uses hand-written Fakes and no MockK, Mockito, or other mocking library. Reuse an existing Fake from the owning module's tests before writing one; shared `:core:engine` doubles live in `core/engine/src/test/kotlin/com/poyka/ripdpi/core/TestDoubles.kt`.

- Name: `Fake` + interface name (e.g., `FakeRipDpiProxyRuntime`)
- Return configurable values set before the test runs
- Assert the observable result of the code under test. Assert a recorded call only when the call itself is the behavior (for example, that a native stop was requested)

### FaultQueue for fault injection

Fault injection uses `FaultQueue<T>` from `core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/testing/FaultModel.kt`.

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
| Rust native logic | `native/rust/crates/*/tests/` | `cargo nextest run --locked -p crate` |
| JNI integration | `app/src/androidTest/.../integration/` | `connectedGithubFullDebugAndroidTest` |
| Network E2E | `app/src/androidTest/.../e2e/` | `connectedGithubFullDebugAndroidTest` |
| Rust network E2E | `native/rust/crates/*/tests/` | `bash scripts/ci/run-rust-network-e2e.sh` |

## Rules

1. **Fake* doubles only.** No mocking frameworks.
2. **FaultQueue for error paths.** Use `FaultSpec` + `FaultQueue`, not ad-hoc exception throwing.
3. **Use backtick names in Kotlin tests.** `@Test fun `proxy start propagates native exception`()`.
4. **Use snake_case names in Rust tests.** `fn proxy_start_propagates_native_exception()`.
5. **Test and implementation land in one commit.** Never commit a test without its implementation or vice versa.
6. **Prefer unit tests.** Escalate to integration or E2E only when the behavior needs real Android or network components.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Using MockK or Mockito | Write or reuse a Fake* class |
| Adding a test an existing test already covers | Extend or rely on the existing test |
| Asserting only a Fake's recorded calls | Assert the observable result |
| `runBlocking` in coroutine tests | Use `runTest` from `kotlinx-coroutines-test` |
| Testing private internals | Test through the public API of the class under test |
| `FaultScope.PERSISTENT` when `ONE_SHOT` suffices | Default to `ONE_SHOT`; use `PERSISTENT` only for repeated-call scenarios |

For deeper coverage of anti-patterns, see `references/testing-anti-patterns.md`.
