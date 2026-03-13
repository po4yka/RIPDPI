# P1 DPI Bypass Improvements Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement three P1 improvements to RIPDPI's DPI bypass: (1) QUIC fake packet size cap ≤1000 bytes to avoid Russian TSPU 1001-byte threshold, (2) QUIC→TCP fallback cache so repeated QUIC failures cause the proxy to bypass QUIC desync, and (3) `ThrottlingFreeze` failure detection for the 2025 Russian ISP TCP freeze pattern.

**Architecture:**
- Task 1 adds `quic_max_initial_bytes: Option<u16>` to `DesyncGroup` in `ciadpi-config` and enforces it in `udp_fake_payload()` in `ciadpi-desync`.
- Task 2 adds `quic_failed_ips` cache to `RuntimeCache`, tracks QUIC flow type in `UdpFlowActivationState`, records failures in `expire_udp_flows`, and suppresses QUIC desync in `udp_associate_loop` for suppressed IPs.
- Task 3 adds `ThrottlingFreeze { bytes_before_freeze: u64 }` to `FailureClass`, modifies `copy_inbound_half` to arm an inactivity timeout after a byte threshold, and emits telemetry in `relay_streams`.

**Tech Stack:** Rust stable, existing ciadpi-config/ciadpi-desync/ripdpi-failure-classifier/ripdpi-runtime crates

---

### Task 1: QUIC 1001-byte size cap (`RuTspuSafe` profile)

Russian TSPU drops UDP/443 packets >1001 bytes containing QUIC v1 at offset 1.
`build_realistic_quic_initial()` pads to `QUIC_FAKE_INITIAL_TARGET_LEN = 1200` bytes — this trips the TSPU filter.
Fix: when the built fake exceeds a per-group cap, fall back to `default_fake_quic_compat()` (620 bytes).

**Files:**
- Modify: `native/rust/third_party/byedpi/crates/ciadpi-config/src/lib.rs` (add field to `DesyncGroup`)
- Modify: `native/rust/third_party/byedpi/crates/ciadpi-desync/src/lib.rs` (enforce cap in `udp_fake_payload`)
- Test: `native/rust/third_party/byedpi/crates/ciadpi-desync/src/lib.rs` (add tests in `#[cfg(test)]` block)

#### Step 1: Write failing tests

Add to `#[cfg(test)]` block in `ciadpi-desync/src/lib.rs` (after the existing test at ~line 1175):

```rust
#[test]
fn udp_fake_payload_respects_quic_max_initial_bytes() {
    use ciadpi_config::{DesyncGroup, ActivationContext, AdaptivePlannerHints};
    use ciadpi_packets::{build_realistic_quic_initial, QUIC_V1_VERSION, default_fake_quic_compat};

    let packet = rust_packet_seeds::quic_initial_v1();
    let mut group = DesyncGroup::new(0);
    group.quic_fake_profile = QuicFakeProfile::RealisticInitial;
    // Cap at 999 bytes — realistic initial (~1200 bytes) should fall back to compat (~620 bytes)
    group.quic_max_initial_bytes = Some(999);

    let ctx = ActivationContext::default();
    let result = udp_fake_payload(&group, &packet, ctx);
    assert!(
        result.len() <= 999,
        "expected compat fallback ≤999 bytes, got {}",
        result.len()
    );
    // Compat default is ~620 bytes
    assert_eq!(result, default_fake_quic_compat());
}

#[test]
fn udp_fake_payload_allows_realistic_when_no_cap() {
    use ciadpi_config::{DesyncGroup, ActivationContext};

    let packet = rust_packet_seeds::quic_initial_v1();
    let mut group = DesyncGroup::new(0);
    group.quic_fake_profile = QuicFakeProfile::RealisticInitial;
    group.quic_max_initial_bytes = None; // no cap

    let ctx = ActivationContext::default();
    let result = udp_fake_payload(&group, &packet, ctx);
    // Realistic initial is padded to 1200 bytes
    assert!(result.len() >= 1100, "expected realistic initial, got {} bytes", result.len());
}
```

#### Step 2: Run tests to verify they fail

```bash
cd native/rust && cargo test -p ciadpi-desync udp_fake_payload_respects_quic_max_initial_bytes udp_fake_payload_allows_realistic_when_no_cap 2>&1 | tail -20
```

Expected: `error[E0609]: no field 'quic_max_initial_bytes' on type 'DesyncGroup'`

#### Step 3: Add `quic_max_initial_bytes` field to `DesyncGroup`

In `ciadpi-config/src/lib.rs`, add the field to the `DesyncGroup` struct (after `quic_fake_host` at line ~307):

```rust
pub quic_max_initial_bytes: Option<u16>,
```

In `DesyncGroup::new()` (line ~348), add the default:

```rust
quic_max_initial_bytes: None,
```

#### Step 4: Enforce cap in `udp_fake_payload`

In `ciadpi-desync/src/lib.rs`, update the `RealisticInitial` arm in `udp_fake_payload()` (lines 1119-1122):

```rust
QuicFakeProfile::RealisticInitial => {
    if let Some(fake) = build_realistic_quic_initial(quic.version, group.quic_fake_host.as_deref()) {
        let cap = group.quic_max_initial_bytes.map(|c| c as usize);
        if cap.is_none_or(|c| fake.len() <= c) {
            return fake;
        }
        return default_fake_quic_compat();
    }
}
```

#### Step 5: Run tests to verify they pass

```bash
cd native/rust && cargo test -p ciadpi-desync udp_fake_payload_respects_quic_max_initial_bytes udp_fake_payload_allows_realistic_when_no_cap 2>&1 | tail -10
```

Expected: `test result: ok. 2 passed`

#### Step 6: Run full ciadpi-desync test suite

```bash
cd native/rust && cargo test -p ciadpi-desync 2>&1 | tail -10
```

Expected: all tests pass, no regressions.

#### Step 7: Commit

```bash
cd /Users/po4yka/GitRep/RIPDPI
git add native/rust/third_party/byedpi/crates/ciadpi-config/src/lib.rs \
        native/rust/third_party/byedpi/crates/ciadpi-desync/src/lib.rs
git commit -m "feat(desync): add quic_max_initial_bytes cap to DesyncGroup

When RealisticInitial QUIC fake exceeds the per-group byte cap,
fall back to CompatDefault (620 bytes). Russian TSPU drops UDP/443
packets >1001 bytes with QUIC v1; RuTspuSafe configs can now set
quic_max_initial_bytes: Some(1000) to stay under the threshold."
```

---

### Task 2: QUIC→TCP fallback via per-IP failure cache

When QUIC is silently dropped for an IP, record the failure and suppress QUIC desync for that IP for 5 minutes. The browser's QUIC timeout then causes it to fall back to TCP/443 without the proxy interfering with a now-useless QUIC fake burst.

**Files:**
- Modify: `native/rust/crates/ripdpi-runtime/src/runtime_policy.rs` (add cache fields + methods)
- Modify: `native/rust/crates/ripdpi-runtime/src/runtime.rs` (track QUIC flows, record failures, suppress in `udp_associate_loop`)
- Test: `native/rust/crates/ripdpi-runtime/src/runtime_policy.rs` (add tests)

#### Step 1: Write failing tests

Add to `#[cfg(test)]` in `runtime_policy.rs`:

```rust
#[test]
fn quic_failure_cache_suppresses_after_record() {
    use std::net::{IpAddr, Ipv4Addr};
    let mut cache = RuntimeCache::default();
    let ip = IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4));
    assert!(!cache.is_quic_suppressed(ip));
    cache.record_quic_failure(ip);
    assert!(cache.is_quic_suppressed(ip));
}

#[test]
fn quic_failure_cache_expires_after_ttl() {
    use std::net::{IpAddr, Ipv4Addr};
    use std::time::{Duration, Instant};
    let mut cache = RuntimeCache::default();
    let ip = IpAddr::V4(Ipv4Addr::new(5, 6, 7, 8));
    cache.record_quic_failure_at(ip, Instant::now() - Duration::from_secs(301));
    assert!(!cache.is_quic_suppressed(ip), "entry should have expired");
}
```

#### Step 2: Run tests to verify they fail

```bash
cd native/rust && cargo test -p ripdpi-runtime quic_failure_cache 2>&1 | tail -10
```

Expected: `error[E0599]: no method named 'record_quic_failure' found`

#### Step 3: Add QUIC failure cache to `RuntimeCache`

In `runtime_policy.rs`, add imports and constant at the top:

```rust
use std::collections::HashMap;
use std::net::IpAddr;
use std::time::{Duration, Instant};

const QUIC_SUPPRESSION_TTL: Duration = Duration::from_secs(300);
```

Add field to `RuntimeCache` struct (after `autolearn_events`):

```rust
quic_failed_ips: HashMap<IpAddr, Instant>,
```

Add methods to `impl RuntimeCache`:

```rust
pub fn record_quic_failure(&mut self, ip: IpAddr) {
    self.record_quic_failure_at(ip, Instant::now());
}

pub fn record_quic_failure_at(&mut self, ip: IpAddr, at: Instant) {
    self.quic_failed_ips.insert(ip, at);
}

pub fn is_quic_suppressed(&self, ip: IpAddr) -> bool {
    self.quic_failed_ips
        .get(&ip)
        .is_some_and(|recorded| recorded.elapsed() < QUIC_SUPPRESSION_TTL)
}
```

#### Step 4: Add `is_quic` flag to `UdpFlowActivationState`

In `runtime.rs`, find the `UdpFlowActivationState` struct (line ~66):

```rust
struct UdpFlowActivationState {
    session: SessionState,
    last_used: Instant,
    route: ConnectionRoute,
    host: Option<String>,
    payload: Vec<u8>,
    awaiting_response: bool,
    is_quic: bool,  // <-- add this
}
```

When the entry is created/updated in `udp_associate_loop` (line ~1438), set `is_quic`:

```rust
let entry = flow_state.entry((sender, target)).or_insert_with(|| UdpFlowActivationState {
    session: SessionState::default(),
    last_used: now,
    route: route.clone(),
    host: host.clone(),
    payload: payload.to_vec(),
    awaiting_response: true,
    is_quic: parse_quic_initial(payload).is_some(),  // <-- add
});
// Also update on re-entry:
entry.is_quic = parse_quic_initial(payload).is_some();
```

#### Step 5: Record QUIC failures in `expire_udp_flows`

In `expire_udp_flows` (line ~1511), after calling `note_adaptive_udp_failure` (line ~1524):

```rust
if entry.is_quic {
    if let Ok(mut cache) = state.cache.lock() {
        cache.record_quic_failure(target.ip());
    }
}
```

#### Step 6: Suppress QUIC desync for suppressed IPs

In `udp_associate_loop`, after `extract_host_info` at line 1402:

```rust
let host_info = extract_host_info(&state.config, payload);
// Suppress QUIC desync if this IP has recent failures
let host_info = if host_info.as_ref().map(|h| h.source) == Some(HostSource::Quic) {
    let suppressed = state.cache.lock().is_ok_and(|c| c.is_quic_suppressed(target.ip()));
    if suppressed { None } else { host_info }
} else {
    host_info
};
```

#### Step 7: Run tests to verify they pass

```bash
cd native/rust && cargo test -p ripdpi-runtime quic_failure_cache 2>&1 | tail -10
```

Expected: `test result: ok. 2 passed`

#### Step 8: Run full ripdpi-runtime test suite

```bash
cd native/rust && cargo test -p ripdpi-runtime 2>&1 | tail -10
```

Expected: all tests pass.

#### Step 9: Commit

```bash
cd /Users/po4yka/GitRep/RIPDPI
git add native/rust/crates/ripdpi-runtime/src/runtime_policy.rs \
        native/rust/crates/ripdpi-runtime/src/runtime.rs
git commit -m "feat(runtime): add QUIC→TCP fallback via per-IP failure cache

When a UDP QUIC flow expires without receiving any response,
record the destination IP in RuntimeCache.quic_failed_ips.
Subsequent QUIC Initial packets to that IP skip QUIC desync
for 5 minutes (QUIC_SUPPRESSION_TTL), allowing the browser's
built-in QUIC timeout to trigger TCP fallback cleanly."
```

---

### Task 3: `ThrottlingFreeze` failure detection

Russian ISPs (2025) freeze TLS 1.3 connections to foreign IPs after ~15-20 KB received — no RST, just silence. Currently `copy_inbound_half` reads with `set_read_timeout(None)` forever. Adding an inactivity timeout after a byte threshold lets the runtime detect and classify this pattern.

**Files:**
- Modify: `native/rust/crates/ripdpi-failure-classifier/src/lib.rs` (add `ThrottlingFreeze` variant)
- Modify: `native/rust/crates/ripdpi-runtime/src/runtime.rs` (modify `copy_inbound_half` + `relay_streams`)
- Test: `native/rust/crates/ripdpi-failure-classifier/src/lib.rs` (add tests)
- Test: `native/rust/crates/ripdpi-runtime/src/runtime.rs` (add tests for `copy_inbound_half`)

#### Step 1: Write failing test for `FailureClass`

Add to `ripdpi-failure-classifier/src/lib.rs` (in `#[cfg(test)]` block if present, else create one):

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn throttling_freeze_as_str_is_stable() {
        assert_eq!(FailureClass::ThrottlingFreeze { bytes_before_freeze: 18_000 }.as_str(), "throttling_freeze");
    }

    #[test]
    fn throttling_freeze_serializes_with_bytes_field() {
        let class = FailureClass::ThrottlingFreeze { bytes_before_freeze: 15_000 };
        let json = serde_json::to_string(&class).unwrap();
        assert!(json.contains("throttling_freeze"), "expected snake_case tag in {json}");
    }
}
```

#### Step 2: Run tests to verify they fail

```bash
cd native/rust && cargo test -p ripdpi-failure-classifier throttling_freeze 2>&1 | tail -10
```

Expected: `error[E0599]: no variant 'ThrottlingFreeze' found`

#### Step 3: Add `ThrottlingFreeze` variant to `FailureClass`

In `ripdpi-failure-classifier/src/lib.rs`, add the variant (after `ConnectFailure`):

```rust
ThrottlingFreeze {
    bytes_before_freeze: u64,
},
```

Add arm to `as_str`:

```rust
Self::ThrottlingFreeze { .. } => "throttling_freeze",
```

Note: `FailureClass` derives `Copy`. `ThrottlingFreeze { bytes_before_freeze: u64 }` contains only `Copy` types, so the derive still works. However, `FailureClass` is also used in `match` arms throughout the codebase — add `FailureClass::ThrottlingFreeze { .. } => 0` to any exhaustive match that currently lacks it (the compiler will point these out).

#### Step 4: Fix any broken match arms

Run:

```bash
cd native/rust && cargo check 2>&1 | grep "non-exhaustive"
```

For each reported location, add:

```rust
FailureClass::ThrottlingFreeze { .. } => /* same as Unknown or appropriate default */,
```

In `runtime.rs` line ~767 (`FailureClass::QuicBreakage => 0`), add after it:

```rust
FailureClass::ThrottlingFreeze { .. } => 0,
```

#### Step 5: Run failure-classifier tests to verify they pass

```bash
cd native/rust && cargo test -p ripdpi-failure-classifier throttling_freeze 2>&1 | tail -10
```

Expected: `test result: ok. 2 passed`

#### Step 6: Modify `copy_inbound_half` to detect freeze

Replace the current `copy_inbound_half` in `runtime.rs` (lines 2197-2216) with:

```rust
const FREEZE_DETECT_THRESHOLD_BYTES: u64 = 10_000;
const FREEZE_INACTIVITY_TIMEOUT: Duration = Duration::from_secs(30);

/// Returns `(io_result, bytes_received, is_freeze)`.
/// `is_freeze` is true when the connection went silent after
/// FREEZE_DETECT_THRESHOLD_BYTES — indicating ISP-level throttle freeze.
fn copy_inbound_half(
    mut reader: TcpStream,
    mut writer: TcpStream,
    session: Arc<Mutex<SessionState>>,
) -> (io::Result<()>, u64, bool) {
    let mut buffer = [0u8; 16_384];
    let mut total_bytes: u64 = 0;
    let mut freeze_armed = false;
    loop {
        match reader.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => {
                total_bytes += n as u64;
                if let Ok(mut state) = session.lock() {
                    state.observe_inbound(&buffer[..n]);
                }
                if let Err(e) = writer.write_all(&buffer[..n]) {
                    let _ = writer.shutdown(Shutdown::Write);
                    let _ = reader.shutdown(Shutdown::Read);
                    return (Err(e), total_bytes, false);
                }
                if !freeze_armed && total_bytes >= FREEZE_DETECT_THRESHOLD_BYTES {
                    let _ = reader.set_read_timeout(Some(FREEZE_INACTIVITY_TIMEOUT));
                    freeze_armed = true;
                }
            }
            Err(ref e) if freeze_armed && matches!(e.kind(), io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock) => {
                let _ = writer.shutdown(Shutdown::Write);
                let _ = reader.shutdown(Shutdown::Read);
                return (Ok(()), total_bytes, true);
            }
            Err(e) => {
                let _ = writer.shutdown(Shutdown::Write);
                let _ = reader.shutdown(Shutdown::Read);
                return (Err(e), total_bytes, false);
            }
        }
    }
    let _ = writer.shutdown(Shutdown::Write);
    let _ = reader.shutdown(Shutdown::Read);
    (Ok(()), total_bytes, false)
}
```

#### Step 7: Update `relay_streams` to consume new return type

In `relay_streams` (lines 1865-1885), the `down` thread spawns `copy_inbound_half`. Update:

1. Capture the upstream peer addr before moving into threads (add before `let down = ...`):

```rust
let upstream_peer = upstream.peer_addr().ok();
```

2. Update the join and result handling (replace lines 1876-1884):

```rust
let up_result = up.join().map_err(|_| io::Error::other("upstream thread panicked"))?;
let down_result = down.join().map_err(|_| io::Error::other("downstream thread panicked"))?;
let (down_io_result, inbound_bytes, is_freeze) = down_result;

if is_freeze {
    if let (Some(telemetry), Some(peer)) = (&state.telemetry, upstream_peer) {
        let failure = ClassifiedFailure::new(
            FailureClass::ThrottlingFreeze { bytes_before_freeze: inbound_bytes },
            FailureStage::FirstResponse,
            FailureAction::RetryWithMatchingGroup,
            format!("connection silent after {} bytes", inbound_bytes),
        )
        .with_tag("bytes_before_freeze", inbound_bytes.to_string());
        telemetry.on_failure_classified(peer, &failure, None);
    }
}

if drop_sack {
    let _ = platform::detach_drop_sack(&upstream);
}

up_result?;
down_io_result?;
session_state.lock().map_err(|_| io::Error::other("session mutex poisoned")).map(|state| state.clone())
```

Note: remove the original `if drop_sack` block that appeared after the join — it's now included above.

#### Step 8: Fix compile errors

Run:

```bash
cd native/rust && cargo check -p ripdpi-runtime 2>&1 | head -30
```

Fix any type mismatch errors. Common issues:
- The `drop_sack` block may need reordering — ensure it runs before the `?` propagation.
- `Shutdown` import check: `use std::net::Shutdown` must be present (it already is at line ~3).

#### Step 9: Run ripdpi-runtime tests

```bash
cd native/rust && cargo test -p ripdpi-runtime 2>&1 | tail -10
```

Expected: all tests pass.

#### Step 10: Commit

```bash
cd /Users/po4yka/GitRep/RIPDPI
git add native/rust/crates/ripdpi-failure-classifier/src/lib.rs \
        native/rust/crates/ripdpi-runtime/src/runtime.rs
git commit -m "feat(classifier,runtime): add ThrottlingFreeze detection for ISP TCP freeze

Russian ISPs (2025) silently freeze TLS 1.3 connections after ~15-20 KB.
copy_inbound_half now arms a 30s inactivity timeout after 10 KB and
returns is_freeze=true on trigger. relay_streams emits ThrottlingFreeze
ClassifiedFailure via telemetry, enabling audit diagnostics to surface
this pattern separately from SilentDrop."
```

---

## Final Verification

After all three tasks:

```bash
cd native/rust && cargo test -p ciadpi-desync -p ripdpi-failure-classifier -p ripdpi-runtime 2>&1 | tail -15
```

Expected: all test suites pass with no regressions.
