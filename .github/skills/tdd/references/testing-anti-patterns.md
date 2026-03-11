# Testing Anti-Patterns

Extended reference for the TDD skill. Load this when diagnosing test quality issues.

## Test Double Anti-Patterns

**No mocking libraries.** The project uses hand-written Fakes exclusively. MockK and Mockito add implicit behavior, make tests brittle to refactoring, and hide design problems.

**Fresh Fake per test.** Never share mutable Fake state across tests. Create a new instance in each test method or in a `@BeforeTest` setup.

**Test public API, not internals.** If you need to reach into private fields or use reflection to test something, the design needs to change -- not the test.

**FaultScope.ONE_SHOT vs PERSISTENT.** Default to `ONE_SHOT` -- it fires once and is consumed. Use `PERSISTENT` only when the test exercises repeated calls to the same target (e.g., polling loops). Forgetting to clear a `PERSISTENT` fault causes mysterious failures in subsequent test methods if Fake instances are accidentally shared.

**FaultOutcome selection.** Match the outcome to the real failure mode:
- `EXCEPTION` / `TIMEOUT` / `DROP` / `RESET` -- for I/O and network faults
- `MALFORMED_PAYLOAD` / `BLANK_PAYLOAD` -- for corrupt or empty native responses (non-throwing)
- `PANIC` -- for simulated native panics that cross the JNI boundary

## Coroutine Testing Anti-Patterns

**Use `runTest`, not `runBlocking`.** `runTest` from `kotlinx-coroutines-test` auto-advances virtual time and fails on uncaught exceptions. `runBlocking` blocks real threads and masks timing bugs.

**Async start + blocker pattern.** When testing code that calls `start()` on a blocking native operation, use the `startedSignal` / `startBlocker` pattern from existing Fakes:

```kotlin
val bindings = FakeRipDpiProxyBindings()
bindings.startedSignal = CompletableDeferred()
bindings.startBlocker = CompletableDeferred()

// Launch the operation under test
val job = launch { runtime.start(preferences) }

// Wait for native start to be called
bindings.startedSignal!!.await()

// Assert intermediate state here

// Unblock native start
bindings.startBlocker!!.complete(Unit)
job.join()
```

**Harness cleanup.** Always cancel coroutine scopes and clear FaultQueues in `@AfterTest`. Leaked coroutines cause flaky tests and misleading stack traces.

## Golden Contract Anti-Patterns

**Scrub volatile fields.** Timestamps, session IDs, loopback ports, temp paths, and archive filenames must be scrubbed or replaced with deterministic placeholders before comparison. See `docs/testing.md` for the full list of scrubbed vs. strict fields.

**Review before blessing.** Never run `RIPDPI_BLESS_GOLDENS=1` and commit without inspecting the diff. Golden contracts are compatibility boundaries -- accidental changes break downstream consumers.

**Correct fixture directory.** Match the test layer:
- Rust: `tests/golden/` within the crate
- JVM: `src/test/resources/golden/` within the module
- Android instrumentation: `app/src/androidTest/assets/golden/`

Putting fixtures in the wrong directory causes silent test skips or misleading passes.

## Rust Testing Anti-Patterns

**TEST_LOCK for shared state.** Rust integration tests that touch shared resources (network ports, file paths) must use a test mutex or serial test attribute. Parallel test execution causes port conflicts and flaky failures.

**Explicit runtime for fixture lifecycle.** When tests use the local-network-fixture binary, build and manage the Tokio runtime explicitly. Do not rely on `#[tokio::test]` defaults for tests that need fixture startup/teardown sequencing.

**Ephemeral ports.** Always bind to port 0 and read back the assigned port. Hard-coded ports cause CI failures when the port is already in use.

**Proptest regression files.** When `proptest` finds a failing case, it writes a regression file. Commit these files -- they prevent the same failure from being missed in future runs.

## Structural Anti-Patterns

**Helpers in TestDoubles.kt, not test classes.** Shared test utilities belong in `TestDoubles.kt` (or a dedicated test-support source set). Duplicating helper logic across test classes leads to drift and maintenance burden.

**No reflection.** If a test needs reflection to access private members, the production code needs a better seam (interface, factory, or package-visible method). Reflection-based tests break silently on rename refactors.

**Unit test when possible.** Integration and E2E tests are slower, flakier, and harder to debug. Only escalate when the behavior genuinely requires real Android components or network I/O. Most business logic can be tested with Fakes at the unit level.

**One assertion focus per test.** A test method should verify one behavior. Multiple unrelated assertions in a single test make failures ambiguous -- you cannot tell which behavior broke without reading the full stack trace.
