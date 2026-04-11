#![forbid(unsafe_code)]

use std::io;
use std::net::ToSocketAddrs;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};

use rand::RngCore;
#[cfg(test)]
use ring::digest::{Context as DigestContext, SHA256};
use ring::hmac::{self, Key};
use rustls::pki_types::ServerName;
use rustls::{ClientConfig as RustlsClientConfig, ClientConnection, RootCertStore};
use serde::{Deserialize, Serialize};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};
use tokio::net::TcpStream;

const TLS_HEADER_LEN: usize = 5;
const TLS_FRAME_MAX_LEN: usize = TLS_HEADER_LEN + 65_535;
const TLS_APPLICATION_DATA: u8 = 0x17;
const TLS_HANDSHAKE: u8 = 0x16;
const TLS_ALERT: u8 = 0x15;
const HMAC_LEN: usize = 4;
const SESSION_ID_LEN: usize = 32;
const MAX_WRITE_PAYLOAD_LEN: usize = 16_380;

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Config {
    pub password: String,
    pub server_name: String,
    pub inner_profile_id: String,
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
        let address = (server, port).to_socket_addrs()?.next().ok_or_else(|| {
            io::Error::new(io::ErrorKind::AddrNotAvailable, "ShadowTLS server resolved to no addresses")
        })?;
        let mut stream = TcpStream::connect(address).await?;
        stream.set_nodelay(true)?;

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

            let frame = read_tls_frame(&mut stream).await?;
            match frame.first().copied() {
                Some(TLS_APPLICATION_DATA) => {
                    verify_handshake_frame(&mut handshake_hmac, &frame)?;
                    break;
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
                        io::Error::new(
                            io::ErrorKind::InvalidData,
                            format!("shadowtls process handshake frame: {error}"),
                        )
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

        Ok(ShadowTlsStream::new(stream, server_data_hmac, client_data_hmac, Some(handshake_hmac)))
    }
}

pub struct ShadowTlsStream<S> {
    stream: S,
    read_hmac: ShadowTlsHmac,
    write_hmac: ShadowTlsHmac,
    handshake_hmac: Option<ShadowTlsHmac>,
    pending_plaintext: Vec<u8>,
    pending_frame: Vec<u8>,
    pending_frame_offset: usize,
    eof: bool,
}

impl<S> ShadowTlsStream<S> {
    fn new(
        stream: S,
        read_hmac: ShadowTlsHmac,
        write_hmac: ShadowTlsHmac,
        handshake_hmac: Option<ShadowTlsHmac>,
    ) -> Self {
        Self {
            stream,
            read_hmac,
            write_hmac,
            handshake_hmac,
            pending_plaintext: Vec::new(),
            pending_frame: Vec::new(),
            pending_frame_offset: 0,
            eof: false,
        }
    }
}

impl<S> AsyncRead for ShadowTlsStream<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let this = self.as_mut().get_mut();

        if !this.pending_plaintext.is_empty() {
            let copy_len = buf.remaining().min(this.pending_plaintext.len());
            let chunk: Vec<u8> = this.pending_plaintext.drain(..copy_len).collect();
            buf.put_slice(&chunk);
            return Poll::Ready(Ok(()));
        }

        if this.eof {
            return Poll::Ready(Ok(()));
        }

        loop {
            if this.pending_frame.is_empty() {
                this.pending_frame.resize(TLS_HEADER_LEN, 0);
                this.pending_frame_offset = 0;
            }

            while this.pending_frame_offset < this.pending_frame.len() {
                let read_result = {
                    let remaining = &mut this.pending_frame[this.pending_frame_offset..];
                    let mut read_buf = ReadBuf::new(remaining);
                    match Pin::new(&mut this.stream).poll_read(cx, &mut read_buf) {
                        Poll::Ready(Ok(())) => Poll::Ready(Ok(read_buf.filled().len())),
                        Poll::Ready(Err(error)) => Poll::Ready(Err(error)),
                        Poll::Pending => Poll::Pending,
                    }
                };

                match read_result {
                    Poll::Ready(Ok(read)) => {
                        if read == 0 {
                            this.eof = true;
                            return Poll::Ready(Ok(()));
                        }
                        this.pending_frame_offset += read;
                    }
                    Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                    Poll::Pending => return Poll::Pending,
                }
            }

            if this.pending_frame.len() == TLS_HEADER_LEN {
                let payload_len = u16::from_be_bytes([this.pending_frame[3], this.pending_frame[4]]) as usize;
                if payload_len > TLS_FRAME_MAX_LEN - TLS_HEADER_LEN {
                    return Poll::Ready(Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "ShadowTLS frame payload too large",
                    )));
                }
                this.pending_frame.resize(TLS_HEADER_LEN + payload_len, 0);
                continue;
            }

            let decode_result = deframe_payload(&mut this.read_hmac, &mut this.handshake_hmac, &this.pending_frame);
            match decode_result {
                Ok(FrameDecode::Plaintext(payload)) => {
                    this.pending_plaintext = payload;
                    this.pending_frame.clear();
                    this.pending_frame_offset = 0;
                    if this.pending_plaintext.is_empty() {
                        continue;
                    }
                    let copy_len = buf.remaining().min(this.pending_plaintext.len());
                    let chunk: Vec<u8> = this.pending_plaintext.drain(..copy_len).collect();
                    buf.put_slice(&chunk);
                    return Poll::Ready(Ok(()));
                }
                Ok(FrameDecode::IgnoredHandshake) => {
                    this.pending_frame.clear();
                    this.pending_frame_offset = 0;
                }
                Ok(FrameDecode::Alert) => {
                    this.pending_frame.clear();
                    this.pending_frame_offset = 0;
                    this.eof = true;
                    return Poll::Ready(Ok(()));
                }
                Err(error) => return Poll::Ready(Err(error)),
            }
        }
    }
}

impl<S> AsyncWrite for ShadowTlsStream<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        let this = self.as_mut().get_mut();

        while this.pending_frame_offset < this.pending_frame.len() {
            let write_result = {
                let frame = &this.pending_frame[this.pending_frame_offset..];
                Pin::new(&mut this.stream).poll_write(cx, frame)
            };
            match write_result {
                Poll::Ready(Ok(0)) => {
                    return Poll::Ready(Err(io::Error::new(
                        io::ErrorKind::WriteZero,
                        "ShadowTLS failed to flush pending frame",
                    )));
                }
                Poll::Ready(Ok(written)) => this.pending_frame_offset += written,
                Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                Poll::Pending => return Poll::Pending,
            }
        }

        this.pending_frame.clear();
        this.pending_frame_offset = 0;

        let write_len = buf.len().min(MAX_WRITE_PAYLOAD_LEN);
        let payload = &buf[..write_len];
        let frame = frame_payload(&mut this.write_hmac, payload)?;
        this.pending_frame = frame;
        Poll::Ready(Ok(write_len))
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.as_mut().get_mut();

        while this.pending_frame_offset < this.pending_frame.len() {
            let write_result = {
                let frame = &this.pending_frame[this.pending_frame_offset..];
                Pin::new(&mut this.stream).poll_write(cx, frame)
            };
            match write_result {
                Poll::Ready(Ok(0)) => {
                    return Poll::Ready(Err(io::Error::new(
                        io::ErrorKind::WriteZero,
                        "ShadowTLS failed to flush pending frame",
                    )));
                }
                Poll::Ready(Ok(written)) => this.pending_frame_offset += written,
                Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                Poll::Pending => return Poll::Pending,
            }
        }

        this.pending_frame.clear();
        this.pending_frame_offset = 0;
        Pin::new(&mut this.stream).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        match self.as_mut().poll_flush(cx) {
            Poll::Ready(Ok(())) => {
                let this = self.as_mut().get_mut();
                Pin::new(&mut this.stream).poll_shutdown(cx)
            }
            Poll::Ready(Err(error)) => Poll::Ready(Err(error)),
            Poll::Pending => Poll::Pending,
        }
    }
}

#[derive(Clone, Debug)]
struct ShadowTlsHmac {
    key: Key,
    data: Vec<u8>,
}

impl ShadowTlsHmac {
    fn new(password: &[u8]) -> Self {
        Self { key: Key::new(hmac::HMAC_SHA1_FOR_LEGACY_USE_ONLY, password), data: Vec::new() }
    }

    fn update(&mut self, data: &[u8]) {
        self.data.extend_from_slice(data);
    }

    fn digest(&self) -> [u8; HMAC_LEN] {
        let tag = hmac::sign(&self.key, &self.data);
        let mut out = [0u8; HMAC_LEN];
        out.copy_from_slice(&tag.as_ref()[..HMAC_LEN]);
        out
    }
}

enum FrameDecode {
    Plaintext(Vec<u8>),
    IgnoredHandshake,
    Alert,
}

#[derive(Debug)]
struct ParsedServerHello {
    server_random: Vec<u8>,
}

fn build_rustls_config() -> Arc<RustlsClientConfig> {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    Arc::new(
        RustlsClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("shadowtls rustls versions")
            .with_root_certificates(roots)
            .with_no_client_auth(),
    )
}

fn read_client_hello(client_conn: &mut ClientConnection) -> io::Result<Vec<u8>> {
    let mut hello = Vec::with_capacity(512);
    if client_conn.wants_write() {
        client_conn
            .write_tls(&mut hello)
            .map_err(|error| io::Error::other(format!("shadowtls write ClientHello: {error}")))?;
    }
    if hello.is_empty() {
        return Err(io::Error::other("shadowtls rustls client did not emit ClientHello"));
    }
    Ok(hello)
}

fn modify_client_hello(frame: &[u8], initial_hmac: &ShadowTlsHmac) -> io::Result<Vec<u8>> {
    if frame.len() < TLS_HEADER_LEN + 44 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ShadowTLS ClientHello frame too short"));
    }
    if frame[0] != TLS_HANDSHAKE {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "expected TLS handshake frame"));
    }

    let record_payload_len = u16::from_be_bytes([frame[3], frame[4]]) as usize;
    if record_payload_len + TLS_HEADER_LEN != frame.len() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ShadowTLS ClientHello payload length mismatch"));
    }

    let handshake_type = frame[5];
    if handshake_type != 0x01 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "expected ClientHello handshake message"));
    }

    let client_hello_len = ((usize::from(frame[6])) << 16) | ((usize::from(frame[7])) << 8) | usize::from(frame[8]);
    if client_hello_len + 4 != record_payload_len {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ShadowTLS ClientHello message length mismatch"));
    }

    let original_session_id_len = usize::from(frame[43]);
    if original_session_id_len > SESSION_ID_LEN {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ShadowTLS ClientHello session id length is invalid"));
    }

    let remaining_offset = 44 + original_session_id_len;
    if remaining_offset > frame.len() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ShadowTLS ClientHello session id overflows frame"));
    }

    let new_client_hello_len = client_hello_len + (SESSION_ID_LEN - original_session_id_len);
    let new_record_payload_len = new_client_hello_len + 4;
    let mut modified = Vec::with_capacity(TLS_HEADER_LEN + new_record_payload_len);
    modified.extend_from_slice(&frame[..TLS_HEADER_LEN]);
    modified[3..5].copy_from_slice(&(new_record_payload_len as u16).to_be_bytes());
    modified.push(handshake_type);
    let client_len = (new_client_hello_len as u32).to_be_bytes();
    modified.extend_from_slice(&client_len[1..]);
    modified.extend_from_slice(&frame[9..43]);
    modified.push(SESSION_ID_LEN as u8);

    let mut session_id = [0u8; SESSION_ID_LEN];
    rand::rng().fill_bytes(&mut session_id[..SESSION_ID_LEN - HMAC_LEN]);
    modified.extend_from_slice(&session_id);
    modified.extend_from_slice(&frame[remaining_offset..]);

    let hmac_start = 44 + SESSION_ID_LEN - HMAC_LEN;
    let hmac_end = hmac_start + HMAC_LEN;
    modified[hmac_start..hmac_end].fill(0);
    let mut hmac = initial_hmac.clone();
    hmac.update(&modified[TLS_HEADER_LEN..]);
    let signature = hmac.digest();
    modified[hmac_start..hmac_end].copy_from_slice(&signature);

    Ok(modified)
}

fn parse_validated_server_hello(frame: &[u8]) -> io::Result<ParsedServerHello> {
    if frame.len() < 47 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ShadowTLS ServerHello frame too short"));
    }
    if frame[0] != TLS_HANDSHAKE {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "expected ServerHello handshake frame"));
    }
    if frame[5] != 0x02 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "expected ServerHello handshake message"));
    }

    let message_len = ((usize::from(frame[6])) << 16) | ((usize::from(frame[7])) << 8) | usize::from(frame[8]);
    if message_len + 9 > frame.len() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ShadowTLS ServerHello length exceeds frame"));
    }

    if frame[9] != 0x03 || frame[10] != 0x03 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("ShadowTLS expected ServerHello TLS version 3.3, got {}.{}", frame[9], frame[10]),
        ));
    }

    let server_random = frame[11..43].to_vec();
    let session_id_len = usize::from(frame[43]);
    if session_id_len != SESSION_ID_LEN {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("ShadowTLS expects ServerHello session id len {SESSION_ID_LEN}, got {session_id_len}"),
        ));
    }

    let mut cursor = 44 + session_id_len;
    if cursor + 3 > frame.len() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ShadowTLS ServerHello truncated"));
    }
    cursor += 2; // cipher suite
    cursor += 1; // compression
    if cursor + 2 > frame.len() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ShadowTLS ServerHello is missing extensions length"));
    }
    let extensions_len = u16::from_be_bytes([frame[cursor], frame[cursor + 1]]) as usize;
    cursor += 2;
    if cursor + extensions_len > frame.len() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ShadowTLS ServerHello extensions exceed frame length"));
    }

    let mut extensions_cursor = cursor;
    let extensions_end = cursor + extensions_len;
    let mut tls13 = false;
    while extensions_cursor + 4 <= extensions_end {
        let ext_type = u16::from_be_bytes([frame[extensions_cursor], frame[extensions_cursor + 1]]);
        let ext_len = u16::from_be_bytes([frame[extensions_cursor + 2], frame[extensions_cursor + 3]]) as usize;
        extensions_cursor += 4;
        if extensions_cursor + ext_len > extensions_end {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "ShadowTLS ServerHello extension exceeds extension block",
            ));
        }
        if ext_type == 0x002b && ext_len == 2 {
            tls13 = frame[extensions_cursor] == 0x03 && frame[extensions_cursor + 1] == 0x04;
        }
        extensions_cursor += ext_len;
    }

    if !tls13 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ShadowTLS requires a TLS 1.3 handshake server"));
    }

    Ok(ParsedServerHello { server_random })
}

async fn read_tls_frame(stream: &mut TcpStream) -> io::Result<Vec<u8>> {
    let mut header = [0u8; TLS_HEADER_LEN];
    stream.read_exact(&mut header).await?;
    let payload_len = u16::from_be_bytes([header[3], header[4]]) as usize;
    if payload_len > TLS_FRAME_MAX_LEN - TLS_HEADER_LEN {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ShadowTLS TLS frame payload too large"));
    }
    let mut frame = Vec::with_capacity(TLS_HEADER_LEN + payload_len);
    frame.extend_from_slice(&header);
    frame.resize(TLS_HEADER_LEN + payload_len, 0);
    stream.read_exact(&mut frame[TLS_HEADER_LEN..]).await?;
    Ok(frame)
}

fn verify_handshake_frame(hmac: &mut ShadowTlsHmac, frame: &[u8]) -> io::Result<()> {
    if frame.len() < TLS_HEADER_LEN + HMAC_LEN + 1 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ShadowTLS handshake application data is too short"));
    }
    let payload_len = u16::from_be_bytes([frame[3], frame[4]]) as usize;
    if payload_len + TLS_HEADER_LEN != frame.len() || payload_len <= HMAC_LEN {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "ShadowTLS handshake application data length is invalid",
        ));
    }

    let digest = &frame[TLS_HEADER_LEN..TLS_HEADER_LEN + HMAC_LEN];
    let payload = &frame[TLS_HEADER_LEN + HMAC_LEN..];
    hmac.update(payload);
    if hmac.digest() != digest {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ShadowTLS handshake HMAC verification failed"));
    }
    Ok(())
}

fn frame_payload(hmac: &mut ShadowTlsHmac, payload: &[u8]) -> io::Result<Vec<u8>> {
    let frame_len = payload.len() + HMAC_LEN;
    let total_len = TLS_HEADER_LEN + frame_len;
    let mut frame = Vec::with_capacity(total_len);
    frame.push(TLS_APPLICATION_DATA);
    frame.push(0x03);
    frame.push(0x03);
    frame.extend_from_slice(&(frame_len as u16).to_be_bytes());

    hmac.update(payload);
    let digest = hmac.digest();
    hmac.update(&digest);
    frame.extend_from_slice(&digest);
    frame.extend_from_slice(payload);
    Ok(frame)
}

fn deframe_payload(
    read_hmac: &mut ShadowTlsHmac,
    handshake_hmac: &mut Option<ShadowTlsHmac>,
    frame: &[u8],
) -> io::Result<FrameDecode> {
    if frame.is_empty() {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "ShadowTLS received an empty frame"));
    }
    if frame[0] == TLS_ALERT {
        return Ok(FrameDecode::Alert);
    }
    if frame[0] != TLS_APPLICATION_DATA {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("ShadowTLS expected TLS application data, got record type {}", frame[0]),
        ));
    }

    let payload_len = u16::from_be_bytes([frame[3], frame[4]]) as usize;
    if payload_len + TLS_HEADER_LEN != frame.len() || payload_len < HMAC_LEN {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ShadowTLS application data frame is malformed"));
    }
    let received_digest = &frame[TLS_HEADER_LEN..TLS_HEADER_LEN + HMAC_LEN];
    let payload = &frame[TLS_HEADER_LEN + HMAC_LEN..];

    if let Some(handshake) = handshake_hmac.as_mut() {
        handshake.update(payload);
        let expected = handshake.digest();
        if expected == received_digest {
            return Ok(FrameDecode::IgnoredHandshake);
        }
        *handshake_hmac = None;
    }

    read_hmac.update(payload);
    let expected = read_hmac.digest();
    if expected != received_digest {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "ShadowTLS data HMAC verification failed"));
    }
    read_hmac.update(&expected);
    Ok(FrameDecode::Plaintext(payload.to_vec()))
}

#[cfg(test)]
fn derive_xor_key(password: &[u8], server_random: &[u8]) -> [u8; 32] {
    let mut digest = DigestContext::new(&SHA256);
    digest.update(password);
    digest.update(server_random);
    let hash = digest.finish();
    let mut out = [0u8; 32];
    out.copy_from_slice(hash.as_ref());
    out
}

#[cfg(test)]
fn xor_in_place(data: &mut [u8], key: &[u8; 32]) {
    for (index, byte) in data.iter_mut().enumerate() {
        *byte ^= key[index % key.len()];
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_hello_modification_signs_session_id() {
        let original = sample_client_hello();
        let initial_hmac = ShadowTlsHmac::new(b"shadow-secret");
        let modified = modify_client_hello(&original, &initial_hmac).expect("modify ClientHello");

        assert_eq!(modified[43], SESSION_ID_LEN as u8);
        let mut expected = initial_hmac.clone();
        let hmac_start = 44 + SESSION_ID_LEN - HMAC_LEN;
        let hmac_end = hmac_start + HMAC_LEN;
        let mut unsigned = modified.clone();
        unsigned[hmac_start..hmac_end].fill(0);
        expected.update(&unsigned[TLS_HEADER_LEN..]);
        assert_eq!(expected.digest(), modified[hmac_start..hmac_end]);
    }

    #[test]
    fn frame_payload_round_trips_after_handshake_switch() {
        let server_random = [7u8; 32];
        let mut handshake = ShadowTlsHmac::new(b"shadow-secret");
        handshake.update(&server_random);

        let mut write_hmac = handshake.clone();
        write_hmac.update(b"C");
        let mut read_hmac = handshake.clone();
        read_hmac.update(b"C");

        let payload = b"hello over shadowtls";
        let frame = frame_payload(&mut write_hmac, payload).expect("frame payload");
        let decoded = deframe_payload(&mut read_hmac, &mut None, &frame).expect("deframe");

        match decoded {
            FrameDecode::Plaintext(value) => assert_eq!(payload.to_vec(), value),
            _ => panic!("expected plaintext payload"),
        }
    }

    #[test]
    fn handshake_frames_are_ignored_before_first_server_payload() {
        let server_random = [11u8; 32];
        let mut handshake = ShadowTlsHmac::new(b"shadow-secret");
        handshake.update(&server_random);
        let payload = b"encrypted-handshake";

        let mut frame = vec![TLS_APPLICATION_DATA, 0x03, 0x03];
        frame.extend_from_slice(&((payload.len() + HMAC_LEN) as u16).to_be_bytes());
        let mut digest_hmac = handshake.clone();
        digest_hmac.update(payload);
        let digest = digest_hmac.digest();
        frame.extend_from_slice(&digest);
        frame.extend_from_slice(payload);

        let decoded = deframe_payload(&mut ShadowTlsHmac::new(b"unused"), &mut Some(handshake), &frame)
            .expect("ignore handshake frame");
        assert!(matches!(decoded, FrameDecode::IgnoredHandshake));
    }

    #[test]
    fn xor_key_derivation_is_stable() {
        let key = derive_xor_key(b"secret", &[1u8; 32]);
        let mut data = *b"abcdefghijklmnop";
        xor_in_place(&mut data, &key);
        xor_in_place(&mut data, &key);
        assert_eq!(b"abcdefghijklmnop", &data);
    }

    fn sample_client_hello() -> Vec<u8> {
        let mut frame = vec![TLS_HANDSHAKE, 0x03, 0x03];
        let payload_len = 72u16;
        frame.extend_from_slice(&payload_len.to_be_bytes());
        frame.push(0x01);
        frame.extend_from_slice(&[0x00, 0x00, 0x44]);
        frame.extend_from_slice(&[0x03, 0x03]);
        frame.extend_from_slice(&[0x11; 32]);
        frame.push(0);
        frame.extend_from_slice(&[0x00, 0x02, 0x13, 0x01]);
        frame.push(0x01);
        frame.push(0x00);
        frame.extend_from_slice(&[0x00, 0x18]);
        frame.extend_from_slice(&[
            0x00, 0x2b, 0x00, 0x03, 0x02, 0x03, 0x04, 0x00, 0x0d, 0x00, 0x04, 0x00, 0x02, 0x04, 0x03, 0x00, 0x33, 0x00,
            0x06, 0x00, 0x04, 0x00, 0x1d, 0x00, 0x17,
        ]);
        frame
    }
}
