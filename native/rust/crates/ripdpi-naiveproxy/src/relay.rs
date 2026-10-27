use std::future::Future;
use std::io;
use std::sync::Arc;
use std::time::Duration;

use tokio::io::copy_bidirectional;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Semaphore;
use tokio::time::Instant;

use crate::config::NaiveProxyConfig;
use crate::connect_tunnel::{AsyncTunnel, open_https_connect_tunnel};
use crate::errors::{connection_failure_line, emit_structured_error};
use crate::http_front::{read_http_connect_request, write_http_connect_failure, write_http_connect_success};
use crate::socks5::{SocksTarget, negotiate_socks5, read_socks5_request, write_socks_reply};

const STRUCTURED_READY_PREFIX: &str = "RIPDPI-READY|naiveproxy|";
/// Budget for the local client to send its whole SOCKS5 or HTTP CONNECT
/// request. A client that connects and stays silent is closed after it.
const CLIENT_SETUP_TIMEOUT: Duration = Duration::from_secs(10);
/// Budget for DNS, TCP, TLS, the H2 handshake, CONNECT and the upgrade. A
/// censored path that drops the ClientHello fails the CONNECT after it,
/// instead of holding the client and upstream sockets until the kernel gives
/// up on retransmits.
const UPSTREAM_OPEN_TIMEOUT: Duration = Duration::from_secs(20);

/// Runs `future` with a deadline and maps expiry to `TimedOut`.
///
/// # Cancel safety
///
/// cancel-safe if `future` is: dropping it drops `future`.
async fn before<T>(deadline: Instant, phase: &str, future: impl Future<Output = io::Result<T>>) -> io::Result<T> {
    tokio::time::timeout_at(deadline, future)
        .await
        .map_err(|_elapsed| io::Error::new(io::ErrorKind::TimedOut, format!("naiveproxy {phase} timed out")))?
}

/// Opens the upstream tunnel within [`UPSTREAM_OPEN_TIMEOUT`].
///
/// A failure is reported as a `RIPDPI-ERROR` line: DNS, TLS, auth and CONNECT
/// failures of the server are what the service classifies. Client-side
/// failures are not, see [`connection_failure_line`].
///
/// # Cancel safety
///
/// cancel-safe: dropping it drops the partly opened upstream connection.
async fn open_upstream(config: &NaiveProxyConfig, target: &SocksTarget) -> io::Result<Box<dyn AsyncTunnel>> {
    let opened =
        before(Instant::now() + UPSTREAM_OPEN_TIMEOUT, "upstream open", open_https_connect_tunnel(config, target))
            .await;
    if let Err(error) = &opened {
        emit_structured_error(error);
    }
    opened
}

pub(crate) async fn run(config: NaiveProxyConfig) -> io::Result<()> {
    let listener = TcpListener::bind(&config.listen).await?;
    println!("{STRUCTURED_READY_PREFIX}{}", env!("CARGO_PKG_VERSION"));

    serve_listener(listener, Arc::new(config)).await
}

/// Most client connections served at once; each holds a client socket and an
/// upstream TCP/TLS/H2 connection. Further clients are closed at once, so they
/// fail fast instead of waiting in the listen backlog for a tunnel to end.
const MAX_CONCURRENT_TUNNELS: usize = 256;
/// Pause after an accept failure caused by resource exhaustion.
const ACCEPT_RETRY_DELAY: Duration = Duration::from_secs(1);

#[cfg(any(target_os = "linux", target_os = "android"))]
const ENOBUFS: i32 = 105;
#[cfg(not(any(target_os = "linux", target_os = "android")))]
const ENOBUFS: i32 = 55;
const ENOMEM: i32 = 12;
const ENFILE: i32 = 23;
const EMFILE: i32 = 24;

pub(crate) async fn serve_listener(listener: TcpListener, config: Arc<NaiveProxyConfig>) -> io::Result<()> {
    serve_listener_with_limit(listener, config, MAX_CONCURRENT_TUNNELS).await
}

/// Serves at most `limit` clients at once and closes clients past it. An
/// accept failure of one pending connection or a resource shortage does not
/// end the helper.
///
/// # Cancel safety
///
/// cancel-safe: dropping it stops accepting; spawned client tasks run on.
async fn serve_listener_with_limit(
    listener: TcpListener,
    config: Arc<NaiveProxyConfig>,
    limit: usize,
) -> io::Result<()> {
    let permits = Arc::new(Semaphore::new(limit));
    let mut at_limit = false;
    loop {
        let socket = match listener.accept().await {
            Ok((socket, _)) => socket,
            Err(error) => match accept_retry_delay(&error) {
                Some(delay) => {
                    if !delay.is_zero() {
                        eprintln!("naiveproxy accept paused: resources exhausted");
                    }
                    tokio::time::sleep(delay).await;
                    continue;
                }
                None => return Err(error),
            },
        };
        let Ok(permit) = Arc::clone(&permits).try_acquire_owned() else {
            if !at_limit {
                eprintln!("naiveproxy tunnel limit reached: refusing new clients");
            }
            at_limit = true;
            drop(socket);
            continue;
        };
        at_limit = false;
        let config = Arc::clone(&config);
        tokio::spawn(async move {
            let _permit = permit;
            if let Err(error) = handle_client(socket, config).await {
                eprintln!("{}", connection_failure_line(&error));
            }
        });
    }
}

/// How long to wait before the next accept after `error`, or `None` when the
/// listener itself is broken. A connection that died in the backlog is
/// skipped at once; a descriptor or memory shortage is retried after
/// [`ACCEPT_RETRY_DELAY`], as upstream does.
fn accept_retry_delay(error: &io::Error) -> Option<Duration> {
    match error.kind() {
        io::ErrorKind::ConnectionAborted
        | io::ErrorKind::ConnectionReset
        | io::ErrorKind::Interrupted
        | io::ErrorKind::WouldBlock => Some(Duration::ZERO),
        io::ErrorKind::OutOfMemory => Some(ACCEPT_RETRY_DELAY),
        _ if matches!(error.raw_os_error(), Some(EMFILE | ENFILE | ENOMEM | ENOBUFS)) => Some(ACCEPT_RETRY_DELAY),
        _ => None,
    }
}

async fn handle_client(client: TcpStream, config: Arc<NaiveProxyConfig>) -> io::Result<()> {
    let setup_deadline = Instant::now() + CLIENT_SETUP_TIMEOUT;
    let mut first_byte = [0u8; 1];
    before(setup_deadline, "client request", client.peek(&mut first_byte)).await?;
    if first_byte[0] == 0x05 {
        return handle_socks5_client(client, config, setup_deadline).await;
    }

    handle_http_front_client(client, config, setup_deadline).await
}

async fn handle_socks5_client(
    mut client: TcpStream,
    config: Arc<NaiveProxyConfig>,
    setup_deadline: Instant,
) -> io::Result<()> {
    let target = before(setup_deadline, "SOCKS5 request", async {
        negotiate_socks5(&mut client).await?;
        read_socks5_request(&mut client).await
    })
    .await?;

    let upstream = match open_upstream(&config, &target).await {
        Ok(stream) => stream,
        Err(error) => {
            write_socks_reply(&mut client, 0x01).await?;
            return Err(error);
        }
    };

    write_socks_reply(&mut client, 0x00).await?;
    let mut upstream = upstream;
    let _ = copy_bidirectional(&mut client, &mut upstream).await?;
    Ok(())
}

async fn handle_http_front_client(
    mut client: TcpStream,
    config: Arc<NaiveProxyConfig>,
    setup_deadline: Instant,
) -> io::Result<()> {
    let target = before(setup_deadline, "HTTP CONNECT request", read_http_connect_request(&mut client)).await?;

    let upstream = match open_upstream(&config, &target).await {
        Ok(stream) => stream,
        Err(error) => {
            write_http_connect_failure(&mut client).await?;
            return Err(error);
        }
    };

    write_http_connect_success(&mut client).await?;
    let mut upstream = upstream;
    let _ = copy_bidirectional(&mut client, &mut upstream).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    #[test]
    fn accept_failures_of_one_connection_or_resources_do_not_end_the_listener() {
        for raw in [EMFILE, ENFILE, ENOMEM, ENOBUFS] {
            assert_eq!(accept_retry_delay(&io::Error::from_raw_os_error(raw)), Some(ACCEPT_RETRY_DELAY), "{raw}");
        }
        let aborted = io::Error::from(io::ErrorKind::ConnectionAborted);
        assert_eq!(accept_retry_delay(&aborted), Some(Duration::ZERO));
        assert_eq!(accept_retry_delay(&io::Error::from(io::ErrorKind::InvalidInput)), None);
    }

    #[tokio::test]
    async fn clients_past_the_limit_are_closed_at_once() {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let address = listener.local_addr().expect("address");
        let config = NaiveProxyConfig {
            listen: address.to_string(),
            server: "proxy.example".to_owned(),
            server_port: 443,
            server_name: "proxy.example".to_owned(),
            username: None,
            password: None,
            path: None,
            tls_config: crate::tls::default_tls_config(),
            protect_socket_path: None,
        };
        let server = tokio::spawn(serve_listener_with_limit(listener, Arc::new(config), 1));

        let first = TcpStream::connect(address).await.expect("first client");
        tokio::time::sleep(Duration::from_millis(100)).await;
        let mut second = TcpStream::connect(address).await.expect("second client");
        let mut byte = [0_u8; 1];
        let refused = tokio::time::timeout(Duration::from_millis(500), second.read(&mut byte))
            .await
            .expect("a client past the limit is closed at once, not parked in the backlog");
        assert!(matches!(refused, Ok(0) | Err(_)), "the second client is closed: {refused:?}");

        drop(first);
        tokio::time::sleep(Duration::from_millis(100)).await;
        let mut third = TcpStream::connect(address).await.expect("third client");
        third.write_all(&[0x05, 0x01, 0x00]).await.expect("greeting");
        let mut reply = [0_u8; 2];
        tokio::time::timeout(Duration::from_secs(2), third.read_exact(&mut reply))
            .await
            .expect("a client is served once the slot frees")
            .expect("reply");
        assert_eq!(reply, [0x05, 0x00]);
        server.abort();
    }
}
