use std::future::Future;
use std::io;
use std::sync::Arc;
use std::time::Duration;

use tokio::io::copy_bidirectional;
use tokio::net::{TcpListener, TcpStream};
use tokio::time::Instant;

use crate::config::NaiveProxyConfig;
use crate::connect_tunnel::{AsyncTunnel, open_https_connect_tunnel};
use crate::errors::emit_structured_error;
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
/// # Cancel safety
///
/// cancel-safe: dropping it drops the partly opened upstream connection.
async fn open_upstream(config: &NaiveProxyConfig, target: &SocksTarget) -> io::Result<Box<dyn AsyncTunnel>> {
    before(Instant::now() + UPSTREAM_OPEN_TIMEOUT, "upstream open", open_https_connect_tunnel(config, target)).await
}

pub(crate) async fn run(config: NaiveProxyConfig) -> io::Result<()> {
    let listener = TcpListener::bind(&config.listen).await?;
    println!("{STRUCTURED_READY_PREFIX}{}", env!("CARGO_PKG_VERSION"));

    serve_listener(listener, Arc::new(config)).await
}

pub(crate) async fn serve_listener(listener: TcpListener, config: Arc<NaiveProxyConfig>) -> io::Result<()> {
    loop {
        let (socket, _) = listener.accept().await?;
        let config = Arc::clone(&config);
        tokio::spawn(async move {
            if let Err(error) = handle_client(socket, config).await {
                emit_structured_error(&error);
                eprintln!("naiveproxy connection failed: {error}");
            }
        });
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
