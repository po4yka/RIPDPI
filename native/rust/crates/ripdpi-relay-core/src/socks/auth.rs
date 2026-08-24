use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio_util::sync::CancellationToken;
use tracing::Instrument;

use crate::backend::RelayBackend;
use crate::socks::connect::handle_connect;
use crate::socks::reply::write_reply;
use crate::socks::target::RelayTargetAddr;
use crate::socks::telemetry::{SocksSessionConfig, SocksTelemetry};
use crate::socks::udp::handle_udp_associate;

const SOCKS_HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
const SOCKS_HANDSHAKE_TIMEOUT_MESSAGE: &str = "relay SOCKS handshake timed out";

#[derive(Debug)]
enum PreReplyNegotiation {
    Cancelled,
    Request {
        command: u8,
        target: RelayTargetAddr,
    },
    /// The request itself is unparseable or uses an unsupported address type.
    /// A SOCKS failure reply is still written (best effort) so well-behaved
    /// clients observe a protocol-level rejection instead of a bare drop;
    /// RFC 1928 reserves REP `0x08` for an unsupported ATYP.
    Rejected {
        reply_code: u8,
        cause: io::Error,
    },
}

/// NOT cancel-safe: the deadline and cancellation arms may drop `read_exact` or `write_all` after partial progress. This is valid only before a SOCKS command success reply exists: the caller returns immediately and drops the client socket, so no established relay or UDP association is abandoned.
async fn negotiate_request<S>(
    client: &mut S,
    deadline: Duration,
    cancel: &CancellationToken,
) -> io::Result<PreReplyNegotiation>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let negotiation = async {
        negotiate_no_auth(client).await?;

        let mut request_header = [0u8; 4];
        client.read_exact(&mut request_header).await?;
        let rejected =
            |reply_code: u8, cause: io::Error| Ok::<_, io::Error>(PreReplyNegotiation::Rejected { reply_code, cause });
        if request_header[0] != 0x05 {
            return rejected(0x01, io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 request"));
        }

        let command = request_header[1];
        let target = match read_target(client, request_header[3]).await {
            Ok(target) => target,
            Err(error) => {
                // RFC 1928: an address type outside {IPv4, domain, IPv6} gets
                // its own REP code; every other parse failure is a general
                // failure.
                let atyp_supported = matches!(request_header[3], 0x01 | 0x03 | 0x04);
                return rejected(if atyp_supported { 0x01 } else { 0x08 }, error);
            }
        };
        Ok::<_, io::Error>(PreReplyNegotiation::Request { command, target })
    };

    tokio::select! {
        biased;
        () = cancel.cancelled() => Ok(PreReplyNegotiation::Cancelled),
        result = negotiation => result,
        () = tokio::time::sleep(deadline) => {
            Err(io::Error::new(io::ErrorKind::TimedOut, SOCKS_HANDSHAKE_TIMEOUT_MESSAGE))
        }
    }
}

/// Negotiate the SOCKS5 method, parse the request, and dispatch the command.
///
/// # Cancel safety
///
/// NOT cancel-safe if externally dropped after a command success reply. `runtime/session.rs` therefore awaits it directly and this function owns every cancellation point through the session shutdown token.
///
/// - The **pre-reply** phase (method negotiation, request header, target parse) is raced against `cancel` and a fixed deadline. Those arms may drop non-cancel-safe `read_exact`/`write_all` operations after partial progress, which is valid only because no command success reply exists and this function immediately returns so the client socket is dropped.
/// - The timeout scope ends before command dispatch. The **post-reply** phase is delegated to [`handle_connect`] / [`handle_udp_associate`], each of which threads `cancel` through and keeps its own success-reply-to-relay window atomic.
pub(crate) async fn handle_client<T>(
    mut client: TcpStream,
    backend: Arc<RelayBackend>,
    config: SocksSessionConfig,
    telemetry: &T,
    cancel: CancellationToken,
) -> io::Result<()>
where
    T: SocksTelemetry + ?Sized,
{
    let (command, target) = match negotiate_request(&mut client, SOCKS_HANDSHAKE_TIMEOUT, &cancel).await {
        Ok(PreReplyNegotiation::Cancelled) => return Ok(()),
        Ok(PreReplyNegotiation::Request { command, target }) => (command, target),
        Ok(PreReplyNegotiation::Rejected { reply_code, cause }) => {
            telemetry.record_handshake_error(cause.to_string());
            // Best effort: a client that already went away must not turn the
            // rejection into a different error.
            let _ = write_reply(&mut client, reply_code, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await;
            return Err(cause);
        }
        Err(error) => {
            if error.kind() == io::ErrorKind::TimedOut {
                telemetry.record_handshake_error(error.to_string());
            }
            return Err(error);
        }
    };
    match command {
        0x01 => {
            telemetry.record_target(target.to_string());
            let traced = config.backend_kind == "vless_reality";
            let attempt_id = telemetry.next_attempt_id();
            handle_connect(
                client,
                backend,
                target,
                config.confirm_good_eligible,
                traced,
                config.timeouts,
                telemetry,
                cancel,
            )
            .instrument(tracing::info_span!("relay_attempt", attempt_id))
            .await
        }
        0x03 => handle_udp_associate(client, backend, config, telemetry, cancel).await,
        _ => {
            write_reply(&mut client, 0x07, SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
            Err(io::Error::new(io::ErrorKind::Unsupported, format!("SOCKS5 command {command:#x} is not supported")))
        }
    }
}

/// Reads the SOCKS5 method-selection greeting and performs RFC 1928 method
/// negotiation. Replies `[0x05, 0x00]` (NO AUTH) when the client offers
/// method `0x00`; replies `[0x05, 0xFF]` (NO ACCEPTABLE METHODS) and returns
/// an error otherwise, as required by RFC 1928 §3.
///
/// # Cancel safety
///
/// NOT cancel-safe in isolation: it reads the greeting then writes the server's method choice; a cancel between the read and the write leaves the client without a method reply. The production caller, [`negotiate_request`], drives it inside a pre-reply `select!`; abandoning it there is valid because no SOCKS5 command reply has been written and the client socket is immediately dropped. Do not call it under a cancellation scope that expects a clean handshake after a partial run.
async fn negotiate_no_auth<S>(stream: &mut S) -> io::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut greeting = [0u8; 2];
    stream.read_exact(&mut greeting).await?;
    if greeting[0] != 0x05 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 version"));
    }

    let method_count = usize::from(greeting[1]);
    let mut methods = vec![0u8; method_count];
    stream.read_exact(&mut methods).await?;

    if methods.contains(&0x00) {
        stream.write_all(&[0x05, 0x00]).await?;
        Ok(())
    } else {
        // RFC 1928 §3: if no offered method is acceptable the server replies
        // with 0xFF and MUST close the connection.
        stream.write_all(&[0x05, 0xFF]).await?;
        Err(io::Error::new(io::ErrorKind::PermissionDenied, "SOCKS5 client offered no acceptable auth method"))
    }
}

/// Reads the SOCKS5 target address (IPv4 / domain / IPv6) following the request
/// header, per RFC 1928 §4.
///
/// # Cancel safety
///
/// NOT cancel-safe in isolation: `read_exact` may consume a partial target before cancellation. Its production caller only abandons it during pre-reply negotiation and immediately drops the client socket, so those consumed bytes are never reused.
pub(crate) async fn read_target<S>(stream: &mut S, address_type: u8) -> io::Result<RelayTargetAddr>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let target = match address_type {
        0x01 => {
            let mut octets = [0u8; 4];
            stream.read_exact(&mut octets).await?;
            let mut port_bytes = [0u8; 2];
            stream.read_exact(&mut port_bytes).await?;
            RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(octets)), u16::from_be_bytes(port_bytes)))
        }
        0x03 => {
            let len = stream.read_u8().await?;
            let mut host = vec![0u8; usize::from(len)];
            stream.read_exact(&mut host).await?;
            let mut port_bytes = [0u8; 2];
            stream.read_exact(&mut port_bytes).await?;
            let host = String::from_utf8(host)
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid SOCKS5 domain target"))?;
            if host.is_empty()
                || host.chars().any(|c| c.is_whitespace() || c.is_control())
                || host.starts_with('.')
                || host.ends_with('.')
            {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "invalid SOCKS5 domain target: empty or malformed host",
                ));
            }
            RelayTargetAddr::Domain(host, u16::from_be_bytes(port_bytes))
        }
        0x04 => {
            let mut octets = [0u8; 16];
            stream.read_exact(&mut octets).await?;
            let mut port_bytes = [0u8; 2];
            stream.read_exact(&mut port_bytes).await?;
            RelayTargetAddr::Ip(SocketAddr::new(IpAddr::V6(Ipv6Addr::from(octets)), u16::from_be_bytes(port_bytes)))
        }
        _ => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("unsupported SOCKS5 address type {address_type:#x}"),
            ));
        }
    };
    Ok(target)
}

#[cfg(test)]
mod tests {
    use std::io;
    use std::net::{Ipv4Addr, SocketAddr};
    use std::sync::{Arc, Mutex};
    use std::time::Duration;

    use tokio::io::{AsyncReadExt, AsyncWriteExt, duplex};
    use tokio_util::sync::CancellationToken;

    use super::{PreReplyNegotiation, negotiate_no_auth, negotiate_request};
    use crate::backend::RelayBackend;
    use crate::socks::target::RelayTargetAddr;
    use crate::socks::telemetry::{RelaySessionTimeouts, SocksSessionConfig, SocksTelemetry};

    #[tokio::test]
    async fn stalled_pre_reply_negotiation_times_out() {
        let (_client_end, mut server_end) = duplex(64);
        let cancel = CancellationToken::new();

        let error = negotiate_request(&mut server_end, Duration::from_millis(10), &cancel)
            .await
            .expect_err("stalled negotiation must time out");

        assert_eq!(io::ErrorKind::TimedOut, error.kind());
    }

    #[tokio::test]
    async fn pre_cancelled_negotiation_wins_over_deadline() {
        let (_client_end, mut server_end) = duplex(64);
        let cancel = CancellationToken::new();
        cancel.cancel();

        let outcome =
            negotiate_request(&mut server_end, Duration::ZERO, &cancel).await.expect("cancellation must be clean");

        assert!(matches!(outcome, PreReplyNegotiation::Cancelled));
    }

    #[tokio::test]
    async fn complete_no_auth_connect_request_parses_before_deadline() {
        let (mut client_end, mut server_end) = duplex(64);
        let cancel = CancellationToken::new();
        client_end
            .write_all(&[0x05, 0x01, 0x00, 0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, 0x1f, 0x90])
            .await
            .expect("write complete greeting and request");

        let outcome = negotiate_request(&mut server_end, Duration::from_millis(100), &cancel)
            .await
            .expect("complete negotiation must parse");

        assert!(matches!(
            outcome,
            PreReplyNegotiation::Request {
                command: 0x01,
                target: RelayTargetAddr::Ip(address),
            } if address == SocketAddr::from((Ipv4Addr::LOCALHOST, 8080))
        ));
        let mut method_reply = [0_u8; 2];
        client_end.read_exact(&mut method_reply).await.expect("read NO AUTH reply");
        assert_eq!([0x05, 0x00], method_reply);
    }

    #[tokio::test]
    async fn socks5_accepts_noauth_offer() {
        // Client offers only NO-AUTH (0x00); server must reply [0x05, 0x00].
        let (mut client_end, mut server_end) = duplex(64);

        // Write greeting: VER=5, NMETHODS=1, METHOD=0x00
        tokio::io::AsyncWriteExt::write_all(&mut client_end, &[0x05, 0x01, 0x00]).await.expect("write greeting");

        negotiate_no_auth(&mut server_end).await.expect("NO-AUTH must be accepted");

        let mut reply = [0u8; 2];
        tokio::io::AsyncReadExt::read_exact(&mut client_end, &mut reply).await.expect("read reply");
        assert_eq!(reply, [0x05, 0x00], "server must reply with method 0x00 (NO AUTH)");
    }

    #[tokio::test]
    async fn socks5_rejects_greeting_without_noauth() {
        // Client offers only user/pass (0x02); server must reply [0x05, 0xFF] and error.
        let (mut client_end, mut server_end) = duplex(64);

        // Write greeting: VER=5, NMETHODS=1, METHOD=0x02 (username/password)
        tokio::io::AsyncWriteExt::write_all(&mut client_end, &[0x05, 0x01, 0x02]).await.expect("write greeting");

        let result = negotiate_no_auth(&mut server_end).await;
        assert!(result.is_err(), "server must reject greeting without NO-AUTH");

        let mut reply = [0u8; 2];
        tokio::io::AsyncReadExt::read_exact(&mut client_end, &mut reply).await.expect("read reply");
        assert_eq!(reply, [0x05, 0xFF], "server must reply with 0xFF (NO ACCEPTABLE METHODS)");
    }
    #[derive(Default)]
    struct RecordingTelemetry {
        targets: Mutex<Vec<String>>,
    }

    impl SocksTelemetry for RecordingTelemetry {
        fn next_attempt_id(&self) -> u64 {
            0
        }

        fn record_target(&self, target: String) {
            self.targets.lock().expect("target lock").push(target);
        }

        fn record_handshake_error(&self, _error: String) {}
    }

    /// Characterization guard for the user-visible `lastTarget` surface: a
    /// CONNECT authority is recorded exactly once, before dispatch.
    #[tokio::test]
    async fn connect_command_records_the_target_authority_in_telemetry() {
        let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("bind test listener");
        let listener_addr = listener.local_addr().expect("listener addr");
        let mut client_end = tokio::net::TcpStream::connect(listener_addr).await.expect("connect test client");
        let (server_end, _) = listener.accept().await.expect("accept test server");
        client_end
            .write_all(&[0x05, 0x01, 0x00, 0x05, 0x01, 0x00, 0x01, 203, 0, 113, 5, 0x01, 0xbb])
            .await
            .expect("write greeting and CONNECT request");

        let telemetry = RecordingTelemetry::default();
        let config = SocksSessionConfig {
            local_socks_host: "127.0.0.1".to_string(),
            backend_kind: "trojan".to_string(),
            confirm_good_eligible: false,
            timeouts: RelaySessionTimeouts::production(),
        };
        let backend = Arc::new(RelayBackend::Unsupported { kind: "trojan".to_string() });
        let cancel = CancellationToken::new();

        // The unsupported backend fails the dial after dispatch; the target
        // must already be recorded by then.
        let _ = super::handle_client(server_end, backend, config, &telemetry, cancel).await;

        assert_eq!(
            vec!["203.0.113.5:443".to_string()],
            *telemetry.targets.lock().expect("target lock"),
            "a CONNECT authority must populate the last-target telemetry marker"
        );
    }

    #[tokio::test]
    async fn unsupported_atyp_is_rejected_with_rep_08() {
        let (mut client_end, mut server_end) = duplex(64);
        let cancel = CancellationToken::new();
        client_end
            .write_all(&[0x05, 0x01, 0x00, 0x05, 0x01, 0x00, 0x09])
            .await
            .expect("write greeting and request with an unassigned ATYP");

        let outcome = tokio::time::timeout(
            Duration::from_secs(2),
            negotiate_request(&mut server_end, Duration::from_secs(5), &cancel),
        )
        .await
        .expect("negotiation hung")
        .expect("negotiation failed");

        match outcome {
            PreReplyNegotiation::Rejected { reply_code, cause } => {
                assert_eq!(0x08, reply_code, "an unassigned ATYP must map to RFC 1928 REP 0x08");
                assert!(cause.to_string().contains("address type"), "unexpected error: {cause}");
            }
            other => panic!("expected a rejection, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn empty_domain_target_is_rejected() {
        let (mut client_end, mut server_end) = duplex(64);
        let cancel = CancellationToken::new();
        // VER CMD RSV ATYP=domain, LEN=0, PORT.
        client_end
            .write_all(&[0x05, 0x01, 0x00, 0x05, 0x01, 0x00, 0x03, 0x00, 0x1f, 0x90])
            .await
            .expect("write greeting and request with an empty domain");

        let outcome = tokio::time::timeout(
            Duration::from_secs(2),
            negotiate_request(&mut server_end, Duration::from_secs(5), &cancel),
        )
        .await
        .expect("negotiation hung")
        .expect("negotiation failed");

        match outcome {
            PreReplyNegotiation::Rejected { reply_code, cause } => {
                assert_eq!(0x01, reply_code);
                assert!(cause.to_string().contains("malformed"), "unexpected error: {cause}");
            }
            other => panic!("expected a rejection, got {other:?}"),
        }
    }
}
