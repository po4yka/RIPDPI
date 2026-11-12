## Context

Task `ENG-1794499735949864` repairs F1 through F6 at 69502620c30cf97528158a37f12b7e2724f527c8. Sockets run synchronously on OS threads.

## Goals / Non-Goals

- Goal: safe failures, complete profiles, correct packet roles and independent acceptance.
- Non-goal: compatibility expansion, root activation, device/external probes, golden blessing.

## Decisions

- Keep io::Result APIs; use typed inner errors for known progress and uncertain delivery. Translate at existing adapter boundaries; no new traits.
- Delivered helper failures are uncertain; not-delivered calls may use the local path. No helper wire change.
- Restore real pages before TTL/MD5 cleanup on all exits. Refuse fallback after possible send.
- Group primary then secondary per fake region; sequential offsets count all fake bytes.
- Apply MD5 only to fake role; pass normal TTL separately to raw original.
- Use deterministic test-owned input and independent packet oracle. Default incomplete captures remain smoke-only.
- User authorized implementation, separate commits, main integration and push; another planning-only approval is not required.

## Contracts and ownership

- raw_repairs: privileged-ops fake_tcp, tcp_payload, raw_socket, send error type/tests; runtime-platform raw/helper errors; proxy-runtime-desync-adapter conversion/fake/ordered errors; desync-runtime platform/types, transport_io/errors/strategy_actions and new progress tests; proxy-runtime state/failure tests.
- runtime_repairs: desync-runtime emissions, fake_split, hostfake/emission_order signature adaptation, fake_ordering and outcome_receipt/nested fake_split tests. F2/F6 remain separate slices.
- wire_oracle: run-android-packet-smoke.sh, test-android-packet-smoke-lib.sh, new oracle/scripts tests, PacketSmokeInstrumentedTest.kt test-only fixture section, androidTest/assets/fakedsplit-wire.json.
- Root: planning, docs clarification, independent review, combined gates, generated API snapshots and integration.
- Serialized Cargo/Gradle versions, locales, golden fixtures, protobuf and helper schema are outside scope. Root alone regenerates any affected API snapshots through owning tools.

## Risks / Trade-offs

- Unknown helper progress must forbid retry without invented bytes.
- Preserve single-profile hostfake caller behavior.
- Synthetic oracle tests do not establish Android wire compatibility.

## Migration Plan

No data migration. Preserve accepted fields and permission policy. Run targeted Rust/oracle tests, affected builds/clippy, instrumentation compilation, architecture/API checks and just ci-gates. Review before issue commits. Fetch/rebase origin/main, repeat affected checks for new inputs, fast-forward main then push. Do not delete branches or worktrees.
