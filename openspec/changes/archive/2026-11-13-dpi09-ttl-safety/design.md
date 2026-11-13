## Context

Current main already owns the OOB stream-integrity repairs. This change closes
only the remaining DPI-09 findings and confirmed TTL/stage failure paths.

## Goals / Non-Goals

- Goal: report actual socket completion and fail before further writes when
  restoration or send-stage completion fails.
- Goal: check the real stream and document the native platform boundary.
- Non-goal: new DPI techniques, repeat semantics, raw sockets or platform ports.
- Non-goal: claim device or external DPI compatibility from local tests.

## Decisions

- Keep the existing TTL-unavailable fallback for an initial failed lowering.
  Treat a failed restoration as a terminal error, with accepted progress intact.
- Share active-family TTL selection between getter and setter. Linux and
  Android mapped IPv4 peers use IP_TTL; other hosts use the native socket
  family because their mapped sockets can reject IP_TTL. Do not accept an
  unrelated family success as proof that the active family changed.
- Keep wait_send as an initial pacing choice. Both deadline branches must fail
  if the kernel still reports unsent bytes.
- Use a sequence-aware independent packet oracle plus strict local echo. Allow
  prefix and urgent byte to use separate segments, as the current emitter does.
- Repair the documentation boundary without adding non-Linux runtime support.

## Contracts and ownership

- runtime_recheck owns ripdpi-desync-runtime TTL setter/lowering and tests,
  in an isolated worktree. H1 and H2 have separate commits.
- smoke_recheck owns ripdpi-cli packet smoke and docs/native/proxy-engine.md,
  in an isolated worktree. F4 and F5 have separate commits.
- contracts_recheck owns ripdpi-privileged-ops stage deadline and tests.
- dpi09-integration owns the portfolio,
  OpenSpec files, combined validation, review, integration and push.
- contracts_recheck also verified the already merged F2/F3/H3 repairs.
- No Kotlin, public API, JNI, protobuf, storage, dependency, schema-version,
  baseline, locale, generated fixture or golden changes are planned.
- Cargo.lock, manifests and generated reports have no parallel writer.

## Risks / Trade-offs

- Stalled sends previously marked successful can now fail. A regression uses a
  real blocked loopback peer to prove the deadline behavior on Linux.
- A TTL restore syscall failure cannot ensure that the OS restored TTL. Stop the
  send and preserve the error rather than claim a completed fallback.
- Kernel segmentation differs. Reassembly checks sequence bytes and urgent
  position instead of requiring one exact segment shape.

## Migration Plan

No data migration is required. Revert a repair commit if its observed behavior
regresses; do not restore a false-success path to make a gate pass.

Before integration, run the affected locked Cargo tests and all-target Clippy,
just ci-gates, architecture health, locked cargo metadata and task validation.
A separate read-only reviewer checks the combined diff. Use local Linux tests
without capabilities for the deadline and socket cases. Packet capture may run
only with existing capture access; no root, physical device or external target
is required or claimed. Fetch and rebase on origin/main before fast-forward
integration. Push only the reviewed main commits requested by the user.
