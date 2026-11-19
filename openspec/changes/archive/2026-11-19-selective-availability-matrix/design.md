## Context

Task DGN-1791519134625558 extends the existing manual connectivity pipeline. The SNI probe and general Web runner do not measure cohort body availability.

## Goals / Non-Goals

- Goal: a complete bounded manual matrix with user targets, history and export.
- Non-goal: establish the operator's intent, add a backend, add H3/PMTU probes, or incorporate pending unrelated boundary changes.

## Decisions

- Add optional selectiveMatrix request/profile config with a SELECTIVE_AVAILABILITY task family. Generic ProbeResult details carry results; no Room migration is needed.
- Keep the engine version unchanged for this additive optional request field. Old profiles decode with no matrix config; the bundled app and native library ship together. New profiles only schedule the explicit matrix family.
- Config fields: version (1), catalogVersion, targets, repetitions (2), timeoutMs (5000), maxResponseBytes (65536). Target fields: id, label, url, cohort (declared_available/domestic/global/user), infrastructureGroup, sourceUrl, sourceDate. Optional lastVerifiedAt is null until a real successful check exists.
- Hard ceilings: 10 targets, 3 repeats, 64 KiB response body and 16 KiB headers per attempt, 120 seconds per scan. Validate the combined requested response budget at 2 MiB. User additions are limited to four public HTTPS hostnames, without credentials, query strings or fragments; input becomes a root HTTPS URL. A limited or non-2xx response never proves body availability.
- Interleave repeats across cohorts. Record one result per attempt with targetId, cohort, infrastructureGroup, attempt, catalogVersion, provenance, dnsStatus, tcpStatus, tlsStatus, httpStatus, bodyStatus, bodyByteCount, bodyComplete, failureStage, elapsedMs and HTTP status. Unmeasured stages remain not_run. Never store arbitrary payloads or headers.
- Rust owns aggregate observations, emitted as selective_availability_summary. Individual probeType is selective_availability. Aggregates distinguish matrix_available, matrix_selective, matrix_unavailable, matrix_mixed and matrix_inconclusive; they never claim an ISP policy. TLS certificate errors, HTTP rejection and incomplete bodies remain inconclusive for filtering. Detailed output tokens are mirrored in taxonomy tests.
- Kotlin reuses targetOverrides for transient selectiveMatrixHosts. Existing network scope finalization invalidates unsafe conclusions; matrix summary must respect the scope metadata. Background execution and policy auto-application are disabled.
- Matched public endpoints are observations with uncertain remote health. Catalog provenance is not health proof. A changed infrastructure group or server-side denial lowers coverage rather than proving filtering.
- All locale resource sets belong to one UI writer. Native wire files belong to one native writer. Root owns Kotlin wire contracts and build-logic. No dependency, baseline or lockfile changes.

## Contracts and ownership

See the portfolio Ownership section. Root integrates catalog generation, Kotlin contracts/planning/admission, summary export and task state. Native writer owns native sources and tests. UI writer owns app sources/tests/locales. Golden changes require separate authorization and specialist review.

## Risks / Trade-offs

- Public endpoint responses may change: record source age and response completion; never assume health.
- Non-root DNS/socket timeouts: use existing protected transport primitives and enforce bounded deadlines between and within attempts.
- URI and redirect confusion: validate public HTTPS hosts and do not follow redirects outside the declared target.
- Disk space is limited: serialize Gradle work, share the existing Cargo target cache, and do not delete unrelated outputs.

## Migration Plan

Existing profiles and stored reports remain readable. Publish a new bundled profile and optional request field. Rollback removes the new profile while old generic results remain viewable. Validate Kotlin/native contracts, native loopback failure scenarios, Kotlin request/summary roundtrips, UI tests, all locale lint, static analysis and architecture health. Record separate CI and device evidence.
