## Context

See proposal.md. Existing Xray Android tests provide separate-UID requests,
server receipts, restart and direct-bypass rejection. Other peers provide host
interop. The Mac is ARM64; CI managed devices use x86_64.

## Goals / Non-Goals

- Goal: one executable catalog and strict reports for existing and new local lanes.
- Goal: disposable Lima Linux routing and real Android endpoint injection.
- Non-goal: claim local fixtures reproduce Cloudflare, carrier DPI, physical
  handover, or OEM lifecycle behavior. Existing release gates stay intact.

## Decisions

- Use Python standard-library orchestration and JSON manifests; reuse current
  build tools, independent peers, and instrumentation tests.
- Run Android Emulator on macOS, not inside the Linux VM. VM hosts router and
  peers. Separate host-native, Android, routed fault, and packet-fidelity lanes.
- Adapters accept --scenario, --run-id, --out-dir and emit result.json.
  The coordinator validates adapter identity and artifacts before reporting pass.
- Keep control connections outside the fault path. Prove TCP and UDP fault
  placement using full-drop negative controls and recovery.
- Pin independent peers; prepare dependencies before runtime isolation. Do not
  infer offline operation without observed egress controls.
- Use fresh run directories. Publish sanitized metadata; keep credentials and raw
  packet captures private. Missing prerequisites are blocked, never passed.

## Contracts and ownership

Coordinator owns manifest, evidence contract, runner, docs, CI and task files.
VM worker owns acceptance/vm, Lima configuration and its tests. Android worker
owns Android adapter, instrumentation, Xray fixture and associated tests. Peer
worker owns acceptance/peers and Hysteria2 interop tests. Each works in an isolated
worktree. No production JNI/protobuf/storage migration or dependency is planned.
Cargo.lock, workflows, registries and shared manifests have one coordinator writer.

## Risks / Trade-offs

- Emulator NAT changes packet evidence: use a distinct native Linux namespace lane.
- Test assumptions can skip: require exact methods and reject skips.
- Long native builds: prepare once and reuse APKs only with explicit hashes.
- Local self-signed peers: separate protocol exchange from certificate validation.
- Host privileges: confine changes to task-owned VM and namespaces; cleanup must
  not flush unrelated rules or delete another run's resources.

## Migration Plan

Additive tooling; existing commands stay valid. Validate Python/shell contracts,
focused native and Android tests, architecture gates, and real local smoke.
Wire CI to the same manifest. Revert the new tooling to roll back; no user data
migration. Record blocked runtime/CI checks explicitly and keep task in review.
