use crate::platform;
use crate::runtime_policy::extract_host;
use crate::sync::{Arc, Mutex};
use ripdpi_session::SessionState;
use std::io::{self, Read, Write};
use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant};

use super::super::desync::send_with_group;
use super::super::state::RuntimeState;

const RELAY_IDLE_TIMEOUT: Duration = Duration::from_secs(60);

pub(super) const CONNECTION_FREEZE_MARKER: &str = "connection freeze detected";

struct FreezeDetector {
    window_ms: u64,
    min_bytes: u64,
    max_stalls: u32,
    window_start: Instant,
    window_bytes: u64,
    consecutive_stalls: u32,
    warm: bool,
}

impl FreezeDetector {
    fn new(window_ms: u32, min_bytes: u32, max_stalls: u32) -> Self {
        Self {
            window_ms: u64::from(window_ms),
            min_bytes: u64::from(min_bytes),
            max_stalls,
            window_start: Instant::now(),
            window_bytes: 0,
            consecutive_stalls: 0,
            warm: false,
        }
    }

    fn is_enabled(&self) -> bool {
        self.max_stalls > 0
    }

    fn record_bytes(&mut self, n: usize) {
        self.warm = true;
        self.window_bytes += n as u64;
    }

    fn check(&mut self, now: Instant) -> bool {
        if !self.is_enabled() || !self.warm {
            return false;
        }
        let elapsed = now.duration_since(self.window_start).as_millis() as u64;
        if elapsed >= self.window_ms {
            if self.window_bytes < self.min_bytes {
                self.consecutive_stalls += 1;
            } else {
                self.consecutive_stalls = 0;
            }
            self.window_start = now;
            self.window_bytes = 0;
        }
        self.consecutive_stalls >= self.max_stalls
    }
}

pub(super) fn relay_streams(
    client: TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    group_index: usize,
    session_seed: SessionState,
    remembered_host_seed: Option<String>,
) -> io::Result<SessionState> {
    client.set_read_timeout(Some(RELAY_IDLE_TIMEOUT))?;
    client.set_write_timeout(None)?;
    upstream.set_read_timeout(Some(RELAY_IDLE_TIMEOUT))?;
    upstream.set_write_timeout(None)?;

    let client_reader = client.try_clone()?;
    let client_writer = client.try_clone()?;
    let upstream_reader = upstream.try_clone()?;
    let upstream_writer = upstream.try_clone()?;
    let session_state = Arc::new(Mutex::new(session_seed));
    let outbound_session = session_state.clone();
    let inbound_session = session_state.clone();
    let outbound_state = state.clone();
    let group = state
        .config
        .groups
        .get(group_index)
        .cloned()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    let drop_sack = group.actions.drop_sack;
    let peer_done = Arc::new(AtomicBool::new(false));
    let freeze_detected = Arc::new(AtomicBool::new(false));

    let freeze_flag = freeze_detected.clone();
    let timeouts = state.config.timeouts;
    let down_done = peer_done.clone();
    let up_done = peer_done.clone();
    let down = thread::Builder::new()
        .name("ripdpi-dn".into())
        .spawn(move || {
            let detector =
                FreezeDetector::new(timeouts.freeze_window_ms, timeouts.freeze_min_bytes, timeouts.freeze_max_stalls);
            copy_inbound_half(upstream_reader, client_writer, inbound_session, down_done, detector, freeze_flag)
        })
        .map_err(|err| io::Error::other(format!("failed to spawn inbound relay thread: {err}")))?;
    let up = thread::Builder::new()
        .name("ripdpi-up".into())
        .spawn(move || {
            copy_outbound_half(
                client_reader,
                upstream_writer,
                outbound_state,
                group_index,
                outbound_session,
                up_done,
                remembered_host_seed,
            )
        })
        .map_err(|err| io::Error::other(format!("failed to spawn outbound relay thread: {err}")))?;

    let up_result = up.join().map_err(|_| io::Error::other("upstream thread panicked"))?;
    let down_result = down.join().map_err(|_| io::Error::other("downstream thread panicked"))?;

    // Ensure both sockets are fully closed regardless of how relay threads exited.
    // The per-direction shutdown in each thread may be skipped on error paths;
    // this guarantees FIN is sent so sockets don't linger in CLOSE_WAIT.
    let _ = upstream.shutdown(Shutdown::Both);
    let _ = client.shutdown(Shutdown::Both);

    if drop_sack {
        let _ = platform::detach_drop_sack(&upstream);
    }

    up_result?;
    down_result?;

    if freeze_detected.load(Ordering::Acquire) {
        return Err(io::Error::new(io::ErrorKind::TimedOut, CONNECTION_FREEZE_MARKER));
    }

    session_state.lock().map_err(|_| io::Error::other("session mutex poisoned")).map(|state| state.clone())
}

fn copy_inbound_half(
    mut reader: TcpStream,
    mut writer: TcpStream,
    session: Arc<Mutex<SessionState>>,
    peer_done: Arc<AtomicBool>,
    mut detector: FreezeDetector,
    freeze_detected: Arc<AtomicBool>,
) -> io::Result<()> {
    let mut buffer = [0u8; 16_384];
    loop {
        match reader.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => {
                if let Ok(mut state) = session.lock() {
                    state.observe_inbound(&buffer[..n]);
                }
                writer.write_all(&buffer[..n])?;
                detector.record_bytes(n);
                if detector.check(Instant::now()) {
                    freeze_detected.store(true, Ordering::Release);
                    break;
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if detector.check(Instant::now()) {
                    freeze_detected.store(true, Ordering::Release);
                    break;
                }
                if peer_done.load(Ordering::Acquire) {
                    break;
                }
                continue;
            }
            Err(err) => return Err(err),
        }
    }
    peer_done.store(true, Ordering::Release);
    let _ = writer.shutdown(Shutdown::Write);
    let _ = reader.shutdown(Shutdown::Read);
    Ok(())
}

fn flush_outbound_payload(
    writer: &mut TcpStream,
    state: &RuntimeState,
    group_index: usize,
    session: &Arc<Mutex<SessionState>>,
    remembered_host: &mut Option<String>,
    payload: &[u8],
) -> io::Result<()> {
    let progress = {
        let mut state = session.lock().map_err(|_| io::Error::other("session mutex poisoned"))?;
        state.observe_outbound(payload)
    };
    let parsed_host = extract_host(&state.config, payload);
    if parsed_host.is_some() {
        *remembered_host = parsed_host.clone();
    }
    let group = state
        .config
        .groups
        .get(group_index)
        .cloned()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    let peer_addr = writer.peer_addr()?;
    let send_outcome = send_with_group(
        writer,
        state,
        group_index,
        &group,
        payload,
        progress,
        parsed_host.as_deref().or(remembered_host.as_deref()),
        peer_addr,
    )
    .map_err(|err| err.into_io_error())?;
    tracing::trace!(
        target = %peer_addr,
        strategy_family = send_outcome.strategy_family.unwrap_or("plain"),
        bytes_committed = send_outcome.bytes_committed,
        "steady-state outbound payload forwarded"
    );
    Ok(())
}

fn copy_outbound_half(
    mut reader: TcpStream,
    mut writer: TcpStream,
    state: RuntimeState,
    group_index: usize,
    session: Arc<Mutex<SessionState>>,
    peer_done: Arc<AtomicBool>,
    mut remembered_host: Option<String>,
) -> io::Result<()> {
    let mut buffer = [0u8; 16_384];
    loop {
        reader.set_read_timeout(Some(RELAY_IDLE_TIMEOUT))?;
        match reader.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => {
                flush_outbound_payload(&mut writer, &state, group_index, &session, &mut remembered_host, &buffer[..n])?;
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if peer_done.load(Ordering::Acquire) {
                    break;
                }
                continue;
            }
            Err(err) => return Err(err),
        }
    }
    peer_done.store(true, Ordering::Release);
    let _ = writer.shutdown(Shutdown::Write);
    let _ = reader.shutdown(Shutdown::Read);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn freeze_detector_disabled_when_max_stalls_zero() {
        let mut d = FreezeDetector::new(5000, 512, 0);
        d.record_bytes(1);
        let far_future = Instant::now() + Duration::from_secs(300);
        assert!(!d.check(far_future));
    }

    #[test]
    fn freeze_detector_does_not_trigger_before_warm() {
        let mut d = FreezeDetector::new(100, 512, 1);
        let far_future = Instant::now() + Duration::from_secs(300);
        assert!(!d.check(far_future));
    }

    #[test]
    fn freeze_detector_triggers_after_consecutive_stalls() {
        let start = Instant::now();
        let mut d = FreezeDetector::new(100, 512, 3);
        d.window_start = start;
        d.record_bytes(1024);

        // First window: good throughput -- reset
        assert!(!d.check(start + Duration::from_millis(100)));
        assert_eq!(d.consecutive_stalls, 0);

        // Windows 2-4: only trickle bytes (below 512)
        d.record_bytes(10);
        assert!(!d.check(start + Duration::from_millis(200)));
        assert_eq!(d.consecutive_stalls, 1);

        d.record_bytes(5);
        assert!(!d.check(start + Duration::from_millis(300)));
        assert_eq!(d.consecutive_stalls, 2);

        d.record_bytes(2);
        assert!(d.check(start + Duration::from_millis(400)));
        assert_eq!(d.consecutive_stalls, 3);
    }

    #[test]
    fn freeze_detector_resets_on_good_window() {
        let start = Instant::now();
        let mut d = FreezeDetector::new(100, 512, 3);
        d.window_start = start;
        d.record_bytes(1024);

        // Warm-up window passes with good throughput
        assert!(!d.check(start + Duration::from_millis(100)));
        assert_eq!(d.consecutive_stalls, 0);

        // First stall window: only 10 bytes
        d.record_bytes(10);
        d.check(start + Duration::from_millis(200));
        assert_eq!(d.consecutive_stalls, 1);

        // Second stall window
        d.record_bytes(10);
        d.check(start + Duration::from_millis(300));
        assert_eq!(d.consecutive_stalls, 2);

        // Good window -- resets counter
        d.record_bytes(600);
        assert!(!d.check(start + Duration::from_millis(400)));
        assert_eq!(d.consecutive_stalls, 0);
    }

    #[test]
    fn freeze_detector_does_not_false_positive_on_slow_but_sufficient_transfer() {
        let start = Instant::now();
        let mut d = FreezeDetector::new(5000, 512, 3);
        d.window_start = start;
        d.record_bytes(1024);

        // Each window: exactly at threshold (512 bytes)
        for i in 1..=10 {
            d.record_bytes(512);
            assert!(!d.check(start + Duration::from_millis(5000 * i)));
            assert_eq!(d.consecutive_stalls, 0);
        }
    }
}
