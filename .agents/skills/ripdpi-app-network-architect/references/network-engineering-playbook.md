# RIPDPI App Network Engineering Playbook

## Principle

Treat the app as a split control-plane/data-plane network system:

- control plane: UI settings, lifecycle, JNI commands, telemetry polling, diagnostics orchestration;
- data plane: native packet/session processing, SOCKS5, TUN bridge, relay transport, DNS forwarding.

A good change improves one of: correctness, reachability, observability, privacy, testability, or operational resilience.

## Common review patterns

### VPN route loop

Symptom: upstream sockets are routed into the app's own TUN.

Check: every non-loopback outbound socket opened while VPN mode is active must be protected before connect/bind. Validate both JNI callback and Unix-socket fallback paths.

### MTU black hole

Symptom: TCP connects but stalls; QUIC/Hysteria/WG works on some networks and fails on others; large TLS records fail while small probes pass.

Check: configured MTU, tunnel overhead, IPv6 minimum MTU, UDP payload sizing, MSS, and whether diagnostics can distinguish path MTU failure from protocol block.

### DNS leak or resolver exhaustion

Symptom: direct DNS works but encrypted DNS fails, or resolver failover loops indefinitely.

Check: resolver order, failure classification, cache scope, detour routing, catastrophic error handling, timeout budgets, and telemetry.

### Protocol strategy drift

Symptom: a packet mutation is exposed in UI but not in Rust config, or diagnostics recommends a strategy that runtime cannot execute.

Check: AppSettings/protobuf → Kotlin models → JSON codec → Rust RuntimeConfig → candidate specs → diagnostics fixtures.

### Native/Kotlin authority split

Symptom: Kotlin reclassifies network failures differently from Rust, or UI state claims success without native evidence.

Check: native classification is authoritative; Kotlin maps verdicts and preserves typed evidence.

### Root-helper regression

Symptom: feature only works on rooted devices or crashes when root is absent.

Check: all root-only operations are behind capability gates, opt-in settings, and graceful fallback.

## Experiment design

Use A/B packet-path experiments, not broad rewrites:

1. define the exact blocked/degraded network shape;
2. build one minimal candidate;
3. collect timing, handshake, DNS, and verdict evidence;
4. compare against transparent baseline and existing strategy winner;
5. preserve a kill switch and rollback.

## Verification ladder

1. Static architecture check.
2. Unit/golden contract test.
3. Native packet-smoke test.
4. Emulator VPN/proxy scenario.
5. Real device on Wi-Fi and cellular.
6. Lab with induced DNS tamper, UDP block, MTU clamp, TCP reset, and handover.
