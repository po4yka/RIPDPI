# P0 Network Optimization Improvements Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement two P0 improvements to RIPDPI's network optimization: (1) server hop-count TTL auto-detection to set correct fake-packet TTL, and (2) switch default DNS from Cloudflare to Google to survive Russian mobile whitelist mode.

**Architecture:**
- Task 1 adds `note_server_ttl()` to `AdaptiveFakeTtlResolver`, a new `detected_from_observed_ttl()` helper, and wires TTL capture from the first upstream response via `recvmsg`+`IP_RECVTTL` through the platform layer into `read_first_response`.
- Task 2 replaces four string constants and one `resolver_id` literal in `ripdpi-monitor/src/lib.rs`.

**Tech Stack:** Rust stable, libc, socket2, existing platform abstraction in `platform/linux.rs` + `platform/mod.rs`

---

### Task 1: Hop-count TTL detection in AdaptiveFakeTtlResolver

**Files:**
- Modify: `native/rust/crates/ripdpi-runtime/src/adaptive_fake_ttl.rs`

#### Step 1: Write failing tests

Add to the `#[cfg(test)]` block (after line 213):

```rust
#[test]
fn detected_from_observed_ttl_computes_hop_count_correctly() {
    // 64-base: TTL=56 → hops=8 → detected=7
    assert_eq!(detected_from_observed_ttl(56), 7);
    // 128-base: TTL=117 → hops=11 → detected=10
    assert_eq!(detected_from_observed_ttl(117), 10);
    // 255-base: TTL=230 → hops=25 → detected=24
    assert_eq!(detected_from_observed_ttl(230), 24);
    // Edge: TTL=64 → hops=0 → detected=max(1, 0.saturating_sub(1)) = 1
    assert_eq!(detected_from_observed_ttl(64), 1);
    // Edge: TTL=1 → reference=64, hops=63 → detected=62
    assert_eq!(detected_from_observed_ttl(1), 62);
}

#[test]
fn note_server_ttl_seeds_candidates_from_detected_hop_count() {
    let mut resolver = AdaptiveFakeTtlResolver::default();
    let cfg = config(-1, 3, 20);
    let target = addr(443);
    let host = Some("hop.example");

    // First resolve: uses static fallback=8
    assert_eq!(resolver.resolve(0, target, host, cfg, Some(8)), 8);

    // Server responds with TTL=56: reference=64, hops=8, detected=7
    resolver.note_server_ttl(0, target, host, 56);

    // Failure clears pin → next resolve rebuilds from detected=7
    resolver.note_failure(0, target, host);
    assert_eq!(resolver.resolve(0, target, host, cfg, Some(8)), 7);
}

#[test]
fn note_server_ttl_for_unknown_key_does_not_panic() {
    let mut resolver = AdaptiveFakeTtlResolver::default();
    // Key was never resolved; should be silently ignored
    resolver.note_server_ttl(0, addr(443), Some("unknown.example"), 60);
}

#[test]
fn detected_fallback_preserved_across_config_rebuild() {
    let mut resolver = AdaptiveFakeTtlResolver::default();
    let target = addr(443);
    let host = Some("rebuild.example");

    // Initial resolve
    resolver.resolve(0, target, host, config(0, 3, 15), Some(8));
    // Record server TTL (hops=6, detected=5)
    resolver.note_server_ttl(0, target, host, 58); // 64-58=6

    // Force rebuild by changing config delta
    resolver.note_failure(0, target, host);
    let ttl = resolver.resolve(0, target, host, config(0, 3, 15), Some(8));
    assert_eq!(ttl, 5); // detected fallback used as seed
}
```

#### Step 2: Run tests to verify they fail

```bash
cd /Users/po4yka/GitRep/RIPDPI
./gradlew :core:engine:buildRustNativeLibs 2>&1 | tail -20
# Or directly with cargo if available:
# cargo test -p ripdpi-runtime 2>&1 | grep -E "FAILED|error\[" | head -20
```

Expected: compile errors for missing `detected_from_observed_ttl` and `note_server_ttl`.

#### Step 3: Implement `detected_from_observed_ttl` and update `AdaptiveFakeTtlState`

In `native/rust/crates/ripdpi-runtime/src/adaptive_fake_ttl.rs`:

1. Add `detected_fallback: Option<u8>` to `AdaptiveFakeTtlState` (after `pinned_ttl`):
```rust
#[derive(Debug, Clone, PartialEq, Eq)]
struct AdaptiveFakeTtlState {
    seed: u8,
    candidates: Vec<u8>,
    candidate_index: usize,
    pinned_ttl: Option<u8>,
    detected_fallback: Option<u8>,
}
```

2. Add the free function after `normalized_host`:
```rust
pub(crate) fn detected_from_observed_ttl(observed: u8) -> u8 {
    let reference: u8 = if observed <= 64 { 64 } else if observed <= 128 { 128 } else { 255 };
    let hops = reference.saturating_sub(observed);
    hops.saturating_sub(1).max(1)
}
```

3. Update `AdaptiveFakeTtlState::new` to accept `detected_fallback`:
```rust
fn new(config: AutoTtlConfig, fallback_ttl: Option<u8>, detected_fallback: Option<u8>) -> Self {
    let effective = detected_fallback.or(fallback_ttl);
    let candidates = candidate_order(config, effective);
    let seed = seed_ttl(config, effective);
    Self { seed, candidates, candidate_index: 0, pinned_ttl: None, detected_fallback }
}
```

4. Update `resolve` to preserve `detected_fallback` through rebuilds and use it as effective fallback:
```rust
pub fn resolve(
    &mut self,
    group_index: usize,
    dest: SocketAddr,
    host: Option<&str>,
    config: AutoTtlConfig,
    fallback_ttl: Option<u8>,
) -> u8 {
    let key = AdaptiveFakeTtlKey {
        group_index,
        target: normalized_host(host)
            .map(AdaptiveFakeTtlTarget::Host)
            .unwrap_or(AdaptiveFakeTtlTarget::Address(dest)),
    };
    let state = self.states.entry(key).or_insert_with(|| AdaptiveFakeTtlState::new(config, fallback_ttl, None));
    let effective = state.detected_fallback.or(fallback_ttl);
    if state.seed != seed_ttl(config, effective) || state.candidates != candidate_order(config, effective) {
        let detected = state.detected_fallback;
        *state = AdaptiveFakeTtlState::new(config, fallback_ttl, detected);
    }
    state.current_ttl()
}
```

5. Add `note_server_ttl` to `AdaptiveFakeTtlResolver` (after `note_failure`):
```rust
pub fn note_server_ttl(&mut self, group_index: usize, dest: SocketAddr, host: Option<&str>, observed_ttl: u8) {
    let detected = detected_from_observed_ttl(observed_ttl);
    if let Some(state) = self.states.get_mut(&AdaptiveFakeTtlKey {
        group_index,
        target: normalized_host(host)
            .map(AdaptiveFakeTtlTarget::Host)
            .unwrap_or(AdaptiveFakeTtlTarget::Address(dest)),
    }) {
        state.detected_fallback = Some(detected);
    }
    // If key not found: silently ignored; detected fallback will apply after the key is
    // created on the next resolve() call followed by a subsequent note_server_ttl().
}
```

#### Step 4: Run tests to verify they pass

```bash
cargo test -p ripdpi-runtime --test-threads=1 2>&1 | grep -E "test .* \.\.\.|FAILED|ok$" | head -30
```

Expected: all existing tests pass + 4 new tests pass.

#### Step 5: Commit

```bash
git add native/rust/crates/ripdpi-runtime/src/adaptive_fake_ttl.rs
git commit -m "feat(ttl): add server hop-count detection to AdaptiveFakeTtlResolver

Adds detected_from_observed_ttl() which converts an observed server
response TTL to a hop-count-derived fake TTL (reference - observed - 1).
note_server_ttl() stores this per-host so the next resolve() after a
failure rebuilds candidates centred on the detected hop count rather
than a static config fallback. Detected fallback is preserved through
config-change rebuilds."
```

---

### Task 2: Platform TTL reading via IP_RECVTTL + recvmsg

**Files:**
- Modify: `native/rust/crates/ripdpi-runtime/src/platform/linux.rs`
- Modify: `native/rust/crates/ripdpi-runtime/src/platform/mod.rs`

#### Step 1: Write failing tests

Add to the `#[cfg(test)]` block in `linux.rs` (after line 575):

```rust
#[test]
fn enable_recv_ttl_succeeds_on_connected_tcp_socket() {
    let (client, _server) = connected_pair();
    enable_recv_ttl(&client).expect("enable IP_RECVTTL on connected socket");
}

#[test]
fn read_chunk_with_ttl_reads_data_from_connected_pair() {
    let (client, server) = connected_pair();
    enable_recv_ttl(&client).expect("enable recv ttl");
    // Server sends data
    let handle = std::thread::spawn(move || {
        (&server).write_all(b"hello").expect("server write");
    });
    let mut buf = [0u8; 16];
    client.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    let (n, _ttl) = read_chunk_with_ttl(&client, &mut buf).expect("read with ttl");
    handle.join().unwrap();
    assert_eq!(n, 5);
    assert_eq!(&buf[..n], b"hello");
    // TTL may or may not be populated for loopback; just verify no panic
}
```

Expected: compile errors for missing functions.

#### Step 2: Implement in `linux.rs`

Add after `detach_drop_sack` (after line 241) and before `send_fake_tcp`:

```rust
pub fn enable_recv_ttl(stream: &TcpStream) -> io::Result<()> {
    let yes = 1i32;
    // SAFETY: `yes` is a valid c_int payload for IP_RECVTTL and `stream` is a
    // live TCP socket.
    let rc = unsafe {
        libc::setsockopt(
            stream.as_raw_fd(),
            libc::IPPROTO_IP,
            libc::IP_RECVTTL,
            (&yes as *const i32).cast(),
            size_of::<i32>() as libc::socklen_t,
        )
    };
    if rc == 0 { Ok(()) } else { Err(io::Error::last_os_error()) }
}

pub fn read_chunk_with_ttl(stream: &TcpStream, buf: &mut [u8]) -> io::Result<(usize, Option<u8>)> {
    let fd = stream.as_raw_fd();
    let ctrl_len = unsafe { libc::CMSG_SPACE(size_of::<libc::c_int>() as u32) } as usize;
    let mut ctrl = vec![0u8; ctrl_len];
    let mut iov = libc::iovec { iov_base: buf.as_mut_ptr().cast(), iov_len: buf.len() };
    let mut msg: libc::msghdr = unsafe { zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = ctrl.as_mut_ptr().cast();
    msg.msg_controllen = ctrl_len;

    // SAFETY: `msg` references live stack/heap storage for the iov and control
    // buffers, and `fd` is a valid TCP socket descriptor owned by `stream`.
    let n = unsafe { libc::recvmsg(fd, &mut msg, 0) };
    if n < 0 {
        return Err(io::Error::last_os_error());
    }
    if n == 0 {
        return Ok((0, None));
    }

    let mut ttl: Option<u8> = None;
    // SAFETY: `msg` was just populated by `recvmsg`; CMSG_FIRSTHDR/CMSG_NXTHDR
    // iterate over the ancillary data buffer we provided.
    let mut cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
    while !cmsg.is_null() {
        let cmsg_ref = unsafe { &*cmsg };
        if cmsg_ref.cmsg_level == libc::IPPROTO_IP && cmsg_ref.cmsg_type == libc::IP_TTL {
            // SAFETY: cmsg_data points into the control buffer we own; the
            // kernel wrote a c_int there per the IP_TTL cmsg spec.
            let value: libc::c_int = unsafe { ptr::read_unaligned(libc::CMSG_DATA(cmsg).cast()) };
            ttl = u8::try_from(value).ok();
            break;
        }
        cmsg = unsafe { libc::CMSG_NXTHDR(&msg, cmsg) };
    }
    Ok((n as usize, ttl))
}
```

#### Step 3: Add platform abstractions in `mod.rs`

Add after `tcp_round_trip_time_ms` (after line 143):

```rust
#[cfg(target_os = "linux")]
pub fn enable_recv_ttl(stream: &TcpStream) -> io::Result<()> {
    linux::enable_recv_ttl(stream)
}

#[cfg(not(target_os = "linux"))]
pub fn enable_recv_ttl(_stream: &TcpStream) -> io::Result<()> {
    Ok(()) // best-effort; no-op on non-Linux
}

#[cfg(target_os = "linux")]
pub fn read_chunk_with_ttl(stream: &TcpStream, buf: &mut [u8]) -> io::Result<(usize, Option<u8>)> {
    linux::read_chunk_with_ttl(stream, buf)
}

#[cfg(not(target_os = "linux"))]
pub fn read_chunk_with_ttl(stream: &TcpStream, buf: &mut [u8]) -> io::Result<(usize, Option<u8>)> {
    use std::io::Read;
    Ok((stream.read(buf)?, None))
}
```

#### Step 4: Run platform tests

```bash
cargo test -p ripdpi-runtime --test-threads=1 2>&1 | grep -E "test .* \.\.\.|FAILED|ok$" | head -30
```

Expected: `enable_recv_ttl_succeeds_on_connected_tcp_socket` and `read_chunk_with_ttl_reads_data_from_connected_pair` pass.

#### Step 5: Commit

```bash
git add native/rust/crates/ripdpi-runtime/src/platform/linux.rs \
        native/rust/crates/ripdpi-runtime/src/platform/mod.rs
git commit -m "feat(platform): add IP_RECVTTL enable and recvmsg-based TTL read

Adds enable_recv_ttl() to opt-in a TCP socket to delivering IP_TTL in
ancillary data, and read_chunk_with_ttl() which uses recvmsg to capture
the TTL of the first received segment alongside the data bytes. Non-Linux
targets fall back to a plain read() with None TTL. Used by the relay
loop to seed hop-count-based fake TTL detection."
```

---

### Task 3: Wire server TTL detection into the relay loop

**Files:**
- Modify: `native/rust/crates/ripdpi-runtime/src/runtime.rs`

#### Step 1: Update `FirstResponse` to carry observed server TTL

Locate `enum FirstResponse` (search for `FirstResponse::Forward`). Change:

```rust
// Before (find this pattern):
FirstResponse::Forward(Vec<u8>),
```

to:

```rust
FirstResponse::Forward(Vec<u8>, Option<u8>), // second: observed server IP TTL
```

Update the one match arm at line ~1546 in `relay`:

```rust
// Before:
FirstResponse::Forward(bytes) => {
    session_state.observe_inbound(&bytes);
    client.write_all(&bytes)?;
    if session_state.recv_count > 0 {
        note_adaptive_tcp_success(...)?;
        note_adaptive_fake_ttl_success(...)?;
        note_route_success(...)?;
        success_recorded = true;
    }
    break;
}

// After:
FirstResponse::Forward(bytes, server_ttl) => {
    session_state.observe_inbound(&bytes);
    client.write_all(&bytes)?;
    if session_state.recv_count > 0 {
        if let Some(ttl) = server_ttl {
            note_server_ttl_for_route(state, target, route.group_index, host.as_deref(), ttl)?;
        }
        note_adaptive_tcp_success(state, target, route.group_index, host.as_deref(), &original_request)?;
        note_adaptive_fake_ttl_success(state, target, route.group_index, host.as_deref())?;
        note_route_success(state, target, &route, host.as_deref())?;
        success_recorded = true;
    }
    break;
}
```

#### Step 2: Add `note_server_ttl_for_route` helper

Add after `note_adaptive_fake_ttl_failure` (near line ~998):

```rust
fn note_server_ttl_for_route(
    state: &RuntimeState,
    target: SocketAddr,
    group_index: usize,
    host: Option<&str>,
    observed_ttl: u8,
) -> io::Result<()> {
    let mut resolver =
        state.adaptive_fake_ttl.lock().map_err(|_| io::Error::other("adaptive fake ttl mutex poisoned"))?;
    resolver.note_server_ttl(group_index, target, host, observed_ttl);
    Ok(())
}
```

#### Step 3: Update `read_first_response` to enable TTL and use `read_chunk_with_ttl`

In `read_first_response` (line ~1709), add TTL capture:

```rust
fn read_first_response(
    state: &RuntimeState,
    target: SocketAddr,
    host: Option<&str>,
    upstream: &mut TcpStream,
    config: &RuntimeConfig,
    request: &[u8],
) -> io::Result<FirstResponse> {
    // Enable IP_RECVTTL so the first recvmsg delivers TTL in ancillary data.
    // Best-effort: ignore failure (IPv6 sockets or non-Linux).
    let _ = platform::enable_recv_ttl(upstream);

    let mut collected = Vec::new();
    let mut chunk = vec![0u8; config.buffer_size.max(16_384)];
    let mut tls_partial = TlsRecordTracker::new(request, config);
    let mut timeout_count = 0i32;
    let mut observed_server_ttl: Option<u8> = None;

    loop {
        upstream.set_read_timeout(first_response_timeout(config, &tls_partial))?;

        // For the very first read, use recvmsg to capture the IP TTL.
        let read_result = if observed_server_ttl.is_none() && collected.is_empty() {
            platform::read_chunk_with_ttl(upstream, &mut chunk).map(|(n, ttl)| {
                if ttl.is_some() {
                    observed_server_ttl = ttl;
                }
                n
            })
        } else {
            upstream.read(&mut chunk)
        };

        let result = match read_result {
            Ok(0) => Ok(FirstResponse::Failure {
                failure: ClassifiedFailure::new(
                    FailureClass::SilentDrop,
                    FailureStage::FirstResponse,
                    FailureAction::RetryWithMatchingGroup,
                    "upstream closed before first response",
                ),
                response_bytes: None,
            }),
            Ok(n) => {
                collected.extend_from_slice(&chunk[..n]);
                tls_partial.observe(&chunk[..n]);

                if tls_partial.waiting_for_tls_record() {
                    continue;
                }

                if let Some(failure) = classify_response_failure(state, target, request, &collected, host) {
                    Ok(FirstResponse::Failure {
                        failure,
                        response_bytes: Some(collected),
                    })
                } else {
                    Ok(FirstResponse::Forward(collected, observed_server_ttl))
                }
            }
            // ... keep all existing Err arms unchanged, but replace any
            // `FirstResponse::Forward(collected)` with
            // `FirstResponse::Forward(collected, observed_server_ttl)`
```

> **Note:** Only the `Ok(n)` arm produces `FirstResponse::Forward`; all `Err` arms produce `FirstResponse::Failure`. Verify there are no other `Forward` construction sites.

#### Step 4: Compile check

```bash
cargo check -p ripdpi-runtime 2>&1 | grep "^error" | head -20
```

Expected: zero errors.

#### Step 5: Run full runtime tests

```bash
cargo test -p ripdpi-runtime --test-threads=1 2>&1 | grep -E "FAILED|test result" | head -10
```

Expected: 0 failures.

#### Step 6: Commit

```bash
git add native/rust/crates/ripdpi-runtime/src/runtime.rs
git commit -m "feat(runtime): wire server TTL detection into relay loop

read_first_response now enables IP_RECVTTL and uses recvmsg for the
first upstream read to capture the server's IP TTL. On a successful
first response the observed TTL is passed to note_server_ttl_for_route,
which seeds AdaptiveFakeTtlResolver with the hop-count-derived fallback.
This makes fake packet TTL converge on the correct value (hop_count - 1)
after the first successful connection rather than relying on static config."
```

---

### Task 4: Switch default DNS resolver from Cloudflare to Google

**Files:**
- Modify: `native/rust/crates/ripdpi-monitor/src/lib.rs` (lines 33–37, 1209)

#### Step 1: Write a failing test

Locate the `#[cfg(test)]` block in `ripdpi-monitor/src/lib.rs`. Add:

```rust
#[test]
fn default_runtime_encrypted_dns_context_uses_google() {
    let ctx = default_runtime_encrypted_dns_context();
    assert_eq!(ctx.resolver_id.as_deref(), Some("google"));
    assert!(ctx.doh_url.as_deref().unwrap_or("").contains("dns.google"));
    assert!(ctx.bootstrap_ips.iter().any(|ip| ip == "8.8.8.8"));
}
```

#### Step 2: Run test to verify it fails

```bash
cargo test -p ripdpi-monitor default_runtime_encrypted_dns_context_uses_google 2>&1 | tail -10
```

Expected: FAILED (currently returns cloudflare values).

#### Step 3: Update the constants and resolver_id

In `native/rust/crates/ripdpi-monitor/src/lib.rs`, change lines 33–37:

```rust
// Before:
const DEFAULT_DNS_SERVER: &str = "1.1.1.1:53";
const DEFAULT_DOH_URL: &str = "https://cloudflare-dns.com/dns-query";
const DEFAULT_DOH_BOOTSTRAP_IPS: &[&str] = &["1.1.1.1", "1.0.0.1"];
const DEFAULT_DOH_HOST: &str = "cloudflare-dns.com";
const DEFAULT_DOH_PORT: u16 = 443;

// After:
const DEFAULT_DNS_SERVER: &str = "8.8.8.8:53";
const DEFAULT_DOH_URL: &str = "https://dns.google/dns-query";
const DEFAULT_DOH_BOOTSTRAP_IPS: &[&str] = &["8.8.8.8", "8.8.4.4"];
const DEFAULT_DOH_HOST: &str = "dns.google";
const DEFAULT_DOH_PORT: u16 = 443;
```

Change line 1209 in `default_runtime_encrypted_dns_context`:

```rust
// Before:
resolver_id: Some("cloudflare".to_string()),

// After:
resolver_id: Some("google".to_string()),
```

#### Step 4: Run test to verify it passes

```bash
cargo test -p ripdpi-monitor default_runtime_encrypted_dns_context_uses_google 2>&1 | tail -5
```

Expected: ok.

#### Step 5: Run full monitor tests to confirm no regressions

```bash
cargo test -p ripdpi-monitor --test-threads=1 2>&1 | grep -E "FAILED|test result" | head -5
```

Expected: 0 failures.

#### Step 6: Commit

```bash
git add native/rust/crates/ripdpi-monitor/src/lib.rs
git commit -m "fix(dns): switch default DoH resolver from Cloudflare to Google

Cloudflare 1.1.1.1 and cloudflare-dns.com are blocked in Russian mobile
whitelist mode (Beeline, MTS, Tele2, MegaFon). Google dns.google/8.8.8.8
remains accessible in those environments. Switches DEFAULT_DNS_SERVER,
DEFAULT_DOH_URL, DEFAULT_DOH_BOOTSTRAP_IPS, DEFAULT_DOH_HOST, and
resolver_id accordingly."
```

---

### Task 5: Final verification

#### Step 1: Full Rust test suite

```bash
cargo test -p ripdpi-runtime -p ripdpi-monitor --test-threads=1 2>&1 | grep -E "FAILED|test result"
```

Expected: 0 failures across both crates.

#### Step 2: Android build check

```bash
./gradlew :core:engine:buildRustNativeLibs 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

#### Step 3: Full unit test suite

```bash
./gradlew testDebugUnitTest 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, 0 failures.
