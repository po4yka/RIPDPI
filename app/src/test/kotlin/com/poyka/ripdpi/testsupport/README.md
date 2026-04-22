## Orchestration Failure Harness

`CorruptFileFixture` centralizes the deterministic torn-write path used by cache corruption regressions.

Use it when a repository test needs to verify that a failed atomic snapshot write preserves the previously cached state.

Keep other cache corruption cases aligned with this helper instead of embedding one-off `AtomicFile` failures in each test file.
