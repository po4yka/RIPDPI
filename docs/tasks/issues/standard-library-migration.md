---
id: EPC-1794036260770217
title: Replace confirmed custom infrastructure with standard components
kind: epic
status: todo
area: epic
priority: high
owner: Standard-library migration
parent: null
blocked_by: []
spec_mode: required
openspec_change: standard-library-migration
created: 2026-11-07
updated: 2026-11-07
---

## Goal

Replace the 15 confirmed custom infrastructure cases from the 2026-11-07 audit at revision 202f970bb3574980dd2eb87fea002cac8fbe29f7. Keep observable contracts except the specific defects described in each child task.

## Scope and provenance

The source is the audit in this chat and the seven-case attachment ripdpi_replace.md. Child tasks record audit finding numbers. WorkManager, PCAP, and QUIC varint were conditional findings 16-18 and are excluded. This epic tracks implementation acceptance; creation of plans is not implementation evidence.

## Acceptance criteria

- All 15 confirmed cases have linked child tasks, requirements, explicit ownership, gates, and independent evidence.
- Every child satisfies its acceptance criteria and recorded regression, compatibility, and failure checks.
- Shared Cargo, Gradle, architecture, API, and fingerprint contracts pass on the combined tree without baseline relaxation.
- Each completed implementation unit has its own commit on main; no external publication is inferred.

## Execution order

1. High priority: profile fragments; app import deep links; client certificates; OkHttp cancellation adapters; SOCKS secret comparison; native URL handling; CORS tenant boundaries.
2. Medium priority: UUID validation; CONNECT grammar; ECH DNS codec; standard heap; atomic probe cache; native atomic writes; WorkerReaper queue.
3. Low priority: hex codec consolidation after behavioral fixes.

Only actual prerequisite relationships use blocked_by. Profile fragments precede app deep links. Native URL parsing precedes the CORS policy and ECH codec integration. Other tasks can proceed independently within their priority band, subject to the single-writer lanes below.

## Ownership and shared lanes

The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

One writer at a time owns native/rust/Cargo.toml, native/rust/Cargo.lock, the fuzz lockfile when affected, gradle/libs.versions.toml, API snapshots, and architecture outputs. Regenerate owned outputs through their tools; do not relax baselines or bless changed golden files. The epic defines the recommended queue; shared-file order is a scheduling constraint, not a semantic dependency.

Suggested native manifest queue: SOCKS comparison, native URLs, CORS policy, UUIDs, CONNECT, ECH DNS, heap removal, atomic writes, reaper, hex. If CORS waits for an origin-policy or dependency decision, independent native tasks may use the lane; do not create false blocker edges.

Do not change specialized TLS fingerprints, packet mutations, SOCKS socket protection, stable hash algorithms, schemas, or root policy as incidental cleanup. The dedicated child owns every exception.

## Verification

Each child records named targeted gates. Validate the combined source and dependency contracts after implementation. Hosted CI, device runs, artifacts, and deployment remain separate evidence categories; do not infer them from local checks.

## Linked child tasks

Child records are added below in creation order. Their own records remain the source of truth for state and acceptance.

- [DAT-1794036514216544](normalize-profile-uri-fragments.md): audit 1 (attachment 2); priority band 1; Decode profile URI fragments exactly once.

- [AND-1794036523065646](parse-import-deep-links-with-uri.md): audit 2; priority band 1; Parse import deep links with standard URI components.
