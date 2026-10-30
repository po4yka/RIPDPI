---
id: RST-1792736814489197
title: Patch ctutils constant-time vulnerabilities
kind: chore
status: review
area: rust-native
priority: high
owner: Dependency security maintenance
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-23
updated: 2026-10-30
spec_reason: dependency-only
---

## Goal

Resolve the published ctutils constant-time advisory by updating the native workspace and fuzz lock entries to 0.4.4. Synchronize the stale fuzz lock with the dependency requirements already present in the owner manifests so that locked validation is possible. No runtime source, dependency requirements, features, vendor patches, toolchain pins, workflows, or policy exceptions change.

## Ownership and serialized paths

Dependency security maintenance owns `native/rust/Cargo.lock`, `native/rust/fuzz/Cargo.lock`, this portfolio record, and its execution file. Lockfiles and the generated `docs/tasks/board.md` use a single serialized writer in the dedicated security worktree. Existing tasks and owner branches are out of scope.

## Acceptance criteria

- Both lockfiles resolve ctutils 0.4.4 with the official archive checksum `12218662ccac37d9714f2191252553d67585b97df8c839cbe49e0bde278dc865`. The native lock changes no other package record.
- The fuzz lock additionally satisfies the existing boring 5.3.0, rustls 0.23.46, and yaml_serde 0.10.7 requirements. Its bounded synchronization replaces serde_yaml_ng/unsafe-libyaml with yaml_serde/libyaml-rs, updates boring/boring-sys and rustls, and updates the existing ripdpi-tunnel-config dependency edge. All other package records, registry sources, and Git revisions remain unchanged.
- The pinned Rust 1.98.1 remains unchanged and satisfies ctutils's Rust 1.87 minimum. The existing optional subtle 2.6.1 resolution remains compatible.
- Run targeted locked Cargo validation and relevant native tests, link all 18 instrumented fuzz binaries on the configured nightly, and obtain bounded packet/YAML runtime evidence with ASan and LSan preserved. Report blocked gates without weakening them.
- Require independent diff review and successful required CI for the exact PR head before merge. Keep this task open until that evidence exists.

## Advisory sources

- RustSec advisory commit: https://github.com/rustsec/advisory-db/commit/c244ff02630e2e4e1eb69e01914b14a37dad7cbb (published with placeholder RUSTSEC-0000-0000 at preparation time).
- Upstream release: https://github.com/RustCrypto/utils/pull/1569.

## Verification evidence

- Native `cargo update -p ctutils --precise 0.4.4` on pinned Rust 1.98.1 produces exactly the reviewed version/checksum-only lock change. Native `cargo tree --locked -i ctutils` passes.
- `cargo test --locked -p ripdpi-shadowsocks` passes on Rust 1.98.1 (81 tests). Supplemental `ripdpi-tunnel-config` tests pass on the same pin (33 unit and eight YAML compatibility tests).
- `taskctl validate` passes; existing task records and generated board rows are preserved.
- Original and ctutils-only fuzz locks both failed `cargo metadata --locked` because the existing manifests already require boring 5.3.0, rustls 0.23.46, and yaml_serde 0.10.7. The independently reviewed synchronization candidate now passes locked metadata; its SHA-256 is `5620999132d4dab0cc2f2153f47bcd55251374bf67af52e8c45a289677e53a5b`.
- All 18 real fuzz binaries link successfully using the configured nightly, rustc `1.101.0-nightly (7189d39d9 2026-10-22)`, and cargo-fuzz 0.13.2. Because that cargo-fuzz version has no locked option, independently reviewed local-only instrumentation adds only `--locked` to child Cargo commands. Actual compiler fingerprints retain ASan, coverage, cfg(fuzzing), and debug assertions. No repository workflow or compiler feature was changed; the candidate lock hash remains unchanged.
- Local packet smoke executes its two initial corpus runs, then exits 1 because LSan cannot attach to its own thread in this cloud execution environment (errno 1). A minimal ASan program reproduces the environment failure. The empty crash artifact does not establish a product defect. A single ordinary elevated execution attempt fails before starting the command because the execution wrapper cannot initialize; no security setting or sanitizer was changed, and no workaround was attempted. YAML fuzz runtime remains unexecuted under the same unresolved limitation. These runtime gates are not reported as passing.
- The earlier minimal head `d0552d25e313bd0cb4cbcf62be85cdc2031f0023` passed all six workflows, including CI (46 successful jobs, 18 skipped), CodeQL, and L7 adversarial checks. Those results do not certify this synchronization. The new exact head needs fresh required CI and hosted runtime evidence without implicit lock drift; independent review and merge approval remain required. The execution record deliberately remains 2/4 complete.
- Merged as #654 (`5e778024b`, exact head `f1ab65f16`). On that exact head, `ci-required` (run 39920289628), CodeQL, `cargo-deny`, `rust-workspace-tests`, `rust-lint`, `rust-miri`, and all six `fuzz-nightly` shards (run 39921030244, ASan defaults unchanged, 18 targets, about 56 million executions, no sanitizer findings) succeeded, and the PR records the independent lock/advisory/task review. These hosted runs supply the packet/YAML fuzz runtime evidence that the local cloud LSan limitation blocked.
- Post-merge `main` (`f0d30a8c4`): the native lock resolves ctutils 0.4.5 (patch bump in #659 by the dependency security gate) and the fuzz lock resolves ctutils 0.4.4 with the reviewed checksum; `cargo metadata --locked` passes for both workspaces on pinned Rust 1.98.1.
