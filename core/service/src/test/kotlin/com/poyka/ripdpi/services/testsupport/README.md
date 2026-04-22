## Orchestration Failure Harness

Service orchestration regressions reuse four deterministic primitives:

- `TestServiceClock` for fake time.
- `ScriptedSupervisorExitSequence` for controlled runtime exits across repeated supervisor lifecycles.
- `HarnessStallGate` for blocking helper code until the test releases it.
- `OverlapTracker` for proving helper runtimes overlap without relying on timing races.

Prefer these helpers over ad hoc latches or bespoke runtime completion code when adding supervisor, helper-runtime, or protect-socket failure regressions.
