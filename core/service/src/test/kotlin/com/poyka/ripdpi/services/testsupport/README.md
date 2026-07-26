## Orchestration Failure Harness

Service orchestration regressions reuse deterministic primitives:

- `TestServiceClock` for fake time.
- `ScriptedSupervisorExitSequence` for controlled runtime exits across repeated supervisor lifecycles.
- `HarnessStallGate` for blocking helper code until the test releases it.
- `OverlapTracker` for proving helper runtimes overlap without relying on timing races.
- `CorruptFileFixture` from `com.poyka.ripdpi.testsupport` for deterministic atomic-cache torn writes.

Prefer these helpers over ad hoc latches or bespoke runtime completion code when adding supervisor, helper-runtime, or protect-socket failure regressions.

Minimal supervisor example:

```kotlin
val scriptedExits = ScriptedSupervisorExitSequence(ScriptedSupervisorExit.Crash(19))
supervisor.start(config) { cause ->
    observedExits += cause
    supervisor.detach()
}
scriptedExits.applyTo(factory.lastRuntime)
runCurrent()
advanceUntilIdle()
```

Minimal stall example (inside `VpnProtectSocketServerTest`, which defines the private fake session used below):

```kotlin
val gate = HarnessStallGate()
val stalledSession = FakeProtectSocketClientSession(beforeRead = gate::stall)
assertTrue(server.dispatchClientSession(stalledSession))
assertTrue(gate.awaitEntered())
gate.release()
```
