---
id: DAT-1794036667822479
title: Replace custom hex codecs with standard library functions
kind: chore
status: done
area: data
priority: low
owner: Standard-library migration
parent: EPC-1794036260770217
blocked_by: []
spec_mode: required
openspec_change: replace-custom-hex-codecs
created: 2026-11-07
updated: 2026-11-07
status_detail: Implementation and required local checks passed; awaiting committed acceptance evidence.
closed_at: "2026-11-07T12:03:01Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Implementation f1043178d accepted: 5081 full Kotlin module tests,35 Rust tests,staticAnalysis,source/dependency gates and independent compatibility review passed;identity fixtures unchanged."
---

## Goal

Hex representations of existing fingerprints, digests, receipts, and configured keys must stay byte-for-byte identical with fewer codec copies.

## Audit evidence

Audit finding 15, 2026-11-07, baseline 202f970bb3574980dd2eb87fea002cac8fbe29f7. This is a confirmed replacement candidate, not proof that an implementation is complete. Recheck callers and tests before editing.

## Scope

Use Kotlin ByteArray.toHexString and String.hexToByteArray, and existing Rust hex::decode_to_slice for the 32-byte shared-priors key and manifest hash.

Keep lowercase, prefixes, lengths, case acceptance, malformed-input errors, unset-key semantics, and all-zero-key rejection. Do not change hash/MAC algorithms or stored identity values.

## Acceptance criteria

- Each known byte vector produces exactly the previous lowercase hex string including leading zero bytes and existing prefixes.
- Malformed keys, wrong lengths, and explicitly all-zero shared-priors keys remain rejected with existing error categories.
- Previously stored fingerprints, digests, receipts, and keys remain identical; build.rs receives the required direct hex build dependency.
- Use existing digest, fingerprint, migration, and key tests with independent fixed byte vectors; cover empty values, mixed case, odd length, invalid digits, and zero keys. Do not bless changed identity fixtures.
- Named local gates and repository-required independent review pass; record exact implementation SHA and command results. Commit each complete atomic implementation unit on main.

## Dependencies and order

Priority band 3; recommended position 15 in the epic queue. Prerequisites: None. Respect the epic priority and shared-file queue.

No dependency is implied by priority alone. Shared Cargo and Gradle files use one writer; the epic's queue prevents concurrent edits without introducing false semantic blockers.

## Ownership

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/FieldTelemetry.kt`
- `core/data/model/src/main/kotlin/com/poyka/ripdpi/data/NetworkStrategyMemory.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/NetworkSnapshotFactory.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/ConnectionPolicySignatureBuilder.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/hosts/HostPackCatalogRepository.kt`
- `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/subscription/DeviceMigrationContract.kt`
- `native/rust/crates/ripdpi-shared-priors/src/key_hex.rs`
- `native/rust/crates/ripdpi-shared-priors/src/manifest.rs`

The same writer owns related module tests, direct dependency declarations, and task artifacts. Shared dependency and generated-output lanes follow the epic. The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

## Dependency decision

Use only existing platform or workspace libraries. Check the direct module dependency before editing.

## Verification

- `./gradlew :core:data:testDebugUnitTest :core:data:runtime-state:testDebugUnitTest :core:service:testDebugUnitTest :core:diagnostics:testDebugUnitTest`
- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-shared-priors --locked`
- `./gradlew staticAnalysis`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
