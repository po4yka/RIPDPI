## Context

The server uses SHA-256 over the fixed sequence jc,jmin,jmax,s1,s2,h1,h2,h3,h4,i1,i2,i3,i4,i5. Absent values are empty strings. The result is sha256 plus a colon and 64 lowercase hexadecimal digits. There is no truncation or case normalization. S3/S4 are excluded by the existing shared contract. INI has no cohort fingerprint field.

## Goals / Non-Goals

- Goal: Validate optional bundle fingerprints before mapping an AWG profile.
- Non-goal: Change the shared hash algorithm, detect whether a self-consistent bundle is stale against a live server, or add INI metadata.

## Decisions

- Compare a present string exactly with the existing computed fingerprint. Equality enforces format and content together. Reject null, numeric, object, blank, and whitespace-modified values.
- Reuse the typed skipped-node path with a fixed cohort_fingerprint detail. Keep valid siblings.
- Validate parsed parameters, which are the values used for activation. Do not report key or parameter values.

## Contracts and ownership

- Runtime-state parser and core data tests only. No Rust/JNI/protobuf/storage contract changes.
- Fingerprint worker owns fingerprint parser hunks and its tests. DNS worker owns independent presence-flag constructor hunks. Integration owner regenerates the task board.

## Risks / Trade-offs

- A matching fingerprint is an integrity check, not proof of freshness or server authentication.
- A legacy bundle has no fingerprint; preserve backward compatibility.

## Migration Plan

No persistence migration. Rollback restores prior import behavior. Validate targeted parser/contract JVM tests, affected ktlint/detekt, and taskctl strict checks. Native, device, artifact, and deployment gates are not required for this parsing-only change.
