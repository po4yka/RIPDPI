# Root Helper Contract

How RIPDPI performs privileged (uid-0) network operations on rooted devices —
the IPC protocol, the command set, the capability model, the session
authentication, and the **mandatory non-root fallback**.

Companion docs: [`ARCHITECTURE.md`](ARCHITECTURE.md),
[`NATIVE_RUST.md`](NATIVE_RUST.md), [`RUNTIME_MODES.md`](RUNTIME_MODES.md) §5,
[`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) §7,
[`JNI_CONTRACT.md`](JNI_CONTRACT.md).

---

## Invariant — the non-root baseline

> **RIPDPI must fully function on non-rooted devices.** The root helper and
> every privileged operation are **opt-in behind the `root_mode_enabled`
> setting** (`AppSettings` proto field 135) and **must degrade gracefully**
> when root is absent or the helper fails to start. A privileged code path
> with no non-root fallback — a local non-privileged path, a clean error, or
> inert behavior — is a bug. See [`AGENTS.md`](../../AGENTS.md) § Project
> Rules.

Root mode is **off by default**. Enabling it never changes the default
non-root behavior of any other device; it only adds privileged paths the
runtime *may* select when capability probing confirms they work.

## What the root helper is

`ripdpi-root-helper` is a **standalone privileged ELF binary** — not a `.so`,
not JNI. On rooted devices (Magisk / KernelSU / APatch), when
`root_mode_enabled` is set, `RootHelperManager.kt` extracts the binary from APK
assets, launches it via `su`, and the native runtime talks to it over a
Unix-domain socket. It runs as uid 0 and exists only to perform raw-socket /
`TCP_REPAIR` / IP-fragmentation operations an unprivileged Android process
cannot.

## Crate map

| Crate | Role | Layer |
|-------|------|-------|
| `ripdpi-root-helper-protocol` | The IPC wire protocol — `CMD_*` command constants, `HelperRequest`/`HelperResponse`, session-nonce validation, `SCM_RIGHTS` fd passing | L5 |
| `ripdpi-root-helper` | The privileged `bin` — socket setup, command dispatch + handlers, runs as uid 0 | L5 |
| `ripdpi-privileged-ops` | The privileged primitives (raw sockets, `TCP_REPAIR`, fragmentation, TTL) the helper — and the local fallback — execute | L5 |
| `ripdpi-capabilities` | The device-capability model that gates whether a privileged op may run | L5 |
| `ripdpi-runtime-platform` | Hosts the helper **client** (`root_helper_client.rs`), the `root_helper.rs` registry, and the `with_root_helper()`-gated dispatch with local fallback | L5 |
| `RootHelperManager.kt` (`:core:service`) | Kotlin lifecycle — asset extract, `su`-launch, socket-readiness poll, graceful stop | — |

## IPC transport

- A **Unix-domain socket** in the **filesystem namespace** —
  `<filesDir>/root_helper.sock`. The helper `bind`s the `UnixListener`, then
  `chown`s the socket to its parent directory's uid/gid, sets mode `0o600`
  (owner read/write only), and runs `restorecon` so the SELinux label allows
  the app uid to connect (`ripdpi-root-helper/src/main.rs`,
  `prepare_socket_for_app`).
- **Framing (protocol v3):** each JSON object is prefixed by a 4-byte big-endian
  payload length with an **8192-byte cap** (`MAX_MESSAGE_BYTES`,
  `ripdpi-root-helper-protocol/src/scm_rights.rs`). Exact-size `recvmsg` loops
  preserve frame boundaries and SCM_RIGHTS data across `SOCK_STREAM` short
  reads; `send_message` completes short writes without resending the fd.
- **Request** — `HelperRequest { command: String, params: Value,
  session_nonce: Option<String> }`. **Response** — `HelperResponse { ok: bool,
  error: Option<String>, data: Value, protocol_version: Option<u32>,
  capability_version: Option<u32> }` (`src/wire.rs`). The helper stamps every
  outgoing response with `PROTOCOL_VERSION` / `CAPABILITY_VERSION` from
  `wire.rs`. Both version fields are `#[serde(skip_serializing_if =
  "Option::is_none")]` and `#[serde(default)]` — a legacy client reads them
  as `None` and a legacy response JSON remains serde-compatible. The v2 stream
  framing itself requires the bundled client and helper binary to match.
- The client connects **per operation**, sends one JSON command plus, for the
  socket-bound commands, the relevant socket file descriptor via **`SCM_RIGHTS`**
  ancillary data. The helper replies with a JSON response and, for
  `TCP_REPAIR`-class operations, an optional **replacement fd** the client
  swaps in via a `dup2`-class call.
- The helper applies a 30 s read / 10 s write timeout per connection.
- The socket path is published to native code (`Tun2SocksConfig.rootHelperSocketPath`)
  **only after** the socket is confirmed connectable — never a stale path.

## Session authentication — the nonce

Every request is gated by a per-launch **session nonce**; there is no other
handshake.

- `RootHelperManager.kt` generates **32 secure-random bytes** (`SecureRandom`),
  encodes them URL-safe Base64 without padding, and writes them to
  `<filesDir>/root_helper.sock.nonce` with owner-only read/write permission
  **before** launching the helper.
- The helper reads the nonce file **once at startup** and validates its shape
  with `valid_session_nonce` — **32–128 bytes**, ASCII `[A-Za-z0-9-_]` only
  (`ripdpi-root-helper-protocol/src/wire.rs`). An invalid nonce file aborts
  helper startup with `PermissionDenied`.
- The client loads the nonce from the `.nonce` file and includes it in the
  `session_nonce` field of **every** `HelperRequest`.
- The helper checks `session_nonce_matches` on every connection. On mismatch
  it **closes any received fd**, returns an error response, and does **not**
  dispatch the command.
- `stop()` and the nonce file are removed together; a new launch always mints
  a fresh nonce, so a stale socket cannot be reused across sessions.

## Command set — stable identifiers

The `CMD_*` string constants in
`native/rust/crates/ripdpi-root-helper-protocol/src/commands.rs` are a **frozen
wire contract**. Every command is namespaced by the protocol version so a
stale helper rejects a newer request before privileged dispatch, including if
the helper is replaced between the preflight and the operation. Renaming or
repurposing a command is a breaking protocol change; the helper binary and the
client update in lock-step. **Adding a new command is out of scope for routine
work** (it is a deliberate, security-reviewed change).

| `CMD_*` | Wire string | Privileged operation | Client→helper fd | Helper→client reply fd |
|---------|-------------|----------------------|------------------|------------------------|
| `CMD_PROTOCOL_PREFLIGHT` | `v3/protocol_preflight` | Pure protocol-version handshake; no capability probe or privileged operation | — | — |
| `CMD_PROBE_CAPABILITIES` | `v3/probe_capabilities` | Probe raw-socket / `TCP_REPAIR` support | — | — |
| `CMD_SEND_FAKE_TCP` | `v3/send_fake_tcp` | Emit a TTL-limited / decoy TCP segment | TCP socket fd | optional replacement fd |
| `CMD_SEND_FAKE_RST` | `v3/send_fake_rst` | Emit a fake TCP RST | TCP socket fd | — |
| `CMD_SEND_FLAGGED_TCP_PAYLOAD` | `v3/send_flagged_tcp_payload` | Send a TCP payload with overridden flags | TCP socket fd | optional replacement fd |
| `CMD_SEND_SEQOVL_TCP` | `v3/send_seqovl_tcp` | Send a sequence-overlapped TCP segment | TCP socket fd | optional replacement fd |
| `CMD_SEND_MULTI_DISORDER_TCP` | `v3/send_multi_disorder_tcp` | Send TCP segments out of order | TCP socket fd | optional replacement fd |
| `CMD_SEND_ORDERED_TCP_SEGMENTS` | `v3/send_ordered_tcp_segments` | Send explicitly ordered TCP segments | TCP socket fd | optional replacement fd |
| `CMD_SEND_IP_FRAGMENTED_TCP` | `v3/send_ip_fragmented_tcp` | Send an IP-fragmented TCP packet | TCP socket fd | optional replacement fd |
| `CMD_SEND_IP_FRAGMENTED_UDP` | `v3/send_ip_fragmented_udp` | Send an IP-fragmented UDP datagram | UDP socket fd | — |
| `CMD_SEND_SYN_HIDE_TCP` | `v3/send_syn_hide_tcp` | Experimental: SYN-hide TCP probe | — | — |
| `CMD_SEND_ICMP_WRAPPED_UDP` | `v3/send_icmp_wrapped_udp` | Experimental: UDP wrapped in ICMP | — | — |
| `CMD_RECV_ICMP_WRAPPED_UDP` | `v3/recv_icmp_wrapped_udp` | Experimental: receive ICMP-wrapped UDP | — | — |
| `CMD_SEND_RAW_IP_PACKET` | `v3/send_raw_ip_packet` | Experimental: send a caller-supplied raw IP packet | — | — |
| `CMD_SHUTDOWN` | `v3/shutdown` | Finish the in-flight request and exit | — | — |

Per-command parameter structs live in
`ripdpi-root-helper-protocol/src/params.rs` and are serialized into the
`params` JSON value. The experimental commands (`send_syn_hide_tcp`,
`send_icmp_wrapped_udp`, `recv_icmp_wrapped_udp`, `send_raw_ip_packet`) pass no
fd — the helper opens its own raw socket; they are `lab_diagnostics_only` tier.

### Descriptor-driven request validation

Both the client (`ripdpi-runtime-platform::root_helper_client::transport`)
and the helper dispatch (`ripdpi-root-helper::dispatch`) pre-validate every
request against `command_descriptor::COMMAND_DESCRIPTORS` via
`validate_request(command, has_inbound_fd, params_present)`. The validator
returns a typed `DescriptorValidationError` — `UnknownCommand`, `MissingFd`,
`UnexpectedFd`, `MissingParams` — whose `Display` form is reused as the error
message on both sides. On the helper side, `UnexpectedFd` / `UnknownCommand`
/ `MissingParams` rejections explicitly `close(2)` any inbound `SCM_RIGHTS`
fd attached to the rejected request, closing the previous silent-leak path
where a non-fd command received an unexpected descriptor.

Per-handler `require_fd` / `decode_params` checks remain in place under the
validator as defence-in-depth — the validator catches first, but a future
refactor of either layer cannot silently disable the rule. The drift tests
`every_dispatch_arm_has_a_descriptor` (in `dispatch.rs`) and
`every_command_descriptor_has_a_dispatch_handler` (already present) pin the
bidirectional coverage.

### File-descriptor passing

`SCM_RIGHTS` carries at most **one fd per message** in each direction
(`scm_rights.rs`). For the socket-bound commands the client sends the live
`TcpStream` / `UdpSocket` fd so the helper operates on the *same* kernel
socket. `TCP_REPAIR`-class commands may return a **replacement fd**; the
client installs it over the original descriptor via
`ripdpi_privileged_ops::swap_replacement_fd` (a `dup2`-class call). When the
helper rejects a request (bad nonce, parse failure) it closes any received fd
so no descriptor leaks across the uid-0 boundary.

## Capability probing & gating

`probe_capabilities` returns a JSON capability set —
`{ "raw_ipv4": bool, "raw_ipv6": bool, "tcp_repair": bool }`. The runtime
converts this into typed capability outcomes; `ripdpi-capabilities` models
them. A privileged op is attempted only if `probe_capabilities` advertised the
capability it needs — capability checks decide whether an emitter runs, they do
not change the tactic taxonomy. Tactics are tiered `non_root_production` /
`rooted_production` / `lab_diagnostics_only` (see
[`architecture/README.md`](README.md)).

## Helper lifecycle — `RootHelperManager.kt`

`RootHelperManager` (`core/service/.../services/RootHelperManager.kt`) owns the
Kotlin side. `syncRootMode(context, rootModeEnabled)` is the entry point:
`rootModeEnabled == false` stops the helper and returns `null`; `true` calls
`ensureStarted()`.

**Start** (`start()`):
1. Extract `ripdpi-root-helper` from APK assets (`bin/<abi>/ripdpi-root-helper`)
   to `<filesDir>/ripdpi-root-helper`, owner-executable.
2. Mint and write the session nonce file.
3. For each launch attempt, `exec` the helper and poll the socket for
   readiness (`LocalSocket` connect, 100 ms interval, 3000 ms timeout).
4. On first success, record and return the socket path; otherwise try the next
   attempt.

**`su` invocation** — unchanged by this audit. Candidates are `su`,
`/system/xbin/su`, `/system/bin/su` (absolute paths filtered by `canExecute()`).
Each is tried in two forms — `su -c exec <helper>` (Magisk-style) and
`su 0 sh -c exec <helper>` (AOSP-style) — for up to six attempts. The helper
argv is `ripdpi-root-helper --socket <path> --session-nonce-file <path>`, with
each path shell-quoted.

**Stop** (`stop()`): send a `shutdown` JSON command over the socket (graceful),
`process.destroy()`, then `destroyForcibly()` after a 1 s grace period, then a
best-effort `killall -TERM`/`-KILL ripdpi-root-helper` via `su` to reap any
detached process, and finally remove the socket and nonce files. The helper
itself handles `SIGTERM` and stops its accept loop.

**Lifecycle binding:** `syncRootMode` is driven from connection-policy
resolution, so the helper is reconciled with the live `root_mode_enabled`
setting on every policy change. The helper is **not** a long-lived daemon
across the setting being toggled off.

## Non-root fallback contract

Every privileged dispatch in `ripdpi-runtime-platform` checks
`with_root_helper()` (`root_helper.rs`) first; the helper registry is a
generation-guarded `RwLock<Option<...>>`. `with_root_helper()` returns `None`
when no helper is registered — which the dispatch site treats as the signal to
take the **local non-privileged path**. A root-only tactic whose local path is
itself unavailable on the device must produce a clean "unavailable" outcome or
`io::Error`, never a crash.

**Fallback audit.** Every `with_root_helper()` dispatch site in
`ripdpi-runtime-platform` was verified to have an explicit local fallback:

| Dispatch site | Privileged commands | Fallback |
|---------------|---------------------|----------|
| `fake_send/root_helper_dispatch.rs` → callers `fake_send/{fake_rst,fake_tcp,flagged_payload,ordered_segments,seqovl}.rs` | `send_fake_rst/tcp`, `send_flagged_tcp_payload`, `send_ordered_tcp_segments`, `send_seqovl_tcp` | `if let Some(result) = …dispatch::send_*(…) { return result }` → local `ripdpi_privileged_ops::send_*` |
| `ip_fragmentation/tcp.rs` | `send_ip_fragmented_tcp`, `send_multi_disorder_tcp` | `if let Some(result) = with_root_helper(…) { return result }` → local `ripdpi_privileged_ops::*` |
| `ip_fragmentation/udp.rs` | `send_ip_fragmented_udp` | same |
| `ip_fragmentation/capabilities.rs` | `probe_capabilities` | `with_root_helper(…)` → local `probe_ip_fragmentation_capabilities` |
| `experimental_tier3.rs` | `send_syn_hide_tcp`, `send_icmp_wrapped_udp`, `recv_icmp_wrapped_udp`, `send_raw_ip_packet` | `if let Some(result) = with_root_helper(…) { return result }` → local `ripdpi_privileged_ops::*` |

The `root_helper_dispatch.rs` functions return `Option<io::Result<()>>` — a
`None` is the rooted-vs-non-root branch, never an error. On the Kotlin side,
`RootHelperManager.start()` returns `null` on **every** failure path (no `su`,
`su` denied, socket never connectable), so the native runtime receives
`rootHelperSocketPath: null`, no client is registered, and `with_root_helper()`
returns `None`. **Audit result: no privileged path lacks an explicit non-root
fallback; no follow-up is required.**

## Security posture

The helper is a **uid-0 process boundary** — treat every request as untrusted,
validate aggressively, and route protocol changes through security review. The
session nonce confines the socket to the launching app session. The helper
still honors the `VpnService.protect()` invariant for any non-loopback socket
it opens. See [`.claude/rules/vpnservice-protect-invariant.md`](../../.claude/rules/vpnservice-protect-invariant.md).

## Adding a privileged operation

See [`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) §7 — add the
`CMD_*` constant + param struct to `ripdpi-root-helper-protocol`, the handler
to `ripdpi-root-helper`, the primitive to `ripdpi-privileged-ops`, the
`with_root_helper()`-gated dispatch **with a local fallback** to
`ripdpi-runtime-platform`, and advertise the capability through
`probe_capabilities`. A new privileged operation is a security-reviewed change,
not routine work.

---

## Cross-references

| Topic | Source |
|-------|--------|
| Crate taxonomy & dependency direction | [`NATIVE_RUST.md`](NATIVE_RUST.md) |
| Root-helper runtime flow | [`RUNTIME_MODES.md`](RUNTIME_MODES.md) §5 |
| Adding a privileged operation | [`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) §7 |
| `VpnService.protect()` invariant | [`.claude/rules/vpnservice-protect-invariant.md`](../../.claude/rules/vpnservice-protect-invariant.md) |
| Root Helper IPC narrative | [`AGENTS.md`](../../AGENTS.md) § Root Helper IPC |
