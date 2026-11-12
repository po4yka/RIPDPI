## Context

Task `ENG-1794499735949864` closes F1-F6 found at `69502620c30cf97528158a37f12b7e2724f527c8`. Sockets run synchronously on OS threads. While this task ran, main gained common DPI-04/DPI-06 safety fixes and the adjacent split/hostfake fixes.

## Goals / Non-Goals

- Goal: safe failures, complete profiles, correct packet roles and independent acceptance.
- Non-goal: compatibility expansion, root activation, device/external probes, golden blessing.

## Decisions

- Use published common send safety for F1: restore real pages before cleanup, retain known stream progress and mark opaque fake/ordered failures uncertain. The first-write classifier must refuse replay when bytes or uncertainty are present.
- Keep the existing progress/error types and adapter contracts. Do not add a parallel error hierarchy.
- Use the published common packet builder for F3/F4. MD5 is fake-only. `original_ttl` keeps genuine TTL separate; disorder explicitly requests TTL1.
- Add two unique non-root Linux regressions: actual partial splice/EAGAIN and actual wait timeout, with independent peer reads of real bytes.
- Group primary then secondary per fake region for F2. Sequential offsets count all fake bytes. The hostfake single-profile caller uses one-element lists.
- Require deterministic source bytes and an independent eight-mode packet oracle for F5. Incomplete captures remain smoke-only.
- Keep family/fallback context in the second region for F6.
- User authorized implementation, commits, main integration and push. Reuse already published fixes instead of reintroducing corrected behavior to create duplicate commits.

## Contracts and ownership

- Published main owns common raw/helper progress, no-replay, packet-role MD5, explicit genuine TTL and negotiated bulk helper framing. This task preserves those contracts.
- raw_repairs reviewed the common fixes and the two unique splice/wait tests. It does not replace published production code.
- runtime_repairs owns F2/F6 emissions, fake-family callers and independent outcome tests.
- wire_oracle owns the test asset, instrumentation matrix, oracle and smoke scripts.
- Root owns planning, evidence, final combined review, checks and integration. All agents preserve other work and use isolated ownership.
- No new dependency, locale, protobuf, golden, baseline or helper schema change belongs to this task. Any needed API snapshot is generated only by its owner tool.

## Risks / Trade-offs

- Published opaque failures are deliberately conservative. Unknown progress must forbid retry without invented bytes.
- Preserve the single-profile hostfake caller and adjacent TTL ownership.
- Synthetic oracle tests do not establish Android wire compatibility.

## Migration Plan

No data migration. Preserve accepted fields and permission policy. Run the owning Rust/oracle tests, affected Clippy/builds, Android instrumentation compilation, architecture/API checks and `just ci-gates`. Review the final delta. Fetch/rebase origin/main, repeat affected checks when inputs change, fast-forward main and push. Keep branches/worktrees unless cleanup is authorized.
