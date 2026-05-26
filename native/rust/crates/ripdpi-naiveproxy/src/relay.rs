use std::io;
use std::sync::Arc;

use tokio::io::copy_bidirectional;
use tokio::net::{TcpListener, TcpStream};

use crate::config::NaiveProxyConfig;
use crate::connect_tunnel::open_https_connect_tunnel;
use crate::errors::emit_structured_error;
use crate::http_front::{read_http_connect_request, write_http_connect_failure, write_http_connect_success};
use crate::socks5::{negotiate_socks5, read_socks5_request, write_socks_reply};

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

async fn handle_client(client: TcpStream, config: Arc<NaiveProxyConfig>) -> io::Result<()> {
    let mut first_byte = [0u8; 1];
    client.peek(&mut first_byte).await?;
    if first_byte[0] == 0x05 {
        return handle_socks5_client(client, config).await;
    }

    handle_http_front_client(client, config).await
}

async fn handle_socks5_client(mut client: TcpStream, config: Arc<NaiveProxyConfig>) -> io::Result<()> {
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
    let mut upstream = upstream;
    let _ = copy_bidirectional(&mut client, &mut upstream).await?;
    Ok(())
}

async fn handle_http_front_client(mut client: TcpStream, config: Arc<NaiveProxyConfig>) -> io::Result<()> {
    let target = read_http_connect_request(&mut client).await?;

    let upstream = match open_https_connect_tunnel(&config, &target).await {
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
