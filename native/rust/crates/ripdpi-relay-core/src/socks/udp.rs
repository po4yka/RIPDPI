use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;

use tokio::io::AsyncReadExt;
use tokio::net::{TcpStream, UdpSocket};
use tokio_util::sync::CancellationToken;

use crate::backend::RelayBackend;
use crate::socks::reply::write_reply;
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

/// Drive a SOCKS5 `UDP ASSOCIATE`: bind the relay socket, reply, then pump
/// datagrams in both directions until the control connection or `cancel` ends
/// the session.
///
/// # Cancel safety
///
/// Cancel-safe. `cancel` is the session's shutdown token. The relay loop's
/// `select!` is `biased` with the two teardown arms first — `cancel.cancelled()`
/// (arm 0) and the control-connection EOF (arm 1) — so sustained upstream UDP
/// on the recv arms can never starve teardown (the original fairness hazard).
/// `cancel` leads `control_closed` because the historic outer `select!` that
/// dropped this future on shutdown was removed (see `runtime/session.rs`); this
/// loop is now the sole place shutdown is observed, so it must lead. Teardown is
/// at the `select!` boundary only: once a recv arm has been selected, its body
/// runs a follow-on message-atomic `send_to().await` (relay→client or
/// client→relay). VLESS/XUDP sends are executed by a bounded writer task with a
/// terminal deadline, so a stalled Reality carrier cannot defer cancellation
/// indefinitely or be reused after a partial frame. The success reply
/// (`REP=0x00`) and the first loop poll are separated by
/// no externally-cancellable drop point, so a confirmed `ASSOCIATE` always enters
/// the pump.
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

    let control_ip = client.peer_addr()?.ip();
    let mut associated_client = None;
    let mut udp_buffer = vec![0u8; UDP_BUFFER_SIZE];
    let mut control_probe = [0u8; 1];
    let control_closed = async {
        let _ = client.read(&mut control_probe).await;
    };
    tokio::pin!(control_closed);

    loop {
        tokio::select! {
            biased;
            () = cancel.cancelled() => {
                if let Some(state) = &mut xudp_telemetry {
                    state.set_termination_reason("runtime_cancelled");
                }
                break;
            },
            _ = &mut control_closed => {
                if let Some(state) = &mut xudp_telemetry {
                    state.set_termination_reason("control_closed");
                }
                break;
            },
            recv = udp_socket.recv_from(&mut udp_buffer) => {
                let (received, source) = recv?;
                if source.ip() != control_ip {
                    continue;
                }
                associated_client = Some(source);
                let (target, payload) = decode_udp_frame(&udp_buffer[..received])?;
                // XUDP may carry DNS and arbitrary per-datagram destinations.
                // Keep those endpoints out of the runtime telemetry surface;
                // the backend kind and aggregate session counters remain enough
                // to diagnose the transport without exposing user traffic.
                if config.backend_kind != "vless_reality" {
                    telemetry.record_target(target.to_string());
                }
                if let Err(error) = udp_session.send_to(&target, payload).await {
                    if let Some(state) = &mut xudp_telemetry {
                        state.set_termination_reason(if error.kind() == io::ErrorKind::TimedOut {
                            "write_timeout"
                        } else {
                            "write_error"
                        });
                        telemetry.record_xudp_write_failure(error.kind() == io::ErrorKind::TimedOut);
                    }
                    telemetry.record_handshake_error(error.to_string());
                    return Err(error);
                }
                if is_xudp {
                    telemetry.record_xudp_uplink(payload.len(), udp_session.queue_high_water_mark());
                }
            }
            result = udp_session.recv_from() => {
                let (target, payload) = match result {
                    Ok(datagram) => datagram,
                    Err(error) => {
                        if let Some(state) = &mut xudp_telemetry {
                            state.set_termination_reason(if error.kind() == io::ErrorKind::TimedOut {
                                "read_timeout"
                            } else {
                                "read_error"
                            });
                            telemetry.record_xudp_read_failure(error.kind() == io::ErrorKind::TimedOut);
                        }
                        return Err(error);
                    }
                };
                if is_xudp {
                    telemetry.record_xudp_downlink(payload.len());
                }
                let Some(destination) = associated_client else {
                    continue;
                };
                let frame = encode_udp_frame(&target, &payload)?;
                udp_socket.send_to(&frame, destination).await?;
            }
        }
    }

    Ok(())
}
