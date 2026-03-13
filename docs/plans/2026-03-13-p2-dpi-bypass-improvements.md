# P2 DPI Bypass Improvements Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement two P2 improvements: (1) Russia-specific ISP presets so users can pick a named profile instead of hand-tuning JSON, and (2) a passive DPI RST injection filter in the TUN layer that drops packets with IP ID 0x0000/0x0001 — the fingerprint of injected resets from legacy passive-DPI ISPs like MGTS.

**Architecture:**
- Task 1 adds `strategy_preset: Option<String>` to `ProxyUiConfig` and a new `presets.rs` module in `ripdpi-proxy-config` that applies known-good field values before `runtime_config_from_ui` runs. Four presets: `russia_rostelecom`, `russia_mgts`, `russia_mts_mobile`, `byedpi_default`.
- Task 2 adds `filter_injected_resets: bool` to `MiscConfig` (hs5t-config), threads it through `TunnelConfigPayload` → `config_from_payload` → `io_loop_task`, and in the TUN read loop drops packets where TCP RST is set AND IP ID is 0x0000 or 0x0001.

**Tech Stack:** Rust stable, serde/serde_json, existing `ripdpi-proxy-config` and `hs5t-core`/`hs5t-config`/`hs5t-android` crates

---

### Task 1: Russia-specific ISP presets

**Files:**
- Create: `native/rust/crates/ripdpi-proxy-config/src/presets.rs`
- Modify: `native/rust/crates/ripdpi-proxy-config/src/lib.rs`

#### Step 1: Write failing tests

Add to the `#[cfg(test)]` block in `ripdpi-proxy-config/src/lib.rs` (after line 836, end of file):

```rust
#[test]
fn known_preset_applies_without_error() {
    let base = minimal_ui_config();
    let mut cfg = base.clone();
    crate::presets::apply_preset("russia_rostelecom", &mut cfg).unwrap();
    assert!(cfg.desync_https, "rostelecom preset should enable HTTPS desync");
    assert!(cfg.adaptive_fake_ttl_enabled, "rostelecom preset should enable adaptive TTL");
}

#[test]
fn unknown_preset_returns_error() {
    let mut cfg = minimal_ui_config();
    let result = crate::presets::apply_preset("not_a_real_preset", &mut cfg);
    assert!(result.is_err());
}

#[test]
fn russia_mgts_preset_produces_valid_runtime_config() {
    let mut cfg = minimal_ui_config();
    crate::presets::apply_preset("russia_mgts", &mut cfg).unwrap();
    // Must not blow up during full config build
    runtime_config_from_ui(cfg).unwrap();
}

#[test]
fn preset_field_in_ui_config_round_trips_json() {
    let mut cfg = minimal_ui_config();
    cfg.strategy_preset = Some("byedpi_default".to_string());
    let json = serde_json::to_string(&cfg).unwrap();
    let decoded: ProxyUiConfig = serde_json::from_str(&json).unwrap();
    assert_eq!(decoded.strategy_preset.as_deref(), Some("byedpi_default"));
}
```

You also need a `minimal_ui_config()` helper. Check if one already exists in the test module. If not, add:

```rust
fn minimal_ui_config() -> ProxyUiConfig {
    serde_json::from_str(r#"{
        "ip": "127.0.0.1",
        "port": 1080,
        "maxConnections": 512,
        "bufferSize": 65536,
        "defaultTtl": 64,
        "customTtl": false,
        "noDomain": false,
        "desyncHttp": false,
        "desyncHttps": false,
        "desyncUdp": false,
        "desyncMethod": "split",
        "fakeTtl": 8,
        "fakeSni": "",
        "oobChar": 97,
        "hostMixedCase": false,
        "domainMixedCase": false,
        "hostRemoveSpaces": false,
        "tlsRecordSplit": false,
        "hostsMode": "disable",
        "hosts": null,
        "tcpFastOpen": false,
        "udpFakeCount": 0,
        "dropSack": false,
        "fakeOffset": 0
    }"#).unwrap()
}
```

#### Step 2: Run tests to verify they fail

```bash
cd native/rust && cargo test -p ripdpi-proxy-config known_preset_applies_without_error unknown_preset_returns_error russia_mgts_preset_produces_valid_runtime_config preset_field_in_ui_config_round_trips_json 2>&1 | tail -15
```

Expected: `error[E0433]: failed to resolve: use of undeclared crate or module 'presets'`

#### Step 3: Add `strategy_preset` field to `ProxyUiConfig`

In `ripdpi-proxy-config/src/lib.rs`, add after `network_scope_key` field (line ~232):

```rust
#[serde(default)]
pub strategy_preset: Option<String>,
```

#### Step 4: Create `presets.rs`

Create `native/rust/crates/ripdpi-proxy-config/src/presets.rs`:

```rust
//! Named ISP presets for Russian DPI bypass.
//!
//! Each preset applies a known-good set of defaults to a `ProxyUiConfig`.
//! Fields already customised by the user (non-default values from Kotlin) are
//! intentionally overwritten — the preset is the authority.

use crate::{ProxyConfigError, ProxyUiConfig, FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT};

/// Apply the named preset to `config`.
///
/// Returns `Err` if `preset_id` is not recognised.
pub fn apply_preset(preset_id: &str, config: &mut ProxyUiConfig) -> Result<(), ProxyConfigError> {
    match preset_id {
        "russia_rostelecom" => apply_russia_rostelecom(config),
        "russia_mgts" => apply_russia_mgts(config),
        "russia_mts_mobile" => apply_russia_mts_mobile(config),
        "byedpi_default" => apply_byedpi_default(config),
        other => Err(ProxyConfigError::InvalidConfig(format!("Unknown strategyPreset: {other}"))),
    }
}

/// Rostelecom / TTK — inline active DPI (ecDPI), injects TCP RST into server
/// response. Needs packet desync with fake TTL so the fake reaches the DPI but
/// not the real server.
fn apply_russia_rostelecom(c: &mut ProxyUiConfig) -> Result<(), ProxyConfigError> {
    c.desync_https = true;
    c.desync_http = true;
    c.desync_method = "fake".to_string();
    c.split_at_host = true;
    c.split_position = 0;
    c.adaptive_fake_ttl_enabled = true;
    c.fake_ttl = 8;
    c.tls_fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.desync_udp = true;
    c.quic_fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.quic_initial_mode = Some("route_and_cache".to_string());
    Ok(())
}

/// MGTS (Moscow city network) — passive DPI, injects TCP RST packets with
/// IP ID 0x0000/0x0001. A simple split at the SNI breaks the pattern match
/// without needing fake packets.
fn apply_russia_mgts(c: &mut ProxyUiConfig) -> Result<(), ProxyConfigError> {
    c.desync_https = true;
    c.desync_http = false;
    c.desync_method = "split".to_string();
    c.split_at_host = true;
    c.split_position = 0;
    c.adaptive_fake_ttl_enabled = false;
    Ok(())
}

/// MTS/Tele2/Beeline mobile — whitelist mode default-deny; Cloudflare 1.1.1.1
/// is blocked. Focus on QUIC compat to avoid >1001-byte QUIC fake drop, and
/// UDP desync for QUIC. DNS is handled by the monitor layer (already using
/// dns.google after the P0 fix).
fn apply_russia_mts_mobile(c: &mut ProxyUiConfig) -> Result<(), ProxyConfigError> {
    c.desync_https = true;
    c.desync_http = true;
    c.desync_method = "split".to_string();
    c.split_at_host = true;
    c.split_position = 0;
    c.adaptive_fake_ttl_enabled = false;
    c.desync_udp = true;
    c.quic_fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.quic_initial_mode = Some("route_and_cache".to_string());
    Ok(())
}

/// ByeDPI default — broad Russian ISP compatibility using disorder (more
/// reliable than split on modern TSPU) with adaptive fake TTL enabled.
fn apply_byedpi_default(c: &mut ProxyUiConfig) -> Result<(), ProxyConfigError> {
    c.desync_https = true;
    c.desync_http = true;
    c.desync_method = "disorder".to_string();
    c.split_at_host = true;
    c.split_position = 0;
    c.adaptive_fake_ttl_enabled = true;
    c.fake_ttl = 8;
    c.tls_fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.desync_udp = true;
    c.quic_fake_profile = FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT.to_string();
    c.quic_initial_mode = Some("route_and_cache".to_string());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base() -> ProxyUiConfig {
        serde_json::from_str(r#"{
            "ip": "127.0.0.1", "port": 1080, "maxConnections": 512,
            "bufferSize": 65536, "defaultTtl": 64, "customTtl": false,
            "noDomain": false, "desyncHttp": false, "desyncHttps": false,
            "desyncUdp": false, "desyncMethod": "split", "fakeTtl": 0,
            "fakeSni": "", "oobChar": 97, "hostMixedCase": false,
            "domainMixedCase": false, "hostRemoveSpaces": false,
            "tlsRecordSplit": false, "hostsMode": "disable", "hosts": null,
            "tcpFastOpen": false, "udpFakeCount": 0, "dropSack": false,
            "fakeOffset": 0
        }"#).unwrap()
    }

    #[test]
    fn rostelecom_enables_adaptive_ttl() {
        let mut c = base();
        apply_russia_rostelecom(&mut c).unwrap();
        assert!(c.adaptive_fake_ttl_enabled);
        assert_eq!(c.desync_method, "fake");
    }

    #[test]
    fn mgts_does_not_enable_fake() {
        let mut c = base();
        apply_russia_mgts(&mut c).unwrap();
        assert_eq!(c.desync_method, "split");
        assert!(!c.adaptive_fake_ttl_enabled);
    }

    #[test]
    fn mts_mobile_enables_quic_compat() {
        let mut c = base();
        apply_russia_mts_mobile(&mut c).unwrap();
        assert_eq!(c.quic_fake_profile, FAKE_PAYLOAD_PROFILE_COMPAT_DEFAULT);
        assert!(c.desync_udp);
    }

    #[test]
    fn all_presets_produce_valid_runtime_config() {
        use crate::runtime_config_from_ui;
        for preset in &["russia_rostelecom", "russia_mgts", "russia_mts_mobile", "byedpi_default"] {
            let mut c = base();
            apply_preset(preset, &mut c).unwrap();
            runtime_config_from_ui(c).unwrap_or_else(|e| panic!("preset {preset} failed validation: {e}"));
        }
    }
}
```

#### Step 5: Register `presets` module in `lib.rs`

In `ripdpi-proxy-config/src/lib.rs`, add after the `use` imports (before line 17):

```rust
pub mod presets;
```

#### Step 6: Apply preset early in `runtime_config_from_ui`

In `runtime_config_from_ui` (line ~399), add after the function signature and before any logic:

```rust
pub fn runtime_config_from_ui(mut payload: ProxyUiConfig) -> Result<RuntimeConfig, ProxyConfigError> {
    if let Some(preset_id) = payload.strategy_preset.clone() {
        presets::apply_preset(&preset_id, &mut payload)?;
    }
    // ... rest of existing function unchanged
```

Note: The function signature changes from `payload: ProxyUiConfig` to `mut payload: ProxyUiConfig` — check if it was already `mut`.

#### Step 7: Run tests to verify they pass

```bash
cd native/rust && cargo test -p ripdpi-proxy-config known_preset_applies_without_error unknown_preset_returns_error russia_mgts_preset_produces_valid_runtime_config preset_field_in_ui_config_round_trips_json 2>&1 | tail -10
```

Expected: `test result: ok. 4 passed`

#### Step 8: Run full ripdpi-proxy-config test suite

```bash
cd native/rust && cargo test -p ripdpi-proxy-config 2>&1 | tail -10
```

Expected: all tests pass, no regressions.

#### Step 9: Commit

```bash
cd /Users/po4yka/GitRep/RIPDPI
git add native/rust/crates/ripdpi-proxy-config/src/lib.rs \
        native/rust/crates/ripdpi-proxy-config/src/presets.rs
git commit -m "feat(proxy-config): add Russia-specific ISP strategy presets

Add strategy_preset field to ProxyUiConfig. When set, apply_preset()
in the new presets module overrides config fields with known-good
values before runtime config is built. Presets:
  - russia_rostelecom: fake+adaptive TTL (inline active DPI)
  - russia_mgts: split only (passive RST injection)
  - russia_mts_mobile: split+QUIC compat (mobile whitelist mode)
  - byedpi_default: disorder+adaptive TTL (broad compatibility)"
```

---

### Task 2: Passive DPI RST injection filter in TUN layer

Passive DPI ISPs (MGTS and others) inject spoofed TCP RST packets into the TUN device. These injected RSTs are distinguishable from real RSTs by their IP ID: always 0x0000 or 0x0001. Dropping them in the TUN layer prevents them from entering the smoltcp stack.

**Files:**
- Modify: `native/rust/third_party/hev-socks5-tunnel/crates/hs5t-core/src/io_loop.rs`
- Modify: `native/rust/third_party/hev-socks5-tunnel/crates/hs5t-config/src/lib.rs`
- Modify: `native/rust/crates/hs5t-android/src/lib.rs`

#### Step 1: Write failing tests

Add to `io_loop.rs` (at the bottom of the file, in a `#[cfg(test)]` block or appending to existing):

```rust
#[cfg(test)]
mod tests {
    use super::*;

    fn ipv4_tcp_rst(ip_id: u16) -> Vec<u8> {
        let mut pkt = vec![0u8; 40];
        pkt[0] = 0x45;                              // IPv4, IHL=5
        pkt[2] = 0;
        pkt[3] = 40;                                // total length
        pkt[4] = (ip_id >> 8) as u8;               // IP ID high
        pkt[5] = (ip_id & 0xFF) as u8;             // IP ID low
        pkt[8] = 64;                                // TTL
        pkt[9] = 6;                                 // TCP
        pkt[12..16].copy_from_slice(&[10, 0, 0, 1]); // src
        pkt[16..20].copy_from_slice(&[10, 0, 0, 2]); // dst
        pkt[32] = 0x50;                             // data offset = 5 (20 bytes)
        pkt[33] = 0x04;                             // RST flag
        pkt
    }

    fn ipv4_tcp_syn() -> Vec<u8> {
        let mut pkt = vec![0u8; 40];
        pkt[0] = 0x45;
        pkt[3] = 40;
        pkt[9] = 6;
        pkt[12..16].copy_from_slice(&[10, 0, 0, 1]);
        pkt[16..20].copy_from_slice(&[10, 0, 0, 2]);
        pkt[32] = 0x50;
        pkt[33] = 0x02; // SYN
        pkt
    }

    #[test]
    fn injected_rst_with_ip_id_zero_is_detected() {
        assert!(is_injected_rst(&ipv4_tcp_rst(0x0000)));
    }

    #[test]
    fn injected_rst_with_ip_id_one_is_detected() {
        assert!(is_injected_rst(&ipv4_tcp_rst(0x0001)));
    }

    #[test]
    fn real_rst_with_normal_ip_id_is_not_injected() {
        assert!(!is_injected_rst(&ipv4_tcp_rst(0x1234)));
    }

    #[test]
    fn tcp_syn_is_not_injected_rst() {
        assert!(!is_injected_rst(&ipv4_tcp_syn()));
    }

    #[test]
    fn short_packet_is_not_injected_rst() {
        assert!(!is_injected_rst(&[0x45, 0x00, 0x00]));
    }
}
```

#### Step 2: Run tests to verify they fail

```bash
cd native/rust && cargo test -p hs5t-core injected_rst 2>&1 | tail -10
```

Expected: `error[E0425]: cannot find function 'is_injected_rst'`

#### Step 3: Add `is_injected_rst` to `io_loop.rs`

Add after the `is_tcp_syn` function (after line ~106):

```rust
/// Return `true` if the raw IPv4 packet looks like an injected TCP RST.
///
/// Passive DPI boxes (e.g. Russian SORM/MGTS) inject spoofed RST packets
/// with IP ID 0x0000 or 0x0001, which is never used by real endpoints.
fn is_injected_rst(pkt: &[u8]) -> bool {
    // Need at least IPv4 header (20) + TCP flags offset (13 bytes into TCP header)
    if pkt.len() < 20 || pkt[0] >> 4 != 4 || pkt[9] != 6 {
        return false;
    }
    let ihl = ((pkt[0] & 0x0f) as usize) * 4;
    if pkt.len() < ihl + 14 {
        return false;
    }
    // TCP RST flag (bit 2 in flags byte)
    if pkt[ihl + 13] & 0x04 == 0 {
        return false;
    }
    // Injected RSTs have IP ID 0x0000 or 0x0001
    let ip_id = u16::from_be_bytes([pkt[4], pkt[5]]);
    ip_id <= 1
}
```

#### Step 4: Run tests to verify they pass

```bash
cd native/rust && cargo test -p hs5t-core injected_rst 2>&1 | tail -10
```

Expected: `test result: ok. 5 passed`

#### Step 5: Add `filter_injected_resets` to `MiscConfig`

In `hs5t-config/src/lib.rs`, add field to `MiscConfig` struct (after `limit_nofile`):

```rust
#[serde(default)]
pub filter_injected_resets: bool,
```

Add to `MiscConfig::default()` (after `limit_nofile: default_limit_nofile()`):

```rust
filter_injected_resets: false,
```

#### Step 6: Thread `filter_injected_resets` through `hs5t-android`

In `hs5t-android/src/lib.rs`, add field to `TunnelConfigPayload` (after `limit_nofile`):

```rust
#[serde(default)]
filter_injected_resets: Option<bool>,
```

In `config_from_payload` (after the `limit_nofile` block around line 640):

```rust
if let Some(value) = payload.filter_injected_resets {
    misc.filter_injected_resets = value;
}
```

#### Step 7: Apply filter in `io_loop_task`

In `io_loop.rs`, extract the flag just after `max_sessions` (line ~503):

```rust
let filter_injected_resets = config.misc.filter_injected_resets;
```

In the `TcpOrOther` match arm (line ~586), add the RST drop check **before** `device.rx_queue.push_back`:

```rust
IpClass::TcpOrOther => {
    // Drop injected TCP RST packets (IP ID 0x0000/0x0001) before they
    // enter the smoltcp stack and tear down real connections.
    if filter_injected_resets && is_injected_rst(pkt) {
        // packet silently discarded
    } else {
        if is_tcp_syn(pkt) {
            if let Some(dst_port) = tcp_dst_port(pkt) {
                // ... existing LISTEN socket creation code unchanged
            }
        }
        device.rx_queue.push_back(pkt.to_vec());
    }
}
```

Note: the entire existing `TcpOrOther` body moves inside the `else` branch. Keep all existing code unchanged — only wrap it.

#### Step 8: Check compile

```bash
cd native/rust && cargo check -p hs5t-core -p hs5t-config -p hs5t-android 2>&1 | head -20
```

Fix any type errors. Common issue: `filter_injected_resets` field may need `pub` in `MiscConfig`.

#### Step 9: Run full test suites

```bash
cd native/rust && cargo test -p hs5t-core -p hs5t-config 2>&1 | tail -10
```

Expected: all tests pass.

#### Step 10: Commit

```bash
cd /Users/po4yka/GitRep/RIPDPI
git add native/rust/third_party/hev-socks5-tunnel/crates/hs5t-core/src/io_loop.rs \
        native/rust/third_party/hev-socks5-tunnel/crates/hs5t-config/src/lib.rs \
        native/rust/crates/hs5t-android/src/lib.rs
git commit -m "feat(tun): add passive DPI RST injection filter in TUN layer

Passive DPI ISPs (MGTS, legacy SORM setups) inject spoofed TCP RST
packets with IP ID 0x0000 or 0x0001 — a fingerprint never used by
real endpoints. Add is_injected_rst() and filter_injected_resets
MiscConfig flag; when enabled, matching packets are silently dropped
before entering the smoltcp stack, preventing connection teardowns."
```

---

## Final Verification

After both tasks:

```bash
cd native/rust && cargo test -p ripdpi-proxy-config -p hs5t-core -p hs5t-config 2>&1 | tail -15
```

Expected: all test suites pass with no regressions.
