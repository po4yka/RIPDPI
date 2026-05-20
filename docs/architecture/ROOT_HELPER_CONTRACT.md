# Root Helper Contract

How RIPDPI performs privileged (uid-0) network operations on rooted devices —
the IPC protocol, the command set, the capability model, and the **mandatory
non-root fallback**.

Companion docs: [`ARCHITECTURE.md`](ARCHITECTURE.md),
[`NATIVE_RUST.md`](NATIVE_RUST.md), [`RUNTIME_MODES.md`](RUNTIME_MODES.md) §5,
[`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) §7.

---

## Invariant — the non-root baseline

> **RIPDPI must fully function on non-rooted devices.** The root helper and
> every privileged operation are **opt-in behind the `root_mode_enabled`
> setting** and **must degrade gracefully** when root is absent or the helper
> fails to start. A privileged code path that has no non-root fallback (a local
> non-privileged path, or inert behavior) is a bug — see
> [AGENTS.md](../../AGENTS.md) § Project Rules.

## What the root helper is

`ripdpi-root-helper` is a **standalone privileged ELF binary** — not a `.so`,
not JNI. On rooted devices (Magisk / KernelSU / APatch), when `root_mode_enabled`
is set, `RootHelperManager.kt` extracts the binary from APK assets, launches it
via `su`, and the native runtime talks to it over a Unix-domain socket. It runs
as uid 0 and exists only to perform raw-socket / `TCP_REPAIR` / IP-fragmentation
operations that an unprivileged Android process cannot.

## Crate map

| Crate | Role | Layer |
|-------|------|-------|
| `ripdpi-root-helper-protocol` | The IPC wire protocol — `CMD_*` command constants, request/response params, `SCM_RIGHTS` fd passing | L5 |
| `ripdpi-root-helper` | The privileged `bin` — command dispatch + handlers, runs as uid 0 | L5 |
| `ripdpi-privileged-ops` | The privileged primitives (raw sockets, `TCP_REPAIR`, fragmentation, TTL) the helper executes | L5 |
| `ripdpi-capabilities` | The device-capability model that gates whether a privileged op may run | L5 |
| `ripdpi-runtime-platform` | Hosts the helper **client** (`root_helper_client.rs`) and the `with_root_helper()` dispatch with local fallback | L5 |
| `RootHelperManager.kt` / `RootDetector.kt` (`:core:service`) | Kotlin lifecycle — extract, `su`-launch, socket-readiness poll, stop; `su` access test | — |

## IPC transport

- A **Unix-domain socket** in the filesystem namespace (`root_helper.sock`),
  guarded by a 32-byte secure-random **session nonce** (`root_helper.sock.nonce`).
- The client connects **per operation**, sends a JSON command plus the relevant
  socket file descriptor via **`SCM_RIGHTS`** ancillary data
  (`ripdpi-root-helper-protocol/src/scm_rights.rs`).
- The helper replies with a JSON response and, for `TCP_REPAIR`-class
  operations, an optional **replacement fd**, which the client swaps in via
  `dup2()`.
- The path is published to native code (`Tun2SocksConfig.rootHelperSocketPath`)
  **only after** the socket is confirmed connectable — never a stale path.

## Command set — stable identifiers

The `CMD_*` string constants in
`native/rust/crates/ripdpi-root-helper-protocol/src/commands.rs` are a **frozen
wire contract** (`do not add new commands` is a constraint on routine work; a
genuine new command is a deliberate, reviewed change):

`probe_capabilities`, `send_fake_tcp`, `send_fake_rst`,
`send_flagged_tcp_payload`, `send_seqovl_tcp`, `send_multi_disorder_tcp`,
`send_ordered_tcp_segments`, `send_ip_fragmented_tcp`, `send_ip_fragmented_udp`,
`send_syn_hide_tcp`, `send_icmp_wrapped_udp`, `recv_icmp_wrapped_udp`,
`send_raw_ip_packet`, `shutdown`.

Renaming or repurposing a command is a breaking protocol change — add, never
rename; the helper binary and the client must update in lock-step.

## Capability probing & gating

`probe_capabilities` returns a JSON capability set
(`{ "raw_ipv4": bool, "raw_ipv6": bool, "tcp_repair": bool }`). The runtime
converts this into typed capability outcomes; `ripdpi-capabilities` models them.
A privileged op is attempted only if `probe_capabilities` advertised the
capability it needs — capability checks decide whether an emitter runs, they do
not change the tactic taxonomy.

## Non-root fallback contract

Every privileged dispatch in `ripdpi-runtime-platform` checks
`with_root_helper()` first; when no helper is registered it **falls back to a
local non-privileged path** or returns inert. Tactics are tiered
`non_root_production` / `rooted_production` / `lab_diagnostics_only` (see
[`architecture/README.md`](README.md)). A root-only tactic that is unavailable
must produce a clean "unavailable" outcome, never an error or a crash.

## Security posture

The helper is a **uid-0 process boundary** — treat every request as untrusted,
validate aggressively, and route protocol changes through security review. The
helper still honors the `VpnService.protect()` invariant for any non-loopback
socket it opens.

## Adding a privileged operation

See [`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) §7 — add the
`CMD_*` constant + wire types to `ripdpi-root-helper-protocol`, the handler to
`ripdpi-root-helper`, the primitive to `ripdpi-privileged-ops`, the
`with_root_helper()`-gated dispatch (with fallback) to `ripdpi-runtime-platform`,
and advertise the capability through `probe_capabilities`.

---

## Cross-references

| Topic | Source |
|-------|--------|
| Crate taxonomy & dependency direction | [`NATIVE_RUST.md`](NATIVE_RUST.md) |
| Root-helper runtime flow | [`RUNTIME_MODES.md`](RUNTIME_MODES.md) §5 |
| Adding a privileged operation | [`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) §7 |
| `VpnService.protect()` invariant | [`.claude/rules/vpnservice-protect-invariant.md`](../../.claude/rules/vpnservice-protect-invariant.md) |
| Root Helper IPC narrative | [`AGENTS.md`](../../AGENTS.md) § Root Helper IPC |
