## Context

Audit finding 15 at 202f970 is owned by portfolio task DAT-1794036667822479. Read its current callers and tests before implementation; the audit does not authorize unrelated cleanup.

## Goals / Non-Goals

- Goal: Hex representations of existing fingerprints, digests, receipts, and configured keys must stay byte-for-byte identical with fewer codec copies.
- Non-goal: Change unrelated protocols, stored identities, UI text, lifecycle behavior, or the conditional audit proposals.

## Decisions

- Use Kotlin ByteArray.toHexString and String.hexToByteArray, and existing Rust hex::decode_to_slice for the 32-byte shared-priors key.
- Use only existing platform or workspace libraries. Check the direct module dependency before editing.
- Use existing digest, fingerprint, migration, and key tests with independent fixed byte vectors; cover empty values, mixed case, odd length, invalid digits, and zero keys. Do not bless changed identity fixtures.

## Contracts and ownership

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/FieldTelemetry.kt`
- `core/data/model/src/main/kotlin/com/poyka/ripdpi/data/NetworkStrategyMemory.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/NetworkSnapshotFactory.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/ConnectionPolicySignatureBuilder.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/hosts/HostPackCatalogRepository.kt`
- `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/subscription/DeviceMigrationContract.kt`
- `native/rust/crates/ripdpi-shared-priors/src/key_hex.rs`

Keep lowercase, prefixes, lengths, case acceptance, malformed-input errors, unset-key semantics, and all-zero-key rejection. Do not change hash/MAC algorithms or stored identity values.

The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

One writer at a time owns native/rust/Cargo.toml, native/rust/Cargo.lock, the fuzz lockfile when affected, gradle/libs.versions.toml, API snapshots, and architecture outputs. Regenerate owned outputs through their tools; do not relax baselines or bless changed golden files. The epic defines the recommended queue; shared-file order is a scheduling constraint, not a semantic dependency.

The owning writer updates all callers and tests in this slice. Prerequisites: None. Respect the epic priority and shared-file queue.

## Risks / Trade-offs

- Standard parsers can accept or reject different input forms: enforce the task's explicit contract with independent boundary vectors.
- Cancellation and persistence can lose resources or state: exercise the stated failure case, not only round trips.
- Dependency changes can affect size, licensing, and Android support: review actual direct and transitive edges before adoption. No baseline relaxation or unrelated compatibility layer is permitted.

## Migration Plan

First reproduce the specified uncovered behavior or establish a passing contract baseline for mechanical cases. Then replace only the owning infrastructure, keep existing stored and wire formats, and run the gates below. No persisted-data or schema migration is planned. If that assumption fails, revise this plan before editing that contract. Roll back the owning implementation commit if required, without deleting user data.

- `./gradlew :core:data:testDebugUnitTest :core:data:runtime-state:testDebugUnitTest :core:service:testDebugUnitTest :core:diagnostics:testDebugUnitTest`
- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-shared-priors --locked`
- `./gradlew staticAnalysis`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
