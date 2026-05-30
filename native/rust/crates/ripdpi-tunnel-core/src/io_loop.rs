//! Core tunnel event loop — io_loop_task.
//!
//! Implements the 6-phase loop from spec.md v2.  One tokio task drives the
//! entire smoltcp TCP/IP stack and bridges sessions to/from the SOCKS5 proxy.

use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use smoltcp::iface::{Interface, SocketSet};
use tokio_util::sync::CancellationToken;
use tracing::{debug, info};
use tun_rs::AsyncDevice;

use crate::dns_cache::DnsCache;
use ripdpi_tunnel_config::Config;

use crate::{ActiveSessions, Stats, TunDevice};

mod bridge;
mod dns_intercept;
mod packet;
mod phases;
mod retransmit;
mod routing;
mod setup;
mod setup_dns;
mod state;
mod state_shutdown;
mod tcp_accept;
mod udp_assoc;
mod wait;

use self::bridge::enqueue_tun_packet;
use self::dns_intercept::MapDnsRuntime;
use self::packet::build_udp_response;
use self::setup::setup_io_loop;
use self::wait::{wait_for_next_event, WaitOutcome};

// ── Constants ─────────────────────────────────────────────────────────────────

/// Buffer size for each `tokio::io::duplex()` pair (Decision A).
const DUPLEX_BUF: usize = 65536;

/// Rx/Tx buffer size for each smoltcp TcpSocket.
const TCP_SOCKET_BUF: usize = 65536;

/// Maximum chunk size for duplex bridge pumping (smoltcp <-> session).
const PUMP_CHUNK: usize = 4096;

/// Default poll delay when smoltcp has no pending timers.
const DEFAULT_POLL_DELAY_MS: u64 = 50;
const DNS_QUEUE_CAPACITY: usize = 256;

/// Timeout for pending LISTEN sockets that never complete the handshake.
const PENDING_LISTEN_TIMEOUT: Duration = Duration::from_secs(30);

/// How often (in loop iterations) to sweep stale pending LISTEN entries.
const PENDING_LISTEN_GC_INTERVAL: u32 = 100;

/// How often (in loop iterations) to emit the TCP-retransmit-derived loss
/// percentage via `Stats::emit_loss_pct`. Kept at 100 to match the GC
/// interval — JNI overhead stays off the per-packet path.
const LOSS_EMIT_INTERVAL: u32 = 100;

// ── Helpers ──────────────────────────────────────────────────────────────────

fn send_dns_servfail(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    mapdns: MapDnsRuntime,
    cache: &DnsCache,
    src: SocketAddr,
    query: &[u8],
    host: Option<&str>,
    reason: &str,
) {
    stats.record_dns_failure(host, reason, None);
    match cache.servfail_response(query) {
        Ok(servfail) => {
            let raw = build_udp_response(mapdns.intercept_addr, src, &servfail);
            enqueue_tun_packet(device, raw, "dns-servfail");
        }
        Err(err) => debug!("failed to synthesize SERVFAIL ({reason}): {err}"),
    }
}

// ── io_loop_task ──────────────────────────────────────────────────────────────

/// Main tunnel event loop.
///
/// Implements the 6-phase loop from spec.md v2 (lines 504-588):
///
/// 1. Drain TUN fd -- classify raw IP packets; UDP->DNS/session, TCP->smoltcp
/// 2. smoltcp poll -- advance all TCP state machines
/// 3. New sessions -- detect ESTABLISHED sockets -> spawn `TcpSession`
/// 4. Duplex bridge -- pump data between smoltcp sockets and session tasks
/// 5. Flush tx_queue -- write smoltcp-produced packets back to TUN fd
/// 6. Wait -- sleep until TUN readable / poll_delay / cancellation
/// Optional io_uring context for batch TUN I/O acceleration.
///
/// When provided, Phase 5 (flush tx_queue) uses io_uring batched writes
/// instead of per-packet `try_send` calls. Phase 1 (drain TUN) still uses
/// `try_recv` because the async select loop depends on `tun.readable()` for
/// wakeup, which is incompatible with io_uring-driven reads.
#[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
pub struct IoUringTunContext {
    pub driver: std::sync::Arc<ripdpi_io_uring::IoUringDriver>,
    /// Owned TUN file descriptor for the io_uring batch-write path.
    ///
    /// INVARIANT: this must be a SEPARATE dup from the fd passed to
    /// `run_tunnel` — never the same raw fd number.  Both `OwnedFd` values
    /// call `libc::close` independently on drop; sharing a raw fd number
    /// between them produces a double-close (EBADF or silent corruption if the
    /// fd number is reused between the two drops).
    ///
    /// This field is `pub(crate)` until the io_uring path is fully wired into
    /// `run_tunnel` and audited.  External callers must not construct
    /// `IoUringTunContext` with the same fd number already adopted by
    /// `AsyncDevice` inside `run_tunnel`.
    pub(crate) tun_fd: std::os::fd::OwnedFd,
}

#[allow(clippy::too_many_arguments)]
pub async fn io_loop_task(
    tun: &AsyncDevice,
    device: TunDevice,
    iface: Interface,
    socket_set: SocketSet<'static>,
    sessions: ActiveSessions,
    config: Arc<Config>,
    cancel: CancellationToken,
    stats: Arc<Stats>,
    dns_cache: Option<DnsCache>,
) -> io::Result<()> {
    let mut state = setup_io_loop(device, iface, socket_set, sessions, config, cancel, stats, dns_cache)?;

    loop {
        phases::drain_tun(tun, &mut state).await;
        phases::drain_dns(&mut state);
        phases::poll_smoltcp(&mut state);
        phases::gc_pending_listens(&mut state);
        phases::emit_loss_sample(&mut state);
        phases::admit_tcp_sessions(&mut state);
        phases::pump_bridges(&mut state).await;
        phases::flush_tun(tun, &mut state).await?;

        if matches!(wait_for_next_event(tun, &mut state).await, WaitOutcome::Cancelled) {
            break;
        }
    }

    state.shutdown().await;

    info!("io_loop exited cleanly");
    Ok(())
}
