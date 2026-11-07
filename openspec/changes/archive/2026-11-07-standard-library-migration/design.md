## Context

The audit at 202f970 identified 15 confirmed cases and three conditional proposals. Existing task records were searched; the earlier Rust Protobuf and Kotlin DNS work is not duplicated.

## Goals / Non-Goals

- Goal: Make each confirmed replacement executable, bounded, and independently verifiable.
- Non-goal: Implement a replacement in the planning change or include WorkManager, PCAP, QUIC varint, or broad TLS/DPI rewrites.

## Decisions

- Keep one portfolio and OpenSpec change per confirmed case, with this epic as the index.
- Use blocked_by only for the profile-decode and native-URL prerequisites. Priority bands and single-writer lanes express the remaining recommended order.
- CORS work starts by fixing an explicit origin policy; PSL grouping alone never grants credential access.

## Contracts and ownership

The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

One writer at a time owns native/rust/Cargo.toml, native/rust/Cargo.lock, the fuzz lockfile when affected, gradle/libs.versions.toml, API snapshots, and architecture outputs. Regenerate owned outputs through their tools; do not relax baselines or bless changed golden files. The epic defines the recommended queue; shared-file order is a scheduling constraint, not a semantic dependency.

Each child owns its source paths, tests, manifest edges, and generated outputs. No child can silently change another protocol, a stored identity, or a JNI/schema contract.

## Risks / Trade-offs

- New adapters may change input acceptance or cancellation: require specific failure and compatibility tests.
- New production dependencies may increase size or maintenance: assess the actual dependency graph and repository approval requirements before addition.
- Small code reductions may not justify a new dependency: keep conditional cases outside this programme.

## Migration Plan

Create the child plans, then implement in the epic's priority order subject to blockers and shared-file ownership. Apply no stored-data migration unless a child explicitly specifies one. Revert only the owning implementation commit if necessary; preserve user data. Run the combined source gates after affected child work and record each implementation SHA.
