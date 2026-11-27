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
- Volume targets are user-supplied hosts, so no catalog class applies to them. The user may declare a class and a destination AS in `TransferVolumeComparisonInput`; both are present or both absent, and they pass the same class set and AS range checks as catalog targets on the Kotlin side and again in native request validation (only the two volume legs may carry them, and both legs must carry the same pair). The runner copies the declared pair into the leg details next to `transferVolumeLeg`. The values are user-declared, not observed, so a volume leg that carries them groups under `volume_declared` and stays apart from the `catalog` rows with the same class and AS. A volume leg without them stays `volume_unattributed`. The earlier path that matched a catalog target to a volume leg had no caller and is removed.
- A matrix target compares HTTP framing only. The exact-byte and SHA-256 comparison is the verified-download path.

## Risks / Trade-offs

- Incomplete evidence could create a false close. The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
