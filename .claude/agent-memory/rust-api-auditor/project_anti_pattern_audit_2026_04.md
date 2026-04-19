---
name: Anti-pattern audit findings April 2026
description: Findings from rust-anti-patterns skill audit covering panic discipline, error propagation, drop/RAII, match exhaustiveness, hot-path alloc, concurrency, atomics, spawn_blocking
type: project
---

Anti-pattern audit run against native/rust/crates/ on 2026-04-19.

## Panic Discipline
- 505 total .unwrap() across 68 files (confirmed count)
- 2,323 total .expect() across 164 files (confirmed count)
- Top unwrap files: ripdpi-config/src/parse/fake_profiles.rs:42, ripdpi-config/src/parse/offsets.rs:29, ripdpi-packets/src/tls.rs:26, ripdpi-failure-classifier/src/lib.rs:18, ripdpi-dns-resolver/src/pool.rs:11
- Production panics: ripdpi-android/src/support.rs:47, ripdpi-config/src/parse/cli.rs:799/815/836 (all inside #[test] blocks -- verified)
- ripdpi-tunnel-core/src/session/tcp.rs:504 has std::thread::sleep inside non-test sync fn called from async

## Error Propagation
- Zero Box<dyn Error> in public API signatures (clean)
- Zero with_context(|| format!(...)) usages (clean)
- ripdpi-dns-resolver/src/resolver.rs has 37 format! calls -- high allocation rate on fast path

## Drop / RAII
- ripdpi-tunnel-core/src/io_loop.rs:109 -- IoUringTunContext.tun_fd: RawFd (should be OwnedFd)
- ripdpi-tunnel-core/src/tunnel_api.rs:62 -- run_tunnel(tun_fd: i32) parameter (ownership comment present but type is wrong)
- ripdpi-io-uring/src/ring.rs:31,33,35,38 -- Submission enum variants carry RawFd (should be BorrowedFd or OwnedFd)
- ripdpi-root-helper carries RawFd in many handler signatures (intentional IPC pattern)

## Match Exhaustiveness  
- Zero #[non_exhaustive] attributes in entire workspace
- 47 `_ =>` wildcard match arms across 24 files on crate-private enums
- ripdpi-config/src/parse/cli.rs has 22 wildcard arms (highest count)
- Many pub enums in ripdpi-config/src/model/mod.rs lack #[non_exhaustive]: DesyncMode, TcpChainStepKind, EmitterTier, SeqOverlapFakeMode, FakePacketSource, FakeOrder, FakeSeqMode, IpIdMode, UdpChainStepKind, WsTunnelMode, QuicInitialMode, QuicFakeProfile, EntropyMode

## Hot-Path Allocations
- ripdpi-dns-resolver/src/resolver.rs: 37 format! calls on query fast path (HIGH)
- ripdpi-packets/src/: grep returned no format!/to_string matches -- clean
- ripdpi-runtime/src/runtime/desync.rs: std::thread::sleep called inside execute_tcp_actions (sync fn called from send_with_group sync path -- NOT directly in async fn, acceptable)

## Concurrency
- RuntimeState (ripdpi-runtime/src/runtime/state.rs): 4 Arc<Mutex<>> fields (cache, adaptive_fake_ttl, adaptive_tuning + one more)
- ripdpi-relay-mux/src/lib.rs: Arc<Mutex<RelayMuxState>> per-connection clone (lines 89, 130, 309)
- ripdpi-tunnel-core/src/io_loop/udp_assoc.rs: Arc<Mutex<StdInstant>> per UDP association (lines 21, 52, 58, 62) -- HIGH: Mutex wrapping Instant is pure write, should be AtomicU64
- ripdpi-telemetry/src/lib.rs: Arc<Mutex<Histogram<u64>>> -- histogram is write-heavy, RwLock wouldn't help; consider lock-free histogram
- Zero `// Lock order:` comments despite structs with multiple Mutex fields
- ripdpi-warp-core/src/lib.rs:114 Arc<Mutex<VecDeque<Bytes>>> on process queue -- potential hot path

## Atomic Ordering
- ripdpi-android/src/telemetry.rs: 20+ Relaxed stores/loads on running, active_sessions, total_errors, route_changes, etc. -- publish/subscribe flags missing // Ordering: comments
- ripdpi-native-protect/src/lib.rs:53 -- last_fd stored with Relaxed; used cross-thread as a publish-once fd value (should be Release/Acquire)
- local-network-fixture uses Relaxed for stop flags in blocking threads -- acceptable for test support

## spawn_blocking vs dedicated thread
- ripdpi-runtime/src/runtime/desync.rs:884,2191,2305 -- std::thread::sleep inside execute_tcp_actions (sync fn, but called from tokio task via send_with_group -> desync path) -- MED: may block tokio worker thread during DesyncAction::Delay
- ripdpi-tunnel-android/src/session/state_machine.rs:83 -- std::thread::sleep(1ms) inside std::thread::spawn worker (acceptable, dedicated thread)
- ripdpi-runtime/src/platform/linux.rs:882 -- std::thread::sleep(delay) in sync fn called from platform path

**Why:** Track for regression and future refactor prioritization.
**How to apply:** Use these findings as baseline for next audit comparison.
