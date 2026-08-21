use std::future::Future;
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;

use std::net::IpAddr;

use tokio::io::{AsyncRead, AsyncReadExt};
use tokio::net::{TcpStream, UdpSocket};
use tokio_util::sync::CancellationToken;

use crate::backend::RelayBackend;
use crate::socks::reply::write_reply;
use crate::socks::target::RelayTargetAddr;
use crate::socks::telemetry::{SocksSessionConfig, SocksTelemetry};
use crate::socks::{decode_udp_frame, encode_udp_frame};

const UDP_BUFFER_SIZE: usize = 65_536;

struct XudpAssociationTelemetry<'a, T: SocksTelemetry + ?Sized> {
    telemetry: &'a T,
    termination_reason: &'static str,
}

impl<T: SocksTelemetry + ?Sized> XudpAssociationTelemetry<'_, T> {
    fn set_termination_reason(&mut self, reason: &'static str) {
        self.termination_reason = reason;
    }
}

impl<T: SocksTelemetry + ?Sized> Drop for XudpAssociationTelemetry<'_, T> {
    fn drop(&mut self) {
        self.telemetry.record_xudp_association_closed(self.termination_reason);
    }
}

/// Drive a SOCKS5 `UDP ASSOCIATE`: gate on carrier UDP capability, open the
/// upstream session, bind the relay socket, and write the success reply, then
/// hand off to [`pump_udp_association`], which owns every teardown arm
/// (`cancel`, control EOF, carrier errors, idle timeout) and documents the
/// cancel-safety contract for the whole association.
/// The datagram carrier a SOCKS5 `UDP ASSOCIATE` pumps through.
///
/// Internal seam over [`crate::backend::udp::RelayUdpSession`] so the pump
/// loop can be driven against a test fake without building a real backend.
pub(crate) trait UdpCarrier: Send {
    fn send_to(&mut self, target: &RelayTargetAddr, payload: &[u8]) -> impl Future<Output = io::Result<()>> + Send;

    fn recv_from(&mut self) -> impl Future<Output = io::Result<(RelayTargetAddr, Vec<u8>)>> + Send;

    fn queue_high_water_mark(&self) -> usize {
        0
    }
}

/// The client-facing relay UDP socket of an association.
///
/// Internal seam over [`tokio::net::UdpSocket`] so the pump loop's
/// downlink-delivery policy can be driven against a test fake.
pub(crate) trait ClientUdpSocket: Send {
    async fn recv_from(&self, buf: &mut [u8]) -> io::Result<(usize, SocketAddr)>;

    async fn send_to(&self, buf: &[u8], target: SocketAddr) -> io::Result<()>;
}

impl ClientUdpSocket for UdpSocket {
    async fn recv_from(&self, buf: &mut [u8]) -> io::Result<(usize, SocketAddr)> {
        UdpSocket::recv_from(self, buf).await
    }

    async fn send_to(&self, buf: &[u8], target: SocketAddr) -> io::Result<()> {
        UdpSocket::send_to(self, buf, target).await.map(|_| ())
    }
}

pub(crate) async fn handle_udp_associate<T>(
    mut client: TcpStream,
    backend: Arc<RelayBackend>,
    config: SocksSessionConfig,
    telemetry: &T,
    cancel: CancellationToken,
) -> io::Result<()>
where
    T: SocksTelemetry + ?Sized,
{
    if !backend.udp_capable() {
        write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("relay backend {} does not support UDP ASSOCIATE", config.backend_kind),
        ));
    }

    let is_xudp = config.backend_kind == "vless_reality";
    let mut udp_session = match backend.open_udp_session().await {
        Ok(session) => session,
        Err(error) => {
            if is_xudp {
                telemetry.record_xudp_open_failure();
            }
            telemetry.record_handshake_error(error.to_string());
            write_reply(&mut client, 0x01, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
            return Err(error);
        }
    };
    let mut xudp_telemetry = is_xudp.then(|| {
        telemetry.record_xudp_association_opened();
        XudpAssociationTelemetry { telemetry, termination_reason: "association_aborted" }
    });

    let udp_socket = UdpSocket::bind(format!("{}:0", config.local_socks_host)).await?;
    let bound = udp_socket.local_addr()?;
    write_reply(&mut client, 0x00, bound).await?;

    let Ok(control_addr) = client.peer_addr() else {
        return Err(io::Error::new(io::ErrorKind::NotConnected, "UDP control connection has no peer"));
    };
    let outcome =
        pump_udp_association(client, control_addr.ip(), udp_socket, &mut udp_session, &config, telemetry, cancel).await;
    if let Some(state) = &mut xudp_telemetry {
        state.set_termination_reason(outcome.termination_reason);
    }
    outcome.result
}

/// The terminal state of a UDP association pump: its `io` result plus the
/// xudp termination reason it concluded with.
struct UdpPumpOutcome {
    result: io::Result<()>,
    termination_reason: &'static str,
}

/// Pump datagrams between the client's UDP socket and the carrier until the
/// control connection closes, `cancel` fires, the carrier errors, or no
/// datagram moves for `config.timeouts.udp_idle`.
///
/// # Cancel safety
///
/// Cancel-safe. The pump `select!` is `biased` with the teardown arms first —
/// `cancel.cancelled()` then the control-connection EOF — so sustained traffic
/// can never starve teardown. The idle arm trails every activity arm, so a
/// datagram racing the idle deadline wins and restarts the window. Teardown is
/// at the `select!` boundary only: once a recv arm has been selected, its body
/// runs a follow-on message-atomic `send_to().await` (relay→client or
/// client→relay). VLESS/XUDP sends are executed by a bounded writer task with a
/// terminal deadline, so a stalled Reality carrier cannot defer cancellation
/// indefinitely or be reused after a partial frame. The success reply
/// (`REP=0x00`) and the first loop poll are separated by no externally
/// cancellable drop point, so a confirmed `ASSOCIATE` always enters the pump.
async fn pump_udp_association<C, U, S, T>(
    mut control: S,
    control_ip: IpAddr,
    udp_socket: U,
    udp_session: &mut C,
    config: &SocksSessionConfig,
    telemetry: &T,
    cancel: CancellationToken,
) -> UdpPumpOutcome
where
    C: UdpCarrier,
    U: ClientUdpSocket,
    S: AsyncRead + Unpin,
    T: SocksTelemetry + ?Sized,
{
    let is_xudp = config.backend_kind == "vless_reality";
    let mut termination_reason = "association_aborted";
    let mut last_activity = tokio::time::Instant::now();

    // Anti-spoofing pin: the association locks to the first client socket
    // address that passes the control-IP gate. Later datagrams from any other
    // source are dropped, so a local process that discovers the relay port can
    // neither inject uplink datagrams nor receive downlink ones.
    let mut pinned_client = None;
    let mut udp_buffer = vec![0u8; UDP_BUFFER_SIZE];
    let mut control_probe = [0u8; 1];
    let control_closed = async {
        let _ = control.read(&mut control_probe).await;
    };
    tokio::pin!(control_closed);

    let result = loop {
        let idle_deadline = last_activity + config.timeouts.udp_idle;
        tokio::select! {
            biased;
            () = cancel.cancelled() => {
                termination_reason = "runtime_cancelled";
                break Ok(());
            },
            _ = &mut control_closed => {
                termination_reason = "control_closed";
                break Ok(());
            },
            recv = udp_socket.recv_from(&mut udp_buffer) => {
                let (received, source) = match recv {
                    Ok(datagram) => datagram,
                    Err(error) => break Err(error),
                };
                match pinned_client {
                    None => {
                        if source.ip() != control_ip {
                            continue;
                        }
                        pinned_client = Some(source);
                    }
                    Some(pinned) if pinned != source => continue,
                    Some(_) => {}
                }
                last_activity = tokio::time::Instant::now();
                let (target, payload) = match decode_udp_frame(&udp_buffer[..received]) {
                    Ok(frame) => frame,
                    // A single malformed datagram must not tear down the whole
                    // association: SOCKS5 UDP is lossy by design, so the frame
                    // is dropped and the pump keeps serving the pinned client.
                    Err(error) => {
                        tracing::debug!(
                            ring = "relay",
                            subsystem = "relay",
                            source = "relay",
                            kind = "udp_frame_malformed",
                            size = received,
                            error = %error,
                            "dropping malformed SOCKS5 UDP frame"
                        );
                        continue;
                    }
                };
                // Policy: per-datagram UDP destinations never enter the
                // runtime telemetry surface, for any backend. Datagram flows
                // carry DNS lookups and arbitrary endpoints; only aggregate
                // session counters describe them. The user-visible last-target
                // marker is reserved for CONNECT authorities (see auth.rs).
                if let Err(error) = udp_session.send_to(&target, payload).await {
                    if is_xudp {
                        termination_reason = if error.kind() == io::ErrorKind::TimedOut {
                            "write_timeout"
                        } else {
                            "write_error"
                        };
                        telemetry.record_xudp_write_failure(error.kind() == io::ErrorKind::TimedOut);
                    }
                    telemetry.record_handshake_error(error.to_string());
                    break Err(error);
                }
                if is_xudp {
                    telemetry.record_xudp_uplink(payload.len(), udp_session.queue_high_water_mark());
                }
            }
            result = udp_session.recv_from() => {
                let (target, payload) = match result {
                    Ok(datagram) => datagram,
                    Err(error) => {
                        if is_xudp {
                            termination_reason = if error.kind() == io::ErrorKind::TimedOut {
                                "read_timeout"
                            } else {
                                "read_error"
                            };
                            telemetry.record_xudp_read_failure(error.kind() == io::ErrorKind::TimedOut);
                        }
                        break Err(error);
                    }
                };
                last_activity = tokio::time::Instant::now();
                if is_xudp {
                    telemetry.record_xudp_downlink(payload.len());
                }
                let Some(destination) = pinned_client else {
                    continue;
                };
                let frame = match encode_udp_frame(&target, &payload) {
                    Ok(frame) => frame,
                    Err(error) => break Err(error),
                };
                // One undeliverable datagram must not tear down the
                // association: a vanished or congested client surfaces as an
                // ICMP-triggered send failure here, and the control-connection
                // EOF or the idle timeout remains the real teardown signal.
                if let Err(error) = udp_socket.send_to(&frame, destination).await {
                    tracing::debug!(
                        ring = "relay",
                        subsystem = "relay",
                        source = "relay",
                        kind = "udp_downlink_dropped",
                        error = %error,
                        "dropping undeliverable downlink datagram"
                    );
                }
            }
            () = tokio::time::sleep_until(idle_deadline) => {
                termination_reason = "idle_timeout";
                break Ok(());
            }
        }
    };

    UdpPumpOutcome { result, termination_reason }
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr, SocketAddr};
    use std::sync::Arc;
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{Duration, Instant};

    use tokio::sync::mpsc;

    use tokio::io::duplex;
    use tokio_util::sync::CancellationToken;

    use super::*;
    use crate::socks::telemetry::RelaySessionTimeouts;

    struct NeverUdpCarrier {
        recv_calls: Arc<AtomicU64>,
    }

    impl UdpCarrier for NeverUdpCarrier {
        async fn send_to(&mut self, _target: &RelayTargetAddr, _payload: &[u8]) -> io::Result<()> {
            Err(io::Error::other("silent test carrier must not be sent to"))
        }

        async fn recv_from(&mut self) -> io::Result<(RelayTargetAddr, Vec<u8>)> {
            self.recv_calls.fetch_add(1, Ordering::Relaxed);
            std::future::pending().await
        }
    }

    struct RecordingUdpCarrier {
        sent: mpsc::UnboundedSender<Vec<u8>>,
        downlink: tokio::sync::Mutex<mpsc::UnboundedReceiver<(RelayTargetAddr, Vec<u8>)>>,
    }

    impl UdpCarrier for RecordingUdpCarrier {
        async fn send_to(&mut self, _target: &RelayTargetAddr, payload: &[u8]) -> io::Result<()> {
            let _ = self.sent.send(payload.to_vec());
            Ok(())
        }

        async fn recv_from(&mut self) -> io::Result<(RelayTargetAddr, Vec<u8>)> {
            self.downlink.lock().await.recv().await.ok_or_else(|| io::Error::other("downlink script channel closed"))
        }
    }

    struct FailingDownlinkSocket {
        uplink: tokio::sync::Mutex<mpsc::UnboundedReceiver<(Vec<u8>, SocketAddr)>>,
        sends_attempted: Arc<AtomicU64>,
    }

    impl ClientUdpSocket for FailingDownlinkSocket {
        async fn recv_from(&self, buf: &mut [u8]) -> io::Result<(usize, SocketAddr)> {
            let (data, source) = self
                .uplink
                .lock()
                .await
                .recv()
                .await
                .ok_or_else(|| io::Error::other("uplink script channel closed"))?;
            let received = data.len().min(buf.len());
            buf[..received].copy_from_slice(&data[..received]);
            Ok((received, source))
        }

        async fn send_to(&self, _buf: &[u8], _target: SocketAddr) -> io::Result<()> {
            self.sends_attempted.fetch_add(1, Ordering::Relaxed);
            Err(io::Error::other("simulated downlink delivery failure"))
        }
    }

    #[derive(Default)]
    struct FakeSocksTelemetry {
        handshake_errors: Arc<AtomicU64>,
        targets: std::sync::Mutex<Vec<String>>,
    }

    impl SocksTelemetry for FakeSocksTelemetry {
        fn next_attempt_id(&self) -> u64 {
            0
        }

        fn record_target(&self, target: String) {
            self.targets.lock().expect("target lock").push(target);
        }

        fn record_handshake_error(&self, _error: String) {
            self.handshake_errors.fetch_add(1, Ordering::Relaxed);
        }
    }

    fn silent_session_config(udp_idle: Duration) -> SocksSessionConfig {
        SocksSessionConfig {
            local_socks_host: "127.0.0.1".to_string(),
            backend_kind: "trojan".to_string(),
            confirm_good_eligible: false,
            timeouts: RelaySessionTimeouts {
                connect_deadline: Duration::from_secs(30),
                tcp_idle: Duration::from_secs(300),
                udp_idle,
            },
        }
    }

    #[tokio::test]
    async fn udp_pump_ends_gracefully_after_idle_timeout_without_traffic() {
        let (control, control_peer) = duplex(64);
        let udp_socket = UdpSocket::bind((std::net::Ipv4Addr::LOCALHOST, 0)).await.expect("bind relay UDP socket");
        let mut carrier = NeverUdpCarrier { recv_calls: Arc::new(AtomicU64::new(0)) };
        let telemetry = FakeSocksTelemetry::default();
        let config = silent_session_config(Duration::from_millis(50));

        let started = Instant::now();
        let outcome = tokio::time::timeout(
            Duration::from_secs(2),
            pump_udp_association(
                control,
                IpAddr::V4(std::net::Ipv4Addr::LOCALHOST),
                udp_socket,
                &mut carrier,
                &config,
                &telemetry,
                CancellationToken::new(),
            ),
        )
        .await
        .expect("idle watchdog did not fire; pump hung past the outer guard");

        outcome.result.expect("a silent association must end gracefully on the idle timeout");
        assert!(started.elapsed() < Duration::from_secs(1), "idle window must fire near 50ms");
        assert_eq!(0, telemetry.handshake_errors.load(Ordering::Relaxed), "idle expiry is not a handshake error");
        drop(control_peer);
    }

    #[tokio::test]
    async fn udp_pump_reports_runtime_cancelled_reason_on_shutdown_token() {
        let (control, control_peer) = duplex(64);
        let udp_socket = UdpSocket::bind((std::net::Ipv4Addr::LOCALHOST, 0)).await.expect("bind relay UDP socket");
        let mut carrier = NeverUdpCarrier { recv_calls: Arc::new(AtomicU64::new(0)) };
        let telemetry = FakeSocksTelemetry::default();
        let config = silent_session_config(Duration::from_secs(300));
        let cancel = CancellationToken::new();
        cancel.cancel();

        let outcome = tokio::time::timeout(
            Duration::from_secs(2),
            pump_udp_association(
                control,
                IpAddr::V4(std::net::Ipv4Addr::LOCALHOST),
                udp_socket,
                &mut carrier,
                &config,
                &telemetry,
                cancel,
            ),
        )
        .await
        .expect("cancelled pump must resolve promptly");

        outcome.result.expect("cancellation is graceful");
        assert_eq!("runtime_cancelled", outcome.termination_reason);
        drop(control_peer);
    }

    #[tokio::test]
    async fn udp_pump_drops_malformed_frames_without_tearing_down_the_association() {
        let (control, control_peer) = duplex(64);
        let relay_socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind relay UDP socket");
        let relay_addr = relay_socket.local_addr().expect("relay socket addr");
        let (sent_tx, mut sent_rx) = mpsc::unbounded_channel();
        let (_downlink_tx, downlink_rx) = mpsc::unbounded_channel();
        let mut carrier = RecordingUdpCarrier { sent: sent_tx, downlink: tokio::sync::Mutex::new(downlink_rx) };
        let telemetry = FakeSocksTelemetry::default();
        let config = silent_session_config(Duration::from_secs(300));
        let cancel = CancellationToken::new();
        let pump_cancel = cancel.clone();

        let pump = tokio::spawn(async move {
            pump_udp_association(
                control,
                IpAddr::V4(Ipv4Addr::LOCALHOST),
                relay_socket,
                &mut carrier,
                &config,
                &telemetry,
                cancel,
            )
            .await
        });

        let client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind SOCKS client socket");
        client.send_to(&[0x05, 0x00], relay_addr).await.expect("send malformed frame");
        let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 9));
        let frame = crate::socks::encode_udp_frame(&target, b"after-malformed").expect("encode valid frame");
        client.send_to(&frame, relay_addr).await.expect("send valid frame");

        let forwarded = tokio::time::timeout(Duration::from_secs(2), sent_rx.recv())
            .await
            .expect("valid frame never reached the carrier after a malformed one");
        assert_eq!(Some(b"after-malformed".to_vec()), forwarded);

        pump_cancel.cancel();
        let outcome = tokio::time::timeout(Duration::from_secs(2), pump)
            .await
            .expect("pump task hung after cancellation")
            .expect("pump task panicked");
        outcome.result.expect("a dropped malformed frame must not end the association as an error");
        assert_eq!("runtime_cancelled", outcome.termination_reason);
        drop(control_peer);
    }
    #[tokio::test]
    async fn udp_pump_pins_the_first_client_socket_and_drops_foreign_sources() {
        let (control, control_peer) = duplex(64);
        let relay_socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind relay UDP socket");
        let relay_addr = relay_socket.local_addr().expect("relay socket addr");
        let (sent_tx, mut sent_rx) = mpsc::unbounded_channel();
        let (_downlink_tx, downlink_rx) = mpsc::unbounded_channel();
        let mut carrier = RecordingUdpCarrier { sent: sent_tx, downlink: tokio::sync::Mutex::new(downlink_rx) };
        let telemetry = FakeSocksTelemetry::default();
        let config = silent_session_config(Duration::from_secs(300));
        let cancel = CancellationToken::new();
        let pump_cancel = cancel.clone();

        let pump = tokio::spawn(async move {
            pump_udp_association(
                control,
                IpAddr::V4(Ipv4Addr::LOCALHOST),
                relay_socket,
                &mut carrier,
                &config,
                &telemetry,
                cancel,
            )
            .await
        });

        let pinned_client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind pinned client socket");
        let foreign_client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind foreign client socket");
        let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 9));

        let pinned_frame = crate::socks::encode_udp_frame(&target, b"from-pinned").expect("encode pinned frame");
        pinned_client.send_to(&pinned_frame, relay_addr).await.expect("send pinned frame");
        let forwarded = tokio::time::timeout(Duration::from_secs(2), sent_rx.recv())
            .await
            .expect("pinned client frame never reached the carrier");
        assert_eq!(Some(b"from-pinned".to_vec()), forwarded);

        let foreign_frame = crate::socks::encode_udp_frame(&target, b"from-foreign").expect("encode foreign frame");
        foreign_client.send_to(&foreign_frame, relay_addr).await.expect("send foreign frame");
        tokio::time::sleep(Duration::from_millis(150)).await;
        assert!(
            matches!(sent_rx.try_recv(), Err(mpsc::error::TryRecvError::Empty)),
            "a datagram from a foreign source socket must never reach the carrier"
        );

        pump_cancel.cancel();
        let outcome = tokio::time::timeout(Duration::from_secs(2), pump)
            .await
            .expect("pump task hung after cancellation")
            .expect("pump task panicked");
        outcome.result.expect("pump must end gracefully after pinning");
        assert_eq!("runtime_cancelled", outcome.termination_reason);
        drop(control_peer);
    }
    #[tokio::test]
    async fn udp_pump_keeps_serving_after_a_failed_downlink_delivery() {
        let (control, control_peer) = duplex(64);
        let (sent_tx, mut sent_rx) = mpsc::unbounded_channel();
        let (downlink_tx, downlink_rx) = mpsc::unbounded_channel();
        let (uplink_tx, uplink_rx) = mpsc::unbounded_channel();
        let sends_attempted = Arc::new(AtomicU64::new(0));
        let mut carrier = RecordingUdpCarrier { sent: sent_tx, downlink: tokio::sync::Mutex::new(downlink_rx) };
        let telemetry = FakeSocksTelemetry::default();
        let config = silent_session_config(Duration::from_secs(300));
        let cancel = CancellationToken::new();
        let pump_cancel = cancel.clone();
        let socket = FailingDownlinkSocket {
            uplink: tokio::sync::Mutex::new(uplink_rx),
            sends_attempted: Arc::clone(&sends_attempted),
        };

        let pump = tokio::spawn(async move {
            pump_udp_association(
                control,
                IpAddr::V4(Ipv4Addr::LOCALHOST),
                socket,
                &mut carrier,
                &config,
                &telemetry,
                cancel,
            )
            .await
        });

        let pinned_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 40_000);
        let target = RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 9));
        let ping = crate::socks::encode_udp_frame(&target, b"ping").expect("encode ping frame");
        uplink_tx.send((ping, pinned_addr)).expect("script uplink datagram");
        let forwarded = tokio::time::timeout(Duration::from_secs(2), sent_rx.recv())
            .await
            .expect("pinned client frame never reached the carrier");
        assert_eq!(Some(b"ping".to_vec()), forwarded);

        downlink_tx.send((target, b"reply".to_vec())).expect("script downlink datagram");
        tokio::time::sleep(Duration::from_millis(150)).await;
        assert!(sends_attempted.load(Ordering::Relaxed) >= 1, "downlink delivery must have been attempted");

        pump_cancel.cancel();
        let outcome = tokio::time::timeout(Duration::from_secs(2), pump)
            .await
            .expect("pump task hung after cancellation")
            .expect("pump task panicked");
        outcome.result.expect("a failed downlink delivery must not end the association as an error");
        assert_eq!("runtime_cancelled", outcome.termination_reason);
        drop(control_peer);
        drop(uplink_tx);
        drop(downlink_tx);
    }
    #[tokio::test]
    async fn udp_pump_never_records_datagram_targets_regardless_of_backend_kind() {
        let (control, control_peer) = duplex(64);
        let relay_socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind relay UDP socket");
        let relay_addr = relay_socket.local_addr().expect("relay socket addr");
        let (sent_tx, _sent_rx) = mpsc::unbounded_channel();
        let (_downlink_tx, downlink_rx) = mpsc::unbounded_channel();
        let mut carrier = RecordingUdpCarrier { sent: sent_tx, downlink: tokio::sync::Mutex::new(downlink_rx) };
        let telemetry = Arc::new(FakeSocksTelemetry::default());
        let mut config = silent_session_config(Duration::from_secs(300));
        // A non-VLESS backend must follow the same per-datagram privacy rule as
        // VLESS XUDP: destinations never enter the runtime telemetry surface.
        config.backend_kind = "shadowsocks".to_string();
        let cancel = CancellationToken::new();
        let pump_cancel = cancel.clone();
        let pump_telemetry = Arc::clone(&telemetry);

        let pump = tokio::spawn(async move {
            pump_udp_association(
                control,
                IpAddr::V4(Ipv4Addr::LOCALHOST),
                relay_socket,
                &mut carrier,
                &config,
                pump_telemetry.as_ref(),
                cancel,
            )
            .await
        });

        let client = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind SOCKS client socket");
        let target = RelayTargetAddr::Domain("tracker.example".to_string(), 443);
        let frame = crate::socks::encode_udp_frame(&target, b"announce").expect("encode frame");
        client.send_to(&frame, relay_addr).await.expect("send uplink frame");
        tokio::time::sleep(Duration::from_millis(100)).await;

        assert!(
            telemetry.targets.lock().expect("target lock").is_empty(),
            "per-datagram UDP destinations must never reach runtime telemetry"
        );

        pump_cancel.cancel();
        let outcome = tokio::time::timeout(Duration::from_secs(2), pump)
            .await
            .expect("pump task hung after cancellation")
            .expect("pump task panicked");
        outcome.result.expect("pump must end gracefully");
        drop(control_peer);
    }
}
