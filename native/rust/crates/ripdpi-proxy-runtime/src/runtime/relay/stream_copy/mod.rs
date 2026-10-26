#[cfg(test)]
mod continuation_tests;
mod copy_halves;
mod freeze;
mod observers;

use copy_halves::{copy_inbound_half, copy_outbound_half};
use freeze::FreezeDetector;

use crate::sync::{Arc, Mutex};
use std::io;
use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;

use super::super::state::RuntimeState;
use super::super::types::{RuntimeRelayGroupSettings, RuntimeRelayRotationSeed};
use super::platform as relay_platform;
use super::session::RelaySession;

const RELAY_IDLE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(60);
/// A write that makes no progress this long ends the relay: a peer that stops reading
/// (a zero window that keeps ACKing persist probes) must not pin the relay threads forever.
/// Unit tests use 1 s so a stalled peer can be shown to end the relay.
const RELAY_WRITE_STALL_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(if cfg!(test) { 1 } else { 300 });

pub(super) const CONNECTION_FREEZE_MARKER: &str = "connection freeze detected";

#[derive(Clone)]
pub(super) struct RelayStreamSettings {
    pub(super) group: RuntimeRelayGroupSettings,
    pub(super) rotation_seed: Option<RuntimeRelayRotationSeed>,
}

pub(super) struct RelayOutboundContext {
    pub(super) state: RuntimeState,
    pub(super) group_index: usize,
}

pub(super) fn shutdown_relay_pair(upstream: &TcpStream, client: &TcpStream) {
    for socket in [upstream, client] {
        // Stop writes even when a peer FIN makes a combined shutdown fail.
        let _ = socket.shutdown(Shutdown::Write);
        let _ = socket.shutdown(Shutdown::Read);
    }
}

pub(super) fn relay_streams(
    client: TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    group_index: usize,
    settings: RelayStreamSettings,
    mut session_seed: RelaySession,
    remembered_host_seed: Option<String>,
) -> io::Result<RelaySession> {
    let rotation_state = RelaySession::rotation_controller(settings.rotation_seed.clone());
    let pending_response = session_seed.take_pending_response();
    let session_state = session_seed.into_shared();
    for socket in [&client, &upstream] {
        let _ = (
            socket.set_read_timeout(Some(RELAY_IDLE_TIMEOUT)),
            socket.set_write_timeout(Some(RELAY_WRITE_STALL_TIMEOUT)),
        );
    }

    fn clone_err(role: &'static str) -> impl FnOnce(io::Error) -> io::Error {
        move |e| io::Error::other(format!("clone {role} socket for relay: {e}"))
    }
    let client_reader = client.try_clone().map_err(clone_err("client"))?;
    let mut client_writer = client.try_clone().map_err(clone_err("client"))?;
    let mut upstream_reader = upstream.try_clone().map_err(clone_err("upstream"))?;
    let upstream_writer = upstream.try_clone().map_err(clone_err("upstream"))?;
    let outbound_session = session_state.clone();
    let inbound_session = session_state.clone();
    let outbound_state = state.clone();
    let inbound_state = state.clone();
    let outbound_context = RelayOutboundContext { state: outbound_state, group_index };
    let peer_done = Arc::new(AtomicBool::new(false));
    let freeze_detected = Arc::new(AtomicBool::new(false));
    let remembered_host = Arc::new(Mutex::new(remembered_host_seed));
    let inbound_host = remembered_host.clone();
    let outbound_host = remembered_host.clone();
    let inbound_rotation = rotation_state.clone();
    let outbound_rotation = rotation_state.clone();

    let freeze_flag = freeze_detected.clone();
    let timeouts = settings.group.timeouts();
    let down_done = peer_done.clone();
    let down = thread::Builder::new()
        .name("ripdpi-dn".into())
        .spawn(move || {
            let detector =
                FreezeDetector::new(timeouts.freeze_window_ms, timeouts.freeze_min_bytes, timeouts.freeze_max_stalls);
            let result = (|| {
                if let Some(pending) = pending_response {
                    pending.finish(&inbound_state, &mut upstream_reader, &mut client_writer, &inbound_session)?;
                }
                copy_inbound_half(
                    &mut upstream_reader,
                    &mut client_writer,
                    &inbound_state,
                    inbound_session,
                    inbound_host,
                    inbound_rotation,
                    down_done.clone(),
                    detector,
                    freeze_flag,
                )
            })();
            if result.is_err() {
                down_done.store(true, Ordering::Release);
                shutdown_relay_pair(&upstream_reader, &client_writer);
            }
            result
        })
        .map_err(|err| io::Error::other(format!("failed to spawn inbound relay thread: {err}")))?;

    // Keep the client->upstream half on the worker thread that already owns
    // this connection. That preserves the blocking desync path while avoiding
    // a second per-flow relay thread in steady state.
    let up_result = copy_outbound_half(
        client_reader,
        upstream_writer,
        outbound_context,
        outbound_session,
        outbound_host,
        outbound_rotation,
        peer_done.clone(),
    );
    if up_result.is_err() {
        peer_done.store(true, Ordering::Release);
        shutdown_relay_pair(&upstream, &client);
    }
    let down_result = down.join().map_err(|_| io::Error::other("downstream thread panicked"))?;

    // Ensure both sockets are fully closed regardless of how relay threads exited.
    // The per-direction shutdown in each thread may be skipped on error paths;
    // this guarantees FIN is sent so sockets don't linger in CLOSE_WAIT. A frozen
    // relay sends no FIN to the client: its close becomes an RST.
    if freeze_detected.load(Ordering::Acquire) {
        let _ = (upstream.shutdown(Shutdown::Both), client.shutdown(Shutdown::Read));
    } else {
        shutdown_relay_pair(&upstream, &client);
    }

    if settings.group.drop_sack() {
        let _ = relay_platform::detach_drop_sack(&upstream);
    }

    up_result.and(down_result)?;

    if freeze_detected.load(Ordering::Acquire) {
        return Err(io::Error::new(io::ErrorKind::TimedOut, CONNECTION_FREEZE_MARKER));
    }

    session_state.snapshot()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};

    /// A TLS application-data header announcing 16 KiB, so later bytes sit mid-record.
    const MID_RECORD_HEADER: [u8; 5] = [0x17, 0x03, 0x03, 0x40, 0x00];

    #[test]
    fn freeze_detector_ignores_an_idle_stream_at_a_record_boundary() {
        let start = Instant::now();
        let mut d = FreezeDetector::new(100, 512, 2);
        d.window_start = start;
        d.record_bytes(&[0x17, 0x03, 0x03, 0x00, 0x02, 0xaa, 0xbb]);

        for i in 1..=5 {
            assert!(!d.check(start + Duration::from_millis(100 * i)), "an idle keepalive is not a freeze");
        }
        assert_eq!(d.consecutive_stalls, 0);
    }

    #[test]
    fn freeze_detector_disabled_when_max_stalls_zero() {
        let mut d = FreezeDetector::new(5000, 512, 0);
        d.record_bytes(&[0; 1]);
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
        d.record_bytes(&MID_RECORD_HEADER);
        d.record_bytes(&[0; 1024]);

        // First window: good throughput -- reset
        assert!(!d.check(start + Duration::from_millis(100)));
        assert_eq!(d.consecutive_stalls, 0);

        // Windows 2-4: only trickle bytes (below 512)
        d.record_bytes(&[0; 10]);
        assert!(!d.check(start + Duration::from_millis(200)));
        assert_eq!(d.consecutive_stalls, 1);

        d.record_bytes(&[0; 5]);
        assert!(!d.check(start + Duration::from_millis(300)));
        assert_eq!(d.consecutive_stalls, 2);

        d.record_bytes(&[0; 2]);
        assert!(d.check(start + Duration::from_millis(400)));
        assert_eq!(d.consecutive_stalls, 3);
    }

    #[test]
    fn freeze_detector_resets_on_good_window() {
        let start = Instant::now();
        let mut d = FreezeDetector::new(100, 512, 3);
        d.window_start = start;
        d.record_bytes(&MID_RECORD_HEADER);
        d.record_bytes(&[0; 1024]);

        // Warm-up window passes with good throughput
        assert!(!d.check(start + Duration::from_millis(100)));
        assert_eq!(d.consecutive_stalls, 0);

        // First stall window: only 10 bytes
        d.record_bytes(&[0; 10]);
        d.check(start + Duration::from_millis(200));
        assert_eq!(d.consecutive_stalls, 1);

        // Second stall window
        d.record_bytes(&[0; 10]);
        d.check(start + Duration::from_millis(300));
        assert_eq!(d.consecutive_stalls, 2);

        // Good window -- resets counter
        d.record_bytes(&[0; 600]);
        assert!(!d.check(start + Duration::from_millis(400)));
        assert_eq!(d.consecutive_stalls, 0);
    }

    #[test]
    fn freeze_detector_does_not_false_positive_on_slow_but_sufficient_transfer() {
        let start = Instant::now();
        let mut d = FreezeDetector::new(5000, 512, 3);
        d.window_start = start;
        d.record_bytes(&[0; 1024]);

        // Each window: exactly at threshold (512 bytes)
        for i in 1..=10 {
            d.record_bytes(&[0; 512]);
            assert!(!d.check(start + Duration::from_millis(5000 * i)));
            assert_eq!(d.consecutive_stalls, 0);
        }
    }

    #[test]
    fn response_after_client_half_close_survives_an_idle_read_timeout() {
        use std::io::{Read, Write};
        use std::net::{Ipv4Addr, TcpListener};

        fn pair() -> (TcpStream, TcpStream) {
            let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind pair");
            let first = TcpStream::connect(listener.local_addr().expect("address")).expect("connect");
            (first, listener.accept().expect("accept").0)
        }
        let (mut upstream_reader, mut origin) = pair();
        let (mut client_writer, mut app) = pair();
        // A short idle timeout stands in for RELAY_IDLE_TIMEOUT; the client already sent FIN.
        upstream_reader.set_read_timeout(Some(Duration::from_millis(30))).expect("idle timeout");
        let peer_done = Arc::new(AtomicBool::new(true));
        let server = thread::spawn(move || {
            thread::sleep(Duration::from_millis(150));
            origin.write_all(b"late response").expect("late response");
        });
        let state = RuntimeState::test(crate::runtime::config::RuntimeConfig::default());
        let relay = thread::spawn(move || {
            copy_inbound_half(
                &mut upstream_reader,
                &mut client_writer,
                &state,
                super::super::session::FirstOutboundSession::new().into_relay_session().into_shared(),
                Arc::new(Mutex::new(None)),
                None,
                peer_done,
                FreezeDetector::new(5000, 512, 0),
                Arc::new(AtomicBool::new(false)),
            )
        });

        app.set_read_timeout(Some(Duration::from_secs(2))).expect("app timeout");
        let mut response = [0u8; 13];
        let read = app.read_exact(&mut response);
        server.join().expect("server");
        assert!(read.is_ok(), "the response after the client FIN was cut: {read:?}");
        assert_eq!(&response, b"late response");
        drop(app);
        drop(relay);
    }

    #[test]
    fn frozen_tls_record_aborts_the_client_with_a_reset_within_the_window() {
        use std::io::{Read, Write};
        use std::net::{Ipv4Addr, TcpListener};

        fn pair() -> (TcpStream, TcpStream) {
            let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind pair");
            let first = TcpStream::connect(listener.local_addr().expect("address")).expect("connect");
            (first, listener.accept().expect("accept").0)
        }
        let (mut upstream_reader, mut origin) = pair();
        let (mut client_writer, mut app) = pair();
        upstream_reader.set_read_timeout(Some(Duration::from_secs(5))).expect("idle timeout");
        // A TLS record that announces 16 KiB and stops after 100 bytes: a mid-record freeze.
        origin.write_all(&[0x17, 0x03, 0x03, 0x40, 0x00]).expect("record header");
        origin.write_all(&[0u8; 100]).expect("partial record body");
        let state = RuntimeState::test(crate::runtime::config::RuntimeConfig::default());
        let freeze = Arc::new(AtomicBool::new(false));
        let relay_freeze = freeze.clone();
        let started = Instant::now();
        let relay = thread::spawn(move || {
            copy_inbound_half(
                &mut upstream_reader,
                &mut client_writer,
                &state,
                super::super::session::FirstOutboundSession::new().into_relay_session().into_shared(),
                Arc::new(Mutex::new(None)),
                None,
                Arc::new(AtomicBool::new(false)),
                FreezeDetector::new(50, 512, 2),
                relay_freeze,
            )
        });

        relay.join().expect("relay thread").expect("inbound half");
        let elapsed = started.elapsed();
        let mut forwarded = [0u8; 105];
        app.read_exact(&mut forwarded).expect("bytes before the freeze reach the client");
        let after = app.read(&mut [0u8; 1]);

        assert!(freeze.load(Ordering::Acquire), "the stalled record must be declared frozen");
        assert!(elapsed < Duration::from_secs(2), "the freeze window must be honored, took {elapsed:?}");
        assert!(
            after.as_ref().is_err_and(|err| err.kind() == io::ErrorKind::ConnectionReset),
            "a frozen stream must not look like a complete response: {after:?}"
        );
        drop(origin);
    }

    /// Feeds one relay side while its opposite peer never reads, and returns the relay
    /// result, how long it took, and how the stalled peer's stream ended.
    fn relay_with_stalled_peer(stall_client: bool) -> (io::Result<RelaySession>, Duration, io::Result<usize>) {
        use std::io::{Read, Write};
        use std::net::{Ipv4Addr, TcpListener};

        fn pair() -> (TcpStream, TcpStream) {
            let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind pair");
            let first = TcpStream::connect(listener.local_addr().expect("address")).expect("connect");
            (first, listener.accept().expect("accept").0)
        }
        let (client, app) = pair();
        let (upstream, origin) = pair();
        let (mut feeder, mut stalled) = if stall_client { (origin, app) } else { (app, origin) };
        let state = RuntimeState::test(crate::runtime::config::RuntimeConfig::default());
        let settings = RelayStreamSettings {
            group: state.relay_group(0).expect("relay group"),
            rotation_seed: state.relay_rotation_seed(0).expect("rotation seed"),
        };
        feeder.set_write_timeout(Some(Duration::from_millis(500))).expect("feeder timeout");
        let feed = thread::spawn(move || {
            let chunk = [0x42u8; 65_536];
            while feeder.write_all(&chunk).is_ok() {}
        });
        let started = Instant::now();
        let result = relay_streams(
            client,
            upstream,
            &state,
            0,
            settings,
            super::super::session::FirstOutboundSession::new().into_relay_session(),
            None,
        );
        let elapsed = started.elapsed();
        feed.join().expect("feeder");
        stalled.set_read_timeout(Some(Duration::from_secs(2))).expect("drain timeout");
        let mut sink = [0u8; 65_536];
        let end = loop {
            match stalled.read(&mut sink) {
                Ok(0) => break Ok(0),
                Ok(_) => {}
                Err(err) => break Err(err),
            }
        };
        (result, elapsed, end)
    }

    #[test]
    fn relay_ends_when_a_peer_stops_reading() {
        for stall_client in [true, false] {
            let (result, elapsed, end) = relay_with_stalled_peer(stall_client);
            // A zero-window peer keeps ACKing probes, so only a send timeout ends a blocked write.
            assert!(result.is_err(), "stall_client={stall_client}: a stalled write must end the relay");
            assert!(elapsed < Duration::from_secs(15), "stall_client={stall_client}: relay took {elapsed:?}");
            assert!(
                !matches!(&end, Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut)),
                "stall_client={stall_client}: the stalled peer's socket was left open: {end:?}"
            );
        }
    }
}
