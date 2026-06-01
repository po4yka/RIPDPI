use std::io;
use std::net::ToSocketAddrs;

use rustls::ClientConnection;
use rustls::pki_types::ServerName;
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::config::Config;
use crate::frames::{TLS_ALERT, TLS_APPLICATION_DATA, read_tls_frame, verify_handshake_frame};
use crate::handshake::{build_rustls_config, modify_client_hello, parse_validated_server_hello, read_client_hello};
use crate::hmac::ShadowTlsHmac;
use crate::stream::ShadowTlsStream;

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
        let address = (server, port).to_socket_addrs()?.next().ok_or_else(|| {
            io::Error::new(io::ErrorKind::AddrNotAvailable, "ShadowTLS server resolved to no addresses")
        })?;
        let stream = TcpStream::connect(address).await?;
        stream.set_nodelay(true)?;
        self.connect_over(stream).await
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
