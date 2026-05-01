use std::io;
use std::sync::Arc;

use tokio::io::copy_bidirectional;
use tokio::net::{TcpListener, TcpStream};

use crate::config::NaiveProxyConfig;
use crate::connect_tunnel::open_https_connect_tunnel;
use crate::errors::emit_structured_error;
use crate::socks5::{negotiate_socks5, read_socks5_request, write_socks_reply};
use crate::tls::BufferedTlsStream;

const STRUCTURED_READY_PREFIX: &str = "RIPDPI-READY|naiveproxy|";

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

async fn handle_client(mut client: TcpStream, config: Arc<NaiveProxyConfig>) -> io::Result<()> {
    negotiate_socks5(&mut client).await?;
    let target = read_socks5_request(&mut client).await?;

    let upstream = match open_https_connect_tunnel(&config, &target).await {
        Ok(stream) => stream,
        Err(error) => {
            write_socks_reply(&mut client, 0x01).await?;
            return Err(error);
        }
    };

    write_socks_reply(&mut client, 0x00).await?;
    let mut upstream = BufferedTlsStream::new(upstream.0, upstream.1);
    let _ = copy_bidirectional(&mut client, &mut upstream).await?;
    Ok(())
}
