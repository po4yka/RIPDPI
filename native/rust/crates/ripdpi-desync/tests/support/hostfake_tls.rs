use std::ops::Range;

pub(super) fn client_hello(tls13: bool, cuts: &[usize]) -> Vec<u8> {
    let host = b"video.example.com";
    let mut sni = Vec::from([0, 20, 0, 0, 17]);
    sni.extend_from_slice(host);
    let mut extensions = Vec::from([0, 0, 0, 22]);
    extensions.extend_from_slice(&sni);
    if tls13 {
        extensions.extend_from_slice(&[0, 43, 0, 3, 2, 3, 4]);
    }
    let mut hello = Vec::from([3, 3]);
    hello.extend_from_slice(&[0; 32]);
    hello.extend_from_slice(&[0, 0, 2]);
    hello.extend_from_slice(if tls13 { &[0x13, 1] } else { &[0xc0, 0x2f] });
    hello.extend_from_slice(&[1, 0]);
    hello.extend_from_slice(&u16::try_from(extensions.len()).expect("extension length").to_be_bytes());
    hello.extend_from_slice(&extensions);
    let mut handshake = Vec::from([1, 0, 0, u8::try_from(hello.len()).expect("small ClientHello")]);
    handshake.extend_from_slice(&hello);

    let mut records = Vec::new();
    let mut start = 0;
    for end in cuts.iter().copied().chain(std::iter::once(handshake.len())) {
        records.extend_from_slice(&[22, 3, 3]);
        records.extend_from_slice(&u16::try_from(end - start).expect("record length").to_be_bytes());
        records.extend_from_slice(&handshake[start..end]);
        start = end;
    }
    records
}

pub(super) fn assert_fake_tls(original: &[u8], fake: &[u8], expected_host: &[u8]) {
    let (mut real_handshake, real_host, real_headers) = dissect(original);
    let (fake_handshake, fake_host, fake_headers) = dissect(fake);
    assert_eq!(fake.len(), original.len(), "wire length must stay unchanged");
    assert_eq!(fake_headers, real_headers, "TLS record headers must stay unchanged");
    assert_eq!(&fake_handshake[fake_host], expected_host);
    real_handshake[real_host].copy_from_slice(expected_host);
    assert_eq!(fake_handshake, real_handshake, "only SNI bytes may change");
}

fn dissect(records: &[u8]) -> (Vec<u8>, Range<usize>, Vec<[u8; 5]>) {
    let mut handshake = Vec::new();
    let mut headers = Vec::new();
    let mut cursor = 0;
    while cursor < records.len() {
        let header: [u8; 5] = records[cursor..cursor + 5].try_into().expect("TLS record header");
        assert_eq!(&header[..3], &[22, 3, 3], "valid handshake record header");
        let end = cursor + 5 + usize::from(u16::from_be_bytes([header[3], header[4]]));
        handshake.extend_from_slice(&records[cursor + 5..end]);
        headers.push(header);
        cursor = end;
    }
    assert_eq!(handshake[0], 1, "ClientHello handshake");
    let length = (usize::from(handshake[1]) << 16) | (usize::from(handshake[2]) << 8) | usize::from(handshake[3]);
    assert_eq!(handshake.len(), length + 4);
    let mut cursor = 38;
    cursor += 1 + usize::from(handshake[cursor]);
    cursor += 2 + read_u16(&handshake, cursor);
    cursor += 1 + usize::from(handshake[cursor]);
    let extensions_end = cursor + 2 + read_u16(&handshake, cursor);
    assert_eq!(extensions_end, handshake.len());
    cursor += 2;
    while cursor < extensions_end {
        let kind = read_u16(&handshake, cursor);
        let length = read_u16(&handshake, cursor + 2);
        cursor += 4;
        if kind == 0 {
            assert_eq!(read_u16(&handshake, cursor), length - 2);
            assert_eq!(handshake[cursor + 2], 0, "host_name SNI");
            let host_length = read_u16(&handshake, cursor + 3);
            assert_eq!(host_length, length - 5);
            return (handshake, cursor + 5..cursor + 5 + host_length, headers);
        }
        cursor += length;
    }
    panic!("ClientHello has no SNI");
}

fn read_u16(bytes: &[u8], offset: usize) -> usize {
    usize::from(u16::from_be_bytes([bytes[offset], bytes[offset + 1]]))
}
