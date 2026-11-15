## Context

Task ID: `DGN-1794732375813278`. See proposal.md and diagnostics/doh-json-survey requirements. Six independent fixes need separate implementation commits.

## Goals / Non-Goals

- Goal: report only DNS answers for the selected query and retain response/error/unknown meaning.
- Non-goal: add DNS protocols, change providers, activate root, change app UI or run external/device probes.

## Decisions

- F1: pass expected name/type to the shared JSON parser; require matching Question and valid records; follow a bounded CNAME chain with case/trailing-dot equivalence. Reject truncation and malformed relevant records.
- F2: replace ambiguous Option with a typed Answer, NoData, NxDomain or DnsError result. Propagate it to scheduled and library callers. Negative evidence alone is inconclusive.
- F3: preserve typed I/O kind/stage through the JSON HTTP helper, retaining general HTTP String adapters. Classify socket timeout at the I/O boundary; do not match localized OS text.
- F4: reuse effective bucket counts with failure > attention > inconclusive > healthy priority; no observations means inconclusive. Preserve healthy contextual variants only for established healthy evidence.
- F5: use conservative library classification for transport/HTTP failures.
- F6: reuse existing URL tools or a bounded shared query builder; preserve query parameters and encoded name. Reject unsupported endpoint forms before requests.

## Contracts and ownership

- Parser worker owns diagnostics-contracts helper and its diagnostics-probes/monitor-engine callers for F1 then F2.
- HTTP worker owns diagnostics-http and the scheduled transport classification for F3; integrate after parser commits.
- Analytics worker owns report/summary.rs and the existing engine summary tests for F4.
- Coordinator owns task/OpenSpec, generated API snapshots and combined gates. The library F5/F6 lane starts after F1/F2.
- No Kotlin/JNI/protobuf/wire/storage/configuration migration. No Cargo.lock, dependency versions, locale, baseline or golden-fixture changes. Regenerate Rust API snapshots with the owning tool.

## Risks / Trade-offs

- Strict schema rejects old incomplete unit fixtures: replace normal test envelopes with independently specified vendor-valid JSON.
- Internal public Rust signatures/status variants change: update all repository callers and generated API snapshots together.
- String status details gain correct DNS meanings; generic report/UI can retain them without a schema change.

## Migration Plan

No persisted migration. Revert the coherent implementation commits to roll back. Validate each regression red/green, then all affected crate tests, API snapshots, independent pr-reviewer and just ci-gates. Also run architecture health and locked cargo metadata before integration. External compatibility, device and deployment evidence are not required for this local repair and must not be inferred.
