---
id: RST-1792736814489197
title: Patch ctutils constant-time vulnerabilities
kind: chore
status: doing
area: rust-native
priority: high
owner: Dependency security maintenance
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-23
updated: 2026-10-23
spec_reason: dependency-only
---

## Goal

Resolve the published ctutils constant-time advisory by updating only the native workspace and fuzz lock entries to 0.4.4. No runtime source, dependency requirements, toolchain pins, or policy exceptions change.

## Ownership and serialized paths

Dependency security maintenance owns `native/rust/Cargo.lock`, `native/rust/fuzz/Cargo.lock`, this portfolio record, and its execution file. Lockfiles and the generated `docs/tasks/board.md` use a single serialized writer in the dedicated security worktree. Existing tasks and owner branches are out of scope.

## Acceptance criteria

- Both lockfiles resolve ctutils 0.4.4 with the official archive checksum `12218662ccac37d9714f2191252553d67585b97df8c839cbe49e0bde278dc865`; every other package record is unchanged.
- The pinned Rust 1.98.1 remains unchanged and satisfies ctutils's Rust 1.87 minimum. The existing optional subtle 2.6.1 resolution remains compatible.
- Run targeted locked Cargo validation and relevant native tests; report blocked gates without weakening them.
- Require independent diff review and successful required CI for the exact PR head before merge. Keep this task open until that evidence exists.

## Advisory sources

- RustSec advisory commit: https://github.com/rustsec/advisory-db/commit/c244ff02630e2e4e1eb69e01914b14a37dad7cbb (published with placeholder RUSTSEC-0000-0000 at preparation time).
- Upstream release: https://github.com/RustCrypto/utils/pull/1569.

## Verification evidence

- Native `cargo update -p ctutils --precise 0.4.4` on pinned Rust 1.98.1 produces exactly the reviewed version/checksum-only lock change. Native `cargo tree --locked -i ctutils` passes.
- `cargo test --locked -p ripdpi-shadowsocks` passes on Rust 1.98.1.
- `taskctl validate` passes; existing task records and generated board rows are preserved.
- Fuzz `cargo metadata --locked` is blocked both before and after this patch by existing manifest/lock drift. Current manifests require boring 5.3.0, rustls 0.23.46 and yaml_serde 0.10.7, while the base fuzz lock still resolves older boring/rustls and serde_yaml_ng. This security patch does not silently include that unrelated resolver migration.
- Full required CI, CodeQL, and merge approval remain pending. The fuzz validation step is not marked complete.
