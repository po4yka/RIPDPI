## Context

The strict app import guard rejects the complete com.poyka.ripdpi.core package. The native probe DTOs already live in core:engine-api, but they remain below the service facade. Current CI at 44aab032e is green while its gradle-static-analysis job is skipped because that commit changes only documentation.

## Goals / Non-Goals

- Goal: repair the observer API boundary and observe local and hosted verification.
- Non-goal: change SSH cryptography, socket ownership, native API/JNI, trust UI, or quality rules.

## Decisions

- Add the facade observation result and failure enum beside SshHostKeyObserver. Do not re-export native types through aliases.
- Convert once after the existing owned runner returns. Keep native results within the observer implementation and runner; map failures with exhaustive when branches.
- Use existing Robolectric observer fixture to test actual public results from native statuses and service-generated failures. Existing editor tests cover trust confirmation and stale/cancelled requests.
- Keep the boundary task, dependencies, baselines, and golden fixtures unchanged.

## Contracts and ownership

- The primary agent is the only writer and committer on main, as explicitly requested by the user.
- Own core/service SshHostKeyObserver.kt and DefaultSshHostKeyObserver.kt, their tests and test doubles, app SSH UI and unit test imports, and the observer assertion in SshHostKeyProbeInstrumentedTest.kt.
- An independent reviewer is read-only. No parallel production writers.
- The in-process Kotlin observer return type changes; all callers are rebuilt together. No external wire or persistence contract changes.

## Risks / Trade-offs

- Incorrect failure conversion could change UI messages: cover every native status and service-generated failure and retain exhaustive source mapping.
- Cancellation and protection must not regress: keep the owned runner and native path unchanged and run their existing tests.
- Documentation-only CI may skip analysis: inspect exact implementation revision jobs rather than accepting only the summary badge.
- The first source CI run found a stale invocation of the deleted Maestro/Appium selector suite. Use the current ADB driver tests in that step and reject missing workflow test modules with a repository-wide regression. Preserve all preflight dependencies and success conditions.

## Migration Plan

No persisted data migration. Rebuild service, app, and instrumentation source consumers together. Roll back the scoped implementation commit if needed. Gates are verifyAppEngineBoundary, unfiltered app and service unit tests, full staticAnalysis, architecture checks, independent review, and exact-revision hosted CI/CodeQL. Physical-device acceptance and release artifacts are outside this boundary-only repair.
