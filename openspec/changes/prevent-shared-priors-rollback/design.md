## Context

The signed manifest binds the payload hash and `issued_at_unix`, but `ripdpi-shared-priors` currently replaces its process registry without checking the preceding release. Android stores only refresh time and an HTTP header. The production release key remains unset, so generated test keys verify the new policy until release activation.

## Goals / Non-Goals

- Goal: Reject signed replay across process restarts and keep registry publication after durable release ordering.
- Non-goal: Recover the prior registry contents after restart or protect against an attacker who can delete app-private files.

## Decisions

- Require a marker path in native global-apply APIs. Android passes one fixed path under `noBackupFilesDir`. A path-free global apply would leave a rollback bypass.
- Store a versioned JSON marker with the signed timestamp and SHA-256 payload hash. A newer timestamp advances it; equal timestamp plus equal hash is idempotent; equal timestamp plus different hash is rejected.
- Verify and parse the bundle before taking the registry write lock. Under that lock, read and compare the marker, write it through a temporary file with file and parent-directory sync, then replace both registry maps. Any failure before replacement leaves both maps unchanged.
- A missing marker bootstraps an existing installation. Invalid marker content fails closed. The worker updates its HTTP refresh cache only on a native success status.

## Contracts and ownership

- `ripdpi-shared-priors` owns marker schema v1 and release comparison; its global-apply methods gain a `&Path` argument. `apply_priors` remains a pure verification and parse API.
- `ripdpi-android-platform-adapter` and `ripdpi-android` pass the path through JNI; `core/engine` exposes it to `core/service`.
- `core/service` owns the stable app-private file location and refresh-cache success decision.
- One writer owns this task's Rust registry, JNI bridge, Kotlin worker, and marker contract. No new dependency or backend is needed.

## Risks / Trade-offs

- A crash after marker sync but before registry replacement leaves the registry empty or unchanged in that process. Reapplying the same bundle is allowed and restores it.
- A corrupt marker blocks all new bundles until repaired by a trusted local action. Silent reset would permit rollback.
- A source rollback that omits this check reopens replay risk. Keep the marker and validation path in any maintenance release.

## Migration Plan

No marker exists on earlier installs. The first valid signed bundle creates it before publication. The worker uses `noBackupFilesDir`; existing refresh preferences remain unchanged. Validate Rust rollback, equal-version, restart, and I/O paths; JNI signature and host compilation; affected Kotlin unit tests; and repository architecture/task checks. Report device verification separately if unavailable.
