use std::io;

use tokio::io::{AsyncRead, AsyncReadExt};

use super::hmac::{HMAC_LEN, ShadowTlsHmac};

pub(crate) const TLS_HEADER_LEN: usize = 5;
pub(crate) const TLS_FRAME_MAX_LEN: usize = TLS_HEADER_LEN + 65_535;
pub(crate) const TLS_APPLICATION_DATA: u8 = 0x17;
pub(crate) const TLS_HANDSHAKE: u8 = 0x16;
pub(crate) const TLS_ALERT: u8 = 0x15;
pub(crate) const MAX_WRITE_PAYLOAD_LEN: usize = 16_380;

pub(crate) enum FrameDecode {
    Plaintext(Vec<u8>),
    IgnoredHandshake,
    Alert,
}

pub(crate) async fn read_tls_frame<S>(stream: &mut S) -> io::Result<Vec<u8>>
where
    S: AsyncRead + Unpin,
{
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

pub(crate) fn verify_handshake_frame(hmac: &mut ShadowTlsHmac, frame: &[u8]) -> io::Result<()> {
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

pub(crate) fn frame_payload(hmac: &mut ShadowTlsHmac, payload: &[u8]) -> io::Result<Vec<u8>> {
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

pub(crate) fn deframe_payload(
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
