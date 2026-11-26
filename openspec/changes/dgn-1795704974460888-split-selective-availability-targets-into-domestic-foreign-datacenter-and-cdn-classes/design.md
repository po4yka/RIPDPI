## Context

Portfolio task `DGN-1795704974460888` owns this change. Freeze diagnostics can show whether connection_freeze results cluster by destination class and by destination AS, because the selective-availability targets carry a class.

## Goals / Non-Goals

- Goal: deliver `Split selective-availability targets into domestic, foreign datacenter and CDN classes` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Filed at proposal level. The target list needs hosts in each class that the project may probe; choosing them is design work and uses no private endpoints.
- The destination-AS claim stays a hypothesis until a report set shows it. The task must make the claim testable, not assume it.
- Class and destination AS come together or not at all. A class-less target stays valid and reports as unattributed, so a saved or overridden catalog cannot fail every scan. The profile version is 2 so the importer replaces saved v1 profiles.
- AS numbers reject 0, AS_TRANS (23456), documentation, private and reserved ranges (64496 to 131071, 4200000000 and above) on both the native and Kotlin sides. The build-logic generator repeats the class set and these ranges because it cannot depend on :core:diagnostics.
- Only a stalled or deadline-cut read is a freeze. A peer reset (`connection_reset`) and a clean close (`early_eof`) have their own report buckets, and a wrong status, length or hash is a server response. Results without a class group under `user_host`, `legacy_no_class` or `volume_unattributed`.
- A matrix target compares HTTP framing only. The exact-byte and SHA-256 comparison is the verified-download path.

## Risks / Trade-offs

- Incomplete evidence could create a false close. The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
