## Context

Deploy owns the canonical bundle schema; RIPDPI vendors it byte-identically and
implements current AWG semantics in a native runtime. Import, storage, and
activation currently lack a first-class wire revision.

## Goals / Non-Goals

- Goal: make revision identity explicit and fail closed end to end.
- Goal: preserve the current revision and stage a later revision safely.
- Non-goal: infer compatibility, auto-upgrade profiles, or promote production defaults.

## Decisions

- Vendor the canonical deploy contract and require explicit revision/provenance
  for new bundles; migrate legacy stored profiles only to the sole historically supported revision.
- Include revision in normalized fingerprint and persistence/export contracts.
- Select native codec/runtime through a closed revision enum and return typed
  refusal for unknown values; no fallback branch is retained.
- Gate later revisions through repository feature metadata plus upstream-pinned,
  cross-stack, and physical-device acceptance.

## Contracts and ownership

- Data owns parsing, migration, persistence, backup, and export.
- Native runtime owns revision-specific semantics and conformance fixtures.
- Service owns activation refusal; UI owns safe typed explanation.
- Deploy remains authoritative for canonical schema and emitted provenance.

## Risks / Trade-offs

- Legacy migration can assign the wrong revision if multiple historical lines existed;
  limit migration to the single revision RIPDPI actually shipped and test it.
- Later-revision code increases audit surface; isolate semantics behind a closed enum and conformance suite.
- Device evidence can be unavailable; remain staging-only rather than weaken acceptance.

## Migration Plan

Land deploy contract first, vendor it, migrate current profiles, and prove current
equivalence. Add later-revision code behind staging metadata, then collect
cross-stack and arm64 evidence. Rollback removes staging eligibility while the
explicit current-revision contract remains.
