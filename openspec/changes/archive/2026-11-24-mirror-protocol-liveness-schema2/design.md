## Context

RIPDPI vendors every non-documentation file from the deployment repository's
`contract/` directory under `core/data/src/test/resources/contract/`. The
deployment contract set at exact frozen revision
`8396ec8c954eda64ae4b78dc1c8f2d18de207c3b` differs from the current client set
only in `protocol-liveness.schema.json`: schema 2 binds each sentinel policy to
a deployment target. The current client copy is schema 1. Repository search
finds no Kotlin, Rust, Gradle, or test callsite that parses this specific resource;
its consumer boundary is cross-repository byte-identity validation.

## Goals / Non-Goals

- Goal: mirror the frozen protocol-liveness schema exactly and prove that the
  complete 22-file vendored contract set matches the producer.
- Goal: record the deliberate schema 1 to schema 2 compatibility break.
- Non-goal: add runtime policy parsing, network-exposure behavior, device tests,
  emulator tests, or deployment behavior to RIPDPI.

## Decisions

- Copy producer bytes without client-side formatting or interpretation. This
  preserves one source of truth and makes `cmp` the authoritative drift check.
- Keep the change test-resource-only. Adding a Kotlin schema parser would create
  runtime behavior that the client does not currently own.
- Validate both the positive schema 2 shape and rejection of a schema 1 policy
  without `target`; do not add compatibility shims because the producer
  contract intentionally requires migration.
- Pin evidence to the full producer commit and SHA-256, not to a dirty checkout
  or branch name.

## Contracts and ownership

- Serialized shared file:
  `core/data/src/test/resources/contract/protocol-liveness.schema.json`.
- Producer contract:
  `ripdpi-vpn-deploy/contract/protocol-liveness.schema.json` at
  `8396ec8c954eda64ae4b78dc1c8f2d18de207c3b`.
- Kotlin modules and Rust crates: unchanged.
- Network-exposure and probe-matrix schemas: unchanged.
- This dedicated worktree owns the mirror file and this task/OpenSpec record;
  shared `main` and unrelated task lanes remain untouched.

## Risks / Trade-offs

- Schema 1 policy documents no longer validate -> surface the break explicitly
  and prove deterministic rejection instead of preserving an unsafe fallback.
- A locally reformatted copy could look equivalent but fail producer sync ->
  copy exact bytes and compare all 22 contract files.
- A green local schema check could miss repository integration failures -> run
  core data, task/OpenSpec, architecture, configured hooks, and exact-head
  hosted CI before protected integration.

## Migration Plan

1. Freeze and verify the producer revision and schema SHA-256.
2. Replace only the vendored protocol-liveness schema with exact producer bytes.
3. Validate schema 2, legacy rejection, all-contract byte identity, repository
   contracts, core data, architecture, and configured hooks.
4. Publish a feature branch and require exact-head hosted CI before merge.
5. If the producer contract is reverted, revert this one mirror file to the
   corresponding producer bytes; never let client and producer diverge.
