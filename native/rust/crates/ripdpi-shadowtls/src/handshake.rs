use std::io;
use std::sync::Arc;

use rand::Rng;
use rustls::{ClientConfig as RustlsClientConfig, ClientConnection, RootCertStore};

use super::frames::{TLS_HANDSHAKE, TLS_HEADER_LEN};
use super::hmac::{HMAC_LEN, ShadowTlsHmac};

const SESSION_ID_LEN: usize = 32;

#[derive(Debug)]
pub(crate) struct ParsedServerHello {
    pub(crate) server_random: Vec<u8>,
}

pub(crate) fn build_rustls_config() -> Arc<RustlsClientConfig> {
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

pub(crate) fn read_client_hello(client_conn: &mut ClientConnection) -> io::Result<Vec<u8>> {
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

pub(crate) fn modify_client_hello(frame: &[u8], initial_hmac: &ShadowTlsHmac) -> io::Result<Vec<u8>> {
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

pub(crate) fn parse_validated_server_hello(frame: &[u8]) -> io::Result<ParsedServerHello> {
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
    cursor += 2;
    cursor += 1;
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

#[cfg(test)]
pub(crate) fn session_id_len() -> usize {
    SESSION_ID_LEN
}
