## Context

Audit finding 13 at 202f970 is owned by portfolio task RST-1794036651029797. Read its current callers and tests before implementation; the audit does not authorize unrelated cleanup.

## Goals / Non-Goals

- Goal: The three native state writers must share safe temporary-file lifecycle behavior without losing durability or file permissions.
- Non-goal: Change unrelated protocols, stored identities, UI text, lifecycle behavior, or the conditional audit proposals.

## Decisions

- Use NamedTempFile::new_in in the destination directory, write and sync the file, persist it, then sync the parent directory as required by the existing contract.
- tempfile is already used for tests but will enter the production graph. Assess license, transitive dependencies, binary size, platform support, and approval required by repository policy before promotion.
- Extend existing failed_replacement_cleans_temporary_file, cache_persist_failure_preserves_existing_file, and cache_persist_preserves_existing_file_permissions tests for all production writers.

## Contracts and ownership

- `native/rust/crates/ripdpi-runtime-policy/src/runtime_policy/autolearn/store.rs`
- `native/rust/crates/ripdpi-runtime-policy/src/runtime_policy/cache/connection.rs`
- `native/rust/crates/ripdpi-runtime-adaptive/src/adaptive_tuning/persistence/file_io.rs`

Keep size limits, restrictive permissions and permission preservation, old-file retention on failure, and sync_all ordering. Drop cleanup is not a SIGKILL guarantee.

The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

One writer at a time owns native/rust/Cargo.toml, native/rust/Cargo.lock, the fuzz lockfile when affected, gradle/libs.versions.toml, API snapshots, and architecture outputs. Regenerate owned outputs through their tools; do not relax baselines or bless changed golden files. The epic defines the recommended queue; shared-file order is a scheduling constraint, not a semantic dependency.

The owning writer updates all callers and tests in this slice. Prerequisites: None. Respect the epic priority and shared-file queue.

## Risks / Trade-offs

- Standard parsers can accept or reject different input forms: enforce the task's explicit contract with independent boundary vectors.
- Cancellation and persistence can lose resources or state: exercise the stated failure case, not only round trips.
- Dependency changes can affect size, licensing, and Android support: review actual direct and transitive edges before adoption. No baseline relaxation or unrelated compatibility layer is permitted.

## Migration Plan

First reproduce the specified uncovered behavior or establish a passing contract baseline for mechanical cases. Then replace only the owning infrastructure, keep existing stored and wire formats, and run the gates below. No persisted-data or schema migration is planned. If that assumption fails, revise this plan before editing that contract. Roll back the owning implementation commit if required, without deleting user data.

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-policy -p ripdpi-runtime-adaptive --locked`
- `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-policy -p ripdpi-runtime-adaptive --all-targets --locked -- -D warnings`
- `cargo deny --manifest-path native/rust/Cargo.toml --locked check`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.

## Approved dependency choice (2026-11-07)

The user approved direct use of tempfile 3.27.0 with default features in policy and adaptive runtime crates. Keep the existing lockfile versions. This crate is already in the production relay graph through arti-client. The main runtime gains the normal tempfile, rustix, errno, and linux-raw-sys edges; no new package version is required. The declared licenses permit an MIT or Apache-2.0 choice under the existing repository policy. The Unix backend creates new named files with mode 0600; Android uses the rustix libc backend and system randomness. Keep default getrandom reseeding.

Source review found no tempfile build script or network actions in the relevant existing build scripts. The cached tempfile archive is 44780 bytes and its unpacked source is 187854 bytes. These sizes do not measure native binary growth. Compare the affected Android binary with the same profile and ABI before acceptance; do not update a size baseline to hide a regression.

## Observed Android size

The owning `:core:engine:buildRustNativeLibs` task passed for arm64-v8a with both profile properties set to android-jni-dev and no prebuilt input. Normalize only copies with NDK29 llvm-strip --strip-all and removal of .debug_gdb_scripts. The baseline build had the same native sources after heap removal; the intervening probe-cache commits changed Kotlin and task records only.

- Before libripdpi.so: 25928104 bytes; text 24836905, data 1085360, bss 21376; SHA256 66b9fd084aec3896cbdf1a2d037525db55c8172f2a8d159897d2d0e9354fa9f8.
- After libripdpi.so: 25961824 bytes; text 24872447, data 1086136, bss 24504; SHA256 858b8d8e5884bb793a54368796bdad9498903b58828672517ec03e9e2868083c.
- Whole-task normalized ELF delta: +33720 bytes (0.130%). This is not an isolated dependency cost or a release APK measurement.
- Before native-input digest: 6529501932a53c8bd2b5b60635bd0f0c65c367d42516559325b27200e3370f82.
- After native-input digest: 912cf77bed9ae5f33cde596e35fa6e4d7a97c83407e8ce58fc3e0b72b6f77a68.

All three writers retain file sync before atomic replacement and best-effort directory sync after it. Only autolearn/adaptive create missing parents. Policy fixture nonce helpers remain under cfg(test); no runtime write uses them. RAII cleanup is tested on failure, not after SIGKILL. No native size baseline changes are needed.
