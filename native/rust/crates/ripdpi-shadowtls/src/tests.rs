use ring::digest::{Context as DigestContext, SHA256};

use super::frames::{FrameDecode, TLS_APPLICATION_DATA, TLS_HANDSHAKE, TLS_HEADER_LEN, deframe_payload, frame_payload};
use super::handshake::{modify_client_hello, session_id_len};
use super::hmac::{HMAC_LEN, ShadowTlsHmac};
use super::stream::ShadowTlsStream;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

// cancel-safe: the in-memory writer owns its half of the duplex stream.
#[tokio::test]
async fn truncated_tls_record_returns_unexpected_eof() {
    for bytes in [&[0x17, 0x03][..], &[0x17, 0x03, 0x03, 0, 10, 1, 2][..]] {
        let (mut peer, carrier) = tokio::io::duplex(64);
        let mut stream =
            ShadowTlsStream::new(carrier, ShadowTlsHmac::new(b"secret"), ShadowTlsHmac::new(b"secret"), None);
        peer.write_all(bytes).await.unwrap();
        peer.shutdown().await.unwrap();
        let error = stream.read(&mut [0u8; 1]).await.expect_err("partial TLS record must not look like clean EOF");
        assert_eq!(error.kind(), std::io::ErrorKind::UnexpectedEof);
    }
}

// cancel-safe: the in-memory writer owns its half of the duplex stream.
#[tokio::test]
async fn empty_tls_record_is_rejected_without_spinning() {
    let (mut peer, carrier) = tokio::io::duplex(64);
    let mut stream = ShadowTlsStream::new(carrier, ShadowTlsHmac::new(b"secret"), ShadowTlsHmac::new(b"secret"), None);
    peer.write_all(&[0x17, 0x03, 0x03, 0, 0]).await.unwrap();
    let error = stream.read(&mut [0u8; 1]).await.expect_err("empty TLS frame must be rejected");
    assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
}

// cancel-safe: the test owns its in-memory stream and does no partial write.
#[tokio::test]
async fn clean_record_boundary_eof_stays_clean() {
    let (peer, carrier) = tokio::io::duplex(64);
    let mut stream = ShadowTlsStream::new(carrier, ShadowTlsHmac::new(b"secret"), ShadowTlsHmac::new(b"secret"), None);
    drop(peer);
    assert_eq!(stream.read(&mut [0u8; 1]).await.unwrap(), 0);
}

#[test]
fn client_hello_modification_signs_session_id() {
    let original = sample_client_hello();
    let initial_hmac = ShadowTlsHmac::new(b"shadow-secret");
    let modified = modify_client_hello(&original, &initial_hmac).expect("modify ClientHello");

    assert_eq!(modified[43], session_id_len() as u8);
    let mut expected = initial_hmac.clone();
    let hmac_start = 44 + session_id_len() - HMAC_LEN;
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

fn derive_xor_key(password: &[u8], server_random: &[u8]) -> [u8; 32] {
    let mut digest = DigestContext::new(&SHA256);
    digest.update(password);
    digest.update(server_random);
    let hash = digest.finish();
    let mut out = [0u8; 32];
    out.copy_from_slice(hash.as_ref());
    out
}

fn xor_in_place(data: &mut [u8], key: &[u8; 32]) {
    for (index, byte) in data.iter_mut().enumerate() {
        *byte ^= key[index % key.len()];
    }
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
