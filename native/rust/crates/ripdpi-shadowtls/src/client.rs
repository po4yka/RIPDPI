use std::io;
use std::net::SocketAddr;
use std::os::fd::AsRawFd;

use rustls::ClientConnection;
use rustls::pki_types::ServerName;
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpSocket, TcpStream};

use crate::config::Config;
use crate::frames::{TLS_ALERT, TLS_APPLICATION_DATA, read_tls_frame, verify_handshake_frame};
use crate::handshake::{build_rustls_config, modify_client_hello, parse_validated_server_hello, read_client_hello};
use crate::hmac::ShadowTlsHmac;
use crate::stream::ShadowTlsStream;
use crate::{ShadowTlsFailureKind, ShadowTlsHandshakeError, classify_failure_payload};

/// Protect a freshly created outbound socket via the registered
/// `VpnService.protect()` callback before it connects to a non-loopback peer.
///
/// No-op for loopback. Fails closed for a non-loopback target when no callback
/// is registered (under a live TUN there is no other per-socket mechanism to
/// keep the socket out of the tunnel). Mirrors the `ripdpi-vless` gold-standard
/// helper. REL-1.
fn protect_outbound_socket<T: AsRawFd>(
    socket: &T,
    target: SocketAddr,
    socket_protection: ripdpi_native_protect::SocketProtectionPolicy,
) -> io::Result<()> {
    socket_protection
        .protect_non_loopback(socket.as_raw_fd(), target)
        .map_err(|error| io::Error::new(error.kind(), format!("protect ShadowTLS outbound socket: {error}")))
}

#[derive(Debug, Clone)]
pub struct ShadowTlsClient {
    config: Config,
}

impl ShadowTlsClient {
    pub fn new(config: Config) -> Self {
        Self { config }
    }

    pub fn config(&self) -> &Config {
        &self.config
    }

    pub async fn connect(&self, server: &str, server_port: i32) -> io::Result<ShadowTlsStream<TcpStream>> {
        let port = u16::try_from(server_port).map_err(|_| {
            io::Error::new(io::ErrorKind::InvalidInput, format!("invalid ShadowTLS port {server_port}"))
        })?;
        let addrs = self.config.socket_protection.resolve_host(server, port).await?;
        let candidates: Vec<SocketAddr> = match self.config.outbound_bind_ip {
            Some(bind_ip) => {
                let matching: Vec<SocketAddr> =
                    addrs.into_iter().filter(|addr| addr.is_ipv4() == bind_ip.is_ipv4()).collect();
                if matching.is_empty() {
                    return Err(io::Error::new(
                        io::ErrorKind::AddrNotAvailable,
                        "no server address matches outbound bind IP family",
                    ));
                }
                matching
            }
            None => addrs,
        };
        let mut last_error: Option<io::Error> = None;
        for address in candidates {
            match self.connect_address(address).await {
                Ok(stream) => return self.connect_over(stream).await,
                Err(error) => last_error = Some(error),
            }
        }
        Err(last_error.unwrap_or_else(|| {
            io::Error::new(io::ErrorKind::AddrNotAvailable, "ShadowTLS server resolved to no addresses")
        }))
    }

    /// Open a protected, optionally bound TCP connection to one candidate address.
    ///
    /// A protection failure is a policy failure, not an unreachable peer: it
    /// aborts the whole connect rather than trying the next address.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: the socket is owned by this future and closes on drop.
    async fn connect_address(&self, address: SocketAddr) -> io::Result<TcpStream> {
        // PROTECT INVARIANT: the carrier socket is protected before connect via the
        // in-process VpnService.protect registry (loopback-skipped, fail-closed under
        // a live TUN) — matching the ripdpi-vless / ripdpi-xhttp gold-standard
        // pattern. ShadowTLS is a standalone relay kind (transport_descriptor.rs
        // build_shadowtls), reachable under a live TUN; own-UID exclusion via
        // computeAppRoutingPlan remains the second layer. `connect_over` carries no
        // OS socket of its own. REL-1 / REL-3. See
        // .claude/rules/vpnservice-protect-invariant.md.
        let socket = match address {
            SocketAddr::V4(_) => TcpSocket::new_v4()?,
            SocketAddr::V6(_) => TcpSocket::new_v6()?,
        };
        protect_outbound_socket(&socket, address, self.config.socket_protection)?;
        if let Some(bind_ip) = self.config.outbound_bind_ip {
            socket.bind(SocketAddr::new(bind_ip, 0))?;
        }
        let stream = socket.connect(address).await?;
        stream.set_nodelay(true)?;
        Ok(stream)
    }

    pub async fn connect_over<S>(&self, mut stream: S) -> io::Result<ShadowTlsStream<S>>
    where
        S: AsyncRead + AsyncWrite + Unpin,
    {
        let tls_config = build_rustls_config();
        let server_name = ServerName::try_from(self.config.server_name.clone()).map_err(|error| {
            io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("invalid ShadowTLS server name {}: {error}", self.config.server_name),
            )
        })?;
        let mut client_conn = ClientConnection::new(tls_config, server_name)
            .map_err(|error| io::Error::other(format!("shadowtls rustls client init: {error}")))?;

        let initial_hmac = ShadowTlsHmac::new(self.config.password.as_bytes());
        let client_hello = read_client_hello(&mut client_conn)?;
        let modified_client_hello = modify_client_hello(&client_hello, &initial_hmac)?;
        stream.write_all(&modified_client_hello).await?;
        stream.flush().await?;

        let server_hello = read_tls_frame(&mut stream).await?;
        let parsed = parse_validated_server_hello(&server_hello)?;
        client_conn.read_tls(&mut std::io::Cursor::new(server_hello.as_slice()))?;
        client_conn.process_new_packets().map_err(|error| {
            io::Error::new(io::ErrorKind::InvalidData, format!("shadowtls process ServerHello: {error}"))
        })?;

        let mut handshake_hmac = initial_hmac.clone();
        handshake_hmac.update(&parsed.server_random);

        let mut client_data_hmac = handshake_hmac.clone();
        client_data_hmac.update(b"C");

        let mut server_data_hmac = handshake_hmac.clone();
        server_data_hmac.update(b"S");

        drive_handshake_to_application_data(&mut stream, &mut client_conn, &mut handshake_hmac).await?;
        Ok(ShadowTlsStream::new(stream, server_data_hmac, client_data_hmac, Some(handshake_hmac)))
    }
}

async fn drive_handshake_to_application_data<S>(
    stream: &mut S,
    client_conn: &mut ClientConnection,
    handshake_hmac: &mut ShadowTlsHmac,
) -> io::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    loop {
        if client_conn.wants_write() {
            let mut frame = Vec::with_capacity(1024);
            client_conn
                .write_tls(&mut frame)
                .map_err(|error| io::Error::other(format!("shadowtls write TLS handshake frame: {error}")))?;
            if !frame.is_empty() {
                stream.write_all(&frame).await?;
                stream.flush().await?;
                continue;
            }
        }

        let frame = read_tls_frame(stream).await?;
        match frame.first().copied() {
            Some(TLS_APPLICATION_DATA) => {
                verify_handshake_frame(handshake_hmac, &frame)?;
                return Ok(());
            }
            Some(TLS_ALERT) => {
                return Err(io::Error::new(
                    io::ErrorKind::ConnectionAborted,
                    "ShadowTLS handshake server returned TLS alert before switch",
                ));
            }
            Some(_) => {
                // Version-mismatch seam: at this switch point a v3 server sends the
                // HMAC-authenticated TLS_APPLICATION_DATA (0x17) frame. A raw TLS
                // handshake record (0x16 0x03 ..) here is classified as a v2-framing
                // signal. Upstream v2 relays the cover TLS flight unchanged
                // (server.rs relay_v2 @02dd0bc7), so with a TLS 1.3 cover a real v2
                // server sends 0x14/0x17 records here, never 0x16, and surfaces as the
                // HMAC failure in `verify_handshake_frame` instead. This branch is
                // harmless but only the synthetic `ShadowTlsV2RejectLoopback` reaches
                // it. The ServerHello is also 0x16 0x03 but is read and validated
                // BEFORE this loop. A bad-password reject sends a 0x17 frame whose
                // HMAC fails and classifies as `Other`, so auth failures are not
                // false-positived.
                if classify_failure_payload(&frame) == ShadowTlsFailureKind::VersionMismatch {
                    return Err(io::Error::other(ShadowTlsHandshakeError::version_mismatch()));
                }
                client_conn.read_tls(&mut std::io::Cursor::new(frame.as_slice()))?;
                client_conn.process_new_packets().map_err(|error| {
                    io::Error::new(io::ErrorKind::InvalidData, format!("shadowtls process handshake frame: {error}"))
                })?;
            }
            None => {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "ShadowTLS handshake server returned an empty frame",
                ));
            }
        }
    }
}

#[cfg(test)]
mod connect_tests {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    use crate::Config;
    use crate::ShadowTlsClient;
    use crate::loopback::ShadowTlsLoopback;

    /// `localhost` can resolve to `::1` first while the loopback server listens
    /// on IPv4 only, so a working connect needs the next resolved address.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: the test owns the server and the client stream.
    #[tokio::test]
    async fn connect_falls_back_to_next_resolved_address() {
        let server = ShadowTlsLoopback::start("pw".to_string()).await.expect("start loopback");
        let client = ShadowTlsClient::new(Config {
            password: "pw".to_string(),
            server_name: "localhost".to_string(),
            inner_profile_id: "default".to_string(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        });
        let mut stream = client.connect("localhost", i32::from(server.local_addr().port())).await.expect("connect");
        stream.write_all(b"ping").await.expect("write");
        stream.flush().await.expect("flush");
        let mut buf = [0u8; 4];
        stream.read_exact(&mut buf).await.expect("read echo");
        assert_eq!(&buf, b"ping");
        server.shutdown().await;
    }
}

#[cfg(test)]
mod protect_tests {
    use std::net::{Ipv4Addr, SocketAddr, TcpListener};
    use std::os::fd::{AsRawFd, RawFd};
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::{Arc, Mutex};

    use ripdpi_native_protect::{ProtectCallback, register_protect_callback, unregister_protect_callback};

    use super::{io, protect_outbound_socket};

    static TEST_LOCK: Mutex<()> = Mutex::new(());

    struct RecordingCallback {
        last_fd: AtomicI32,
    }

    impl ProtectCallback for RecordingCallback {
        fn protect(&self, fd: RawFd) -> io::Result<()> {
            self.last_fd.store(fd, Ordering::Release);
            Ok(())
        }
    }

    fn non_loopback() -> SocketAddr {
        SocketAddr::from((Ipv4Addr::new(203, 0, 113, 7), 443))
    }

    #[test]
    fn non_loopback_without_callback_fails_closed() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        let err = protect_outbound_socket(
            &listener,
            non_loopback(),
            ripdpi_native_protect::SocketProtectionPolicy::VpnRequired,
        )
        .expect_err("must fail closed");
        assert_eq!(err.kind(), io::ErrorKind::NotConnected);
    }

    #[test]
    fn non_loopback_is_protected_via_callback() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        protect_outbound_socket(&listener, non_loopback(), ripdpi_native_protect::SocketProtectionPolicy::VpnRequired)
            .expect("protect succeeds");
        assert_eq!(cb.last_fd.load(Ordering::Acquire), listener.as_raw_fd());
        unregister_protect_callback();
    }

    #[test]
    fn loopback_is_not_protected() {
        let _g = TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        unregister_protect_callback();
        let cb = Arc::new(RecordingCallback { last_fd: AtomicI32::new(-1) });
        register_protect_callback(Arc::clone(&cb) as Arc<dyn ProtectCallback>);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("listener");
        protect_outbound_socket(
            &listener,
            SocketAddr::from((Ipv4Addr::LOCALHOST, 1)),
            ripdpi_native_protect::SocketProtectionPolicy::VpnRequired,
        )
        .expect("loopback no-op");
        assert_eq!(cb.last_fd.load(Ordering::Acquire), -1);
        unregister_protect_callback();
    }
}
