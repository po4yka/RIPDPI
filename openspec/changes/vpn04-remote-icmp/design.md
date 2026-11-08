## Context

Standard SOCKS5 (RFC 1928 section 7) has no network-layer error transport. Linux/Android error queues expose the original destination, quoted UDP payload prefix and ICMP origin. Other hosts cannot establish provenance from errno.

## Goals / Non-Goals

- Goal: actual non-root direct UDP error queue to private local SOCKS to TUN.
- Non-goals: remote SOCKS extensions, echo relay, carrier error translation, cross-family translation, ICMP Parameter Problem pointers for rewritten headers, privileged packet capture or Android device acceptance.

## Decisions

- Use private authenticated CMD 0x80. Eligibility is the owned ephemeral authenticated loopback listener and actual loopback TCP peer. Bind/protect the UDP client before negotiation; pin its exact endpoint.
- Reply with standard SOCKS success then `RUF1` and a random 128-bit nonce. Each UDP envelope has magic, kind, reserved zero, exact body length, nonce and datagram ID. Requests have monotonic nonzero IDs; responses use zero; errors identify the original request. Ordinary CMD 3 stays standard.
- Fall back only on explicit REP 7, using a fresh TCP connection. Authentication or malformed private responses never downgrade.
- Enable IP_RECVERR/IPV6_RECVERR only on direct upstream sockets for private associations. Consume bounded MSG_ERRQUEUE records before receive and before a new send. Require origin ICMP/ICMP6, valid ancillary lengths and no truncation.
- Match actual target and quoted payload prefix uniquely within bounded per-socket history. Keep reported records to detect later ambiguity; poison feedback after history exhaustion or untracked mutations. Socket replacement resets or disables history. Do not infer remote errors from errno.
- Carry the logical target and actual ICMP offender. The tunnel maps the ID to an original quote and registration. It suppresses stale or cross-family errors, charges quote bytes to the global memory budget, and retains current UID/rate/lifecycle checks.
- Allow IPv4 type 3 codes 0..15 and type 11 code 0; IPv6 type 1 codes 0..6, type 2 code 0 and type 3 codes 0..1. Preserve MTU info only where defined. Parameter Problem needs upstream-to-original pointer translation and is suppressed.

## Contracts and ownership

- Root: task/spec artifacts, tunnel core, adapter codec re-export and combined validation. No lockfile edit is planned.
- feedback_protocol: isolated codec worktree, only ripdpi-socks5-core UDP feedback module and exports/tests.
- kernel_icmp: isolated kernel worktree, only proxy-runtime-adapter platform UDP error queue module and declaration.
- proxy_feedback: isolated proxy worktree, only proxy-runtime handshake, UDP owners and tests.
- Review agents are read-only. Each writer preserves others' work. Shared dependencies, generated API snapshots and architecture reports have one owner (root).
- No Kotlin/JNI/protobuf/storage/configuration migration. All payload and nonce state stays in bounded volatile memory; no payload logging.

## Risks / Trade-offs

- Kernel quotes can omit payload or be ambiguous: suppress rather than guess.
- Desync, fallback and upstream relays can change provenance: retain actual writes only when reliable, otherwise disable feedback for that generation.
- IPv6 MTU info may be below 1280; retain numeric wire value, leave sender policy to the app stack.
- Host tests do not prove Android delivery. Linux non-root loopback tests must exercise the real kernel; Android physical tests remain unrun without authorization.

## Migration Plan

Both ends negotiate the private command at runtime. Old peers return REP 7 and use standard SOCKS. Revert the feature commit to remove the extension; no persistent data changes.

Exact gates: cargo test --locked and cargo clippy --locked --all-targets -- -D warnings for ripdpi-socks5-core, ripdpi-proxy-runtime-adapter, ripdpi-proxy-runtime and ripdpi-tunnel-core; cargo fmt --all -- --check; just ci-gates; python3 scripts/ci/check_architecture_health.py; cargo metadata --manifest-path native/rust/Cargo.toml --locked. Run real Linux non-root IPv4/IPv6 closed-port and proxy-to-TUN loopback regressions in a capability-free container. Require pr-reviewer, unsafe-code-auditor and async-cancel-safety passes before the feature commit.
