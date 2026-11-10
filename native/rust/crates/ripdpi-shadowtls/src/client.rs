use std::io;
use std::net::SocketAddr;
use std::os::fd::AsRawFd;
use std::time::Duration;

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

/// Connect budget for one server address except the last. A blackholed address
/// would otherwise wait for the kernel connect timeout, which is longer than the
/// relay connect deadline, and the next address would never be tried.
const PER_ADDRESS_CONNECT_TIMEOUT: Duration =
    if cfg!(test) { Duration::from_millis(500) } else { Duration::from_secs(8) };

/// Result of one failed address attempt.
#[derive(Debug)]
enum Attempt {
    /// The address is unusable; try the next one.
    Next(io::Error),
    /// A policy failure (socket protection); stop and report it.
    Abort(io::Error),
}

/// Run `attempt` on each address in order and return the first success.
///
/// Every attempt except the last gets `per_address` before the next address is
/// tried. [`Attempt::Abort`] stops at once. When every attempt fails, the last
/// error is returned.
///
/// # Cancel safety
///
/// cancel-safe: holds no state besides the pending attempt future, which is
/// dropped with this one.
async fn try_each_address<T, F, Fut>(
    candidates: Vec<SocketAddr>,
    per_address: Duration,
    mut attempt: F,
) -> io::Result<T>
where
    F: FnMut(SocketAddr) -> Fut,
    Fut: Future<Output = Result<T, Attempt>>,
{
    let count = candidates.len();
    let mut last_error: Option<io::Error> = None;
    for (index, address) in candidates.into_iter().enumerate() {
        let result = if index + 1 < count {
            match tokio::time::timeout(per_address, attempt(address)).await {
                Ok(result) => result,
                Err(_) => Err(Attempt::Next(io::Error::new(
                    io::ErrorKind::TimedOut,
                    format!("ShadowTLS connect to {address} timed out"),
                ))),
            }
        } else {
            attempt(address).await
        };
        match result {
            Ok(value) => return Ok(value),
            Err(Attempt::Abort(error)) => return Err(error),
            Err(Attempt::Next(error)) => last_error = Some(error),
        }
    }
    Err(last_error.unwrap_or_else(|| {
        io::Error::new(io::ErrorKind::AddrNotAvailable, "ShadowTLS server resolved to no addresses")
    }))
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

    /// Resolve `server`, connect to the first usable address, and run the
    /// ShadowTLS v3 handshake over it.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: each attempt's socket and the handshake state are owned by
    /// this future and close when it is dropped.
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
        let stream =
            try_each_address(candidates, PER_ADDRESS_CONNECT_TIMEOUT, |address| self.connect_address(address)).await?;
        self.connect_over(stream).await
    }

    /// Open a protected, optionally bound TCP connection to one candidate address.
    ///
    /// A protection failure is a policy failure, not an unreachable peer: it
    /// returns [`Attempt::Abort`], which stops the whole connect instead of
    /// trying the next address.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: the socket is owned by this future and closes on drop.
    async fn connect_address(&self, address: SocketAddr) -> Result<TcpStream, Attempt> {
        let socket = match address {
            SocketAddr::V4(_) => TcpSocket::new_v4(),
            SocketAddr::V6(_) => TcpSocket::new_v6(),
        }
        .map_err(Attempt::Next)?;
        protect_outbound_socket(&socket, address, self.config.socket_protection).map_err(Attempt::Abort)?;
        self.bind_and_connect(socket, address).await.map_err(Attempt::Next)
    }

    /// # Cancel safety
    ///
    /// cancel-safe: the socket is owned by this future and closes on drop.
    async fn bind_and_connect(&self, socket: TcpSocket, address: SocketAddr) -> io::Result<TcpStream> {
        // PROTECT INVARIANT: the carrier socket is protected before connect via the
        // in-process VpnService.protect registry (loopback-skipped, fail-closed under
        // a live TUN) — matching the ripdpi-vless / ripdpi-xhttp gold-standard
        // pattern. ShadowTLS is a standalone relay kind (transport_descriptor.rs
        // build_shadowtls), reachable under a live TUN; own-UID exclusion via
        // computeAppRoutingPlan remains the second layer. `connect_over` carries no
        // OS socket of its own. REL-1 / REL-3. See
        // .claude/rules/vpnservice-protect-invariant.md. `connect_address` protects
        // the socket before it calls this function.
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
                // HMAC failure in `verify_handshake_frame` instead. A TLS 1.2 cover,
                // whose plaintext Certificate records would also start 0x16 0x03, is
                // rejected earlier by `parse_validated_server_hello` (strict TLS 1.3).
                // So in practice only the synthetic `ShadowTlsV2RejectLoopback`
                // reaches this branch. The ServerHello is also 0x16 0x03 but is read and validated
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
    use std::net::{Ipv4Addr, SocketAddr};
    use std::sync::Mutex;
    use std::time::Duration;

    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    use super::{Attempt, try_each_address};
    use crate::Config;
    use crate::ShadowTlsClient;
    use crate::loopback::ShadowTlsLoopback;

    fn addr(port: u16) -> SocketAddr {
        SocketAddr::from((Ipv4Addr::LOCALHOST, port))
    }

    fn client() -> ShadowTlsClient {
        ShadowTlsClient::new(Config {
            password: "pw".to_string(),
            server_name: "localhost".to_string(),
            inner_profile_id: "default".to_string(),
            socket_protection: ripdpi_native_protect::SocketProtectionPolicy::Inactive,
            outbound_bind_ip: None,
        })
    }

    /// A refused first address falls through to the live second address.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: the test owns the server and the client stream.
    #[tokio::test]
    async fn dead_first_address_falls_back_to_live_address() {
        let dead = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind").local_addr().expect("addr");
        let server = ShadowTlsLoopback::start("pw".to_string()).await.expect("start loopback");
        let client = client();
        let tcp = try_each_address(vec![dead, server.local_addr()], Duration::from_secs(5), |address| {
            client.connect_address(address)
        })
        .await
        .expect("second address connects");
        let mut stream = client.connect_over(tcp).await.expect("handshake");
        stream.write_all(b"ping").await.expect("write");
        stream.flush().await.expect("flush");
        let mut buf = [0u8; 4];
        stream.read_exact(&mut buf).await.expect("read echo");
        assert_eq!(&buf, b"ping");
        server.shutdown().await;
    }

    /// A policy failure stops the walk; later addresses are not tried.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: the attempts are in-memory futures.
    #[tokio::test]
    async fn abort_stops_without_trying_next_address() {
        let tried = Mutex::new(Vec::new());
        let result: std::io::Result<()> = try_each_address(vec![addr(1), addr(2)], Duration::from_secs(5), |address| {
            tried.lock().expect("lock").push(address);
            async { Err(Attempt::Abort(std::io::Error::other("protect failed"))) }
        })
        .await;
        assert_eq!(result.expect_err("abort").to_string(), "protect failed");
        assert_eq!(*tried.lock().expect("lock"), vec![addr(1)]);
    }

    /// A blackholed address is bounded by the per-address budget, so the
    /// next address still gets its turn.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: the attempts are in-memory futures.
    #[tokio::test]
    async fn stalled_address_times_out_and_next_address_wins() {
        let result = try_each_address(vec![addr(1), addr(2)], Duration::from_millis(50), |address| async move {
            if address == addr(1) {
                std::future::pending::<()>().await;
            }
            Ok::<_, Attempt>(address)
        })
        .await;
        assert_eq!(result.expect("second address"), addr(2));
    }

    /// When every address fails, the caller sees the last error.
    ///
    /// # Cancel safety
    ///
    /// cancel-safe: the attempts are in-memory futures.
    #[tokio::test]
    async fn all_failures_return_last_error() {
        let result: std::io::Result<()> =
            try_each_address(vec![addr(1), addr(2)], Duration::from_secs(5), |address| async move {
                Err(Attempt::Next(std::io::Error::other(format!("refused {}", address.port()))))
            })
            .await;
        assert_eq!(result.expect_err("all fail").to_string(), "refused 2");
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
