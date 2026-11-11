use super::*;

fn hello(cipher: u16, extensions: &[u8]) -> Vec<u8> {
    let mut body = vec![3, 3];
    body.extend_from_slice(&[0x5a; 32]);
    body.push(32);
    body.extend_from_slice(&[0x33; 32]);
    body.extend_from_slice(&cipher.to_be_bytes());
    body.push(0);
    body.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
    body.extend_from_slice(extensions);
    let mut handshake = vec![2, 0, (body.len() >> 8) as u8, body.len() as u8];
    handshake.extend_from_slice(&body);
    records(&[&handshake])
}

fn records(parts: &[&[u8]]) -> Vec<u8> {
    let mut out = Vec::new();
    for part in parts {
        out.extend_from_slice(&[22, 3, 3]);
        out.extend_from_slice(&(part.len() as u16).to_be_bytes());
        out.extend_from_slice(part);
    }
    out
}

const TLS13_EXTENSION: [u8; 6] = [0, 43, 0, 2, 3, 4];

#[test]
fn marker_outside_supported_versions_does_not_enable_direct() {
    for cipher in [0xc02f, 0x1301] {
        for marker_at in [11, 44] {
            let mut record = hello(cipher, &[]);
            record[marker_at..marker_at + 6].copy_from_slice(&TLS13_EXTENSION);
            assert_eq!(filter_server_hello(&mut ServerHelloFilter::default(), &record), Some(false));
        }
        let unrelated = [0, 99, 0, 6, 0, 43, 0, 2, 3, 4];
        assert_eq!(filter_server_hello(&mut ServerHelloFilter::default(), &hello(cipher, &unrelated)), Some(false));
    }
}

#[test]
fn fragmented_handshake_preserves_direct_eligibility() {
    let record = hello(0x1301, &TLS13_EXTENSION);
    let handshake = &record[5..];
    for split in 1..handshake.len() {
        let wire = records(&[&handshake[..split], &handshake[split..]]);
        for cut in 0..wire.len() {
            let mut buffered = ServerHelloFilter::default();
            assert_eq!(filter_server_hello(&mut buffered, &wire[..cut]), None);
            assert_eq!(filter_server_hello(&mut buffered, &wire[cut..]), Some(true));
        }
    }
}

#[test]
fn unsupported_ciphers_and_malformed_extensions_do_not_enable_direct() {
    for cipher in [0xc02f, 0x1300, 0x1305, 0x1306, 0xffff] {
        assert_eq!(
            filter_server_hello(&mut ServerHelloFilter::default(), &hello(cipher, &TLS13_EXTENSION)),
            Some(false)
        );
    }
    for extensions in [
        [TLS13_EXTENSION.as_slice(), TLS13_EXTENSION.as_slice()].concat(),
        vec![0, 43, 0, 1, 3],
        vec![0, 43, 0, 3, 3, 4],
        [TLS13_EXTENSION.as_slice(), &[0]].concat(),
    ] {
        assert_eq!(filter_server_hello(&mut ServerHelloFilter::default(), &hello(0x1301, &extensions)), Some(false));
    }
    for cipher in 0x1301..=0x1304 {
        assert_eq!(
            filter_server_hello(&mut ServerHelloFilter::default(), &hello(cipher, &TLS13_EXTENSION)),
            Some(true)
        );
    }
}

#[test]
fn malformed_server_hello_fields_do_not_enable_direct() {
    for offset in [43, 78, 80] {
        let mut wire = hello(0x1301, &TLS13_EXTENSION);
        wire[offset] += 1;
        assert_eq!(filter_server_hello(&mut ServerHelloFilter::default(), &wire), Some(false));
    }
    let mut oversized = hello(0x1301, &TLS13_EXTENSION);
    oversized[6..9].copy_from_slice(&[2, 0, 0]);
    assert_eq!(filter_server_hello(&mut ServerHelloFilter::default(), &oversized), Some(false));
    for header in [[22, 3, 3, 0, 0], [22, 3, 3, 0x40, 1]] {
        assert_eq!(filter_server_hello(&mut ServerHelloFilter::default(), &header), Some(false));
    }
}

#[tokio::test]
/// # Cancel safety:
/// cancel-safe: the test owns its encoded output and discards it on cancellation.
async fn server_hello_decision_selects_the_wire_terminal_command() {
    use tokio::io::AsyncWriteExt;
    let mut tls12 = hello(0xc02f, &[]);
    tls12[11..17].copy_from_slice(&TLS13_EXTENSION);
    let tls13 = hello(0x1301, &TLS13_EXTENSION);
    let fragmented = records(&[&tls13[5..20], &tls13[20..]]);
    for (hello, command) in [(tls12, CMD_END), (fragmented, CMD_DIRECT)] {
        let mut encoded = Vec::new();
        let mut stream = VisionStream::new_vision(&mut encoded, [0x42; 16]);
        stream.xtls_enabled = filter_server_hello(&mut ServerHelloFilter::default(), &hello);
        stream.write_all(&[22, 3, 1, 0, 1, 1]).await.expect("inner handshake");
        stream.write_all(&[23, 3, 3, 0, 1, 7]).await.expect("inner application record");
        stream.flush().await.expect("flush padded records");
        let content_len = usize::from(u16::from_be_bytes([encoded[17], encoded[18]]));
        let padding_len = usize::from(u16::from_be_bytes([encoded[19], encoded[20]]));
        let second_header = 21 + content_len + padding_len;
        assert_eq!(encoded[second_header], command);
        assert_eq!(&encoded[second_header + 5..second_header + 11], &[23, 3, 3, 0, 1, 7]);
    }
}
