use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use super::super::routing::connect_socket;
use super::classification::{
    classify_connect_error, classify_read_error, classify_tls_response, classify_write_error, ProbeResult,
};
use super::target_catalog::PROBE_TIMEOUT;

/// Attempt a raw TLS ClientHello to `target` and read the first response bytes.
/// Returns `Success` if we get a ServerHello (TLS record type 0x16, handshake
/// type 0x02), `DpiFailure` if we see signs of DPI interference.
pub(crate) fn probe_tls_handshake(target: SocketAddr, sni: &str, protect_path: Option<&str>) -> ProbeResult {
    let stream =
        match connect_socket(target, IpAddr::V4(Ipv4Addr::UNSPECIFIED), protect_path, false, Some(PROBE_TIMEOUT)) {
            Ok(s) => s,
            Err(err) => return classify_connect_error(&err),
        };

    if stream.set_read_timeout(Some(PROBE_TIMEOUT)).is_err() {
        return ProbeResult::NetworkError("set_timeout_failed");
    }
    if stream.set_write_timeout(Some(PROBE_TIMEOUT)).is_err() {
        return ProbeResult::NetworkError("set_timeout_failed");
    }

    let client_hello = build_minimal_client_hello(sni);
    let mut stream = stream;
    if let Err(err) = stream.write_all(&client_hello) {
        return classify_write_error(&err);
    }

    let mut header = [0u8; 5];
    match stream.read_exact(&mut header) {
        Ok(()) => {
            let handshake_type = read_handshake_type(&mut stream, header);
            classify_tls_response(header, handshake_type)
        }
        Err(err) => classify_read_error(&err),
    }
}

/// Build a minimal TLS 1.0 ClientHello with the given SNI. This is a stripped-
/// down version -- just enough to trigger DPI SNI classification.
pub(crate) fn build_minimal_client_hello(sni: &str) -> Vec<u8> {
    let sni_bytes = sni.as_bytes();
    let sni_list_len = (sni_bytes.len() + 3) as u16;
    let sni_ext_len = sni_list_len + 2;

    // SNI extension (type 0x0000)
    let mut extensions = Vec::new();
    extensions.extend_from_slice(&[0x00, 0x00]);
    extensions.extend_from_slice(&sni_ext_len.to_be_bytes());
    extensions.extend_from_slice(&sni_list_len.to_be_bytes());
    extensions.push(0x00); // host_name type
    extensions.extend_from_slice(&(sni_bytes.len() as u16).to_be_bytes());
    extensions.extend_from_slice(sni_bytes);

    let mut body = Vec::new();
    body.extend_from_slice(&[0x03, 0x01]); // client_version: TLS 1.0
    body.extend_from_slice(&[0u8; 32]); // Random (32 bytes)
    body.push(0); // session_id length = 0
    body.extend_from_slice(&[0x00, 0x02, 0x00, 0x9c]); // TLS_RSA_WITH_AES_128_GCM_SHA256
    body.extend_from_slice(&[0x01, 0x00]); // Compression methods: null
    body.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
    body.extend_from_slice(&extensions);

    let handshake_len = body.len() as u32;
    let mut handshake = Vec::with_capacity(4 + body.len());
    handshake.push(0x01); // ClientHello
    handshake.push((handshake_len >> 16) as u8);
    handshake.push((handshake_len >> 8) as u8);
    handshake.push(handshake_len as u8);
    handshake.extend_from_slice(&body);

    let record_len = handshake.len() as u16;
    let mut record = Vec::with_capacity(5 + handshake.len());
    record.push(0x16); // ContentType: Handshake
    record.extend_from_slice(&[0x03, 0x01]); // TLS 1.0
    record.extend_from_slice(&record_len.to_be_bytes());
    record.extend_from_slice(&handshake);

    record
}

fn read_handshake_type(stream: &mut impl Read, header: [u8; 5]) -> Option<u8> {
    if header[0] != 0x16 {
        return None;
    }

    let mut handshake_type = [0u8; 1];
    if stream.read_exact(&mut handshake_type).is_ok() {
        Some(handshake_type[0])
    } else {
        None
    }
}
