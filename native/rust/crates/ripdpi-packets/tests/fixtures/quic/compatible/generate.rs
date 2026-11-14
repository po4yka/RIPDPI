// Run as a temporary ripdpi-proxy-runtime dev example; see README.md.
// Crypto uses pinned rustls 0.23.46, with no RIPDPI packet helpers.
use rustls::{
    Side,
    quic::{Keys, Version},
};
use std::{fs, path::Path};
const V1: u32 = 1;
const V2: u32 = 0x6b3343cf;
fn keys(version: u32, dcid: &[u8], side: Side) -> Keys {
    let suite = rustls::crypto::ring::cipher_suite::TLS13_AES_128_GCM_SHA256.tls13().unwrap();
    suite.quic_suite().unwrap().keys(dcid, side, if version == V1 { Version::V1 } else { Version::V2 })
}
fn read_varint(bytes: &[u8], at: &mut usize) -> usize {
    let width = 1usize << (bytes[*at] >> 6);
    let mut value = usize::from(bytes[*at] & 0x3f);
    for byte in &bytes[*at + 1..*at + width] {
        value = (value << 8) | usize::from(*byte);
    }
    *at += width;
    value
}
fn varint(value: usize) -> Vec<u8> {
    assert!(value < 16384);
    if value < 64 { vec![value as u8] } else { vec![0x40 | (value >> 8) as u8, value as u8] }
}
fn header(bytes: &[u8]) -> (Vec<u8>, Vec<u8>, usize, usize) {
    let dcid_len = usize::from(bytes[5]);
    let dcid = bytes[6..6 + dcid_len].to_vec();
    let mut cursor = 6 + dcid_len;
    let scid_len = usize::from(bytes[cursor]);
    cursor += 1;
    let scid = bytes[cursor..cursor + scid_len].to_vec();
    cursor += scid_len;
    let token_len = read_varint(bytes, &mut cursor);
    cursor += token_len;
    let length = read_varint(bytes, &mut cursor);
    (dcid, scid, cursor, length)
}
fn decrypt(bytes: &[u8], request_dcid: &[u8], version: u32, side: Side) -> Vec<u8> {
    let (_, _, pn_offset, length) = header(bytes);
    let key = keys(version, request_dcid, side);
    let mut first = bytes[0];
    let mut pn_bytes = bytes[pn_offset..pn_offset + 4].to_vec();
    key.remote.header.decrypt_in_place(&bytes[pn_offset + 4..pn_offset + 20], &mut first, &mut pn_bytes).unwrap();
    let pn_len = usize::from((first & 3) + 1);
    let pn = pn_bytes[..pn_len].iter().fold(0, |n, byte| (n << 8) | u64::from(*byte));
    let mut aad = bytes[..pn_offset + pn_len].to_vec();
    aad[0] = first;
    aad[pn_offset..].copy_from_slice(&pn_bytes[..pn_len]);
    let mut body = bytes[pn_offset + pn_len..pn_offset + length].to_vec();
    key.remote.packet.decrypt_in_place(pn, &aad, &mut body).unwrap().to_vec()
}
fn client_hello(plaintext: &[u8]) -> Vec<u8> {
    let mut cursor = 0;
    while plaintext[cursor] == 0 {
        cursor += 1;
    }
    assert_eq!(plaintext[cursor], 6);
    cursor += 1;
    assert_eq!(read_varint(plaintext, &mut cursor), 0);
    let size = read_varint(plaintext, &mut cursor);
    plaintext[cursor..cursor + size].to_vec()
}
fn offer(hello: &[u8], original: u32, include_sni: bool, grease: bool) -> Vec<u8> {
    let mut cursor = 38;
    cursor += 1 + usize::from(hello[cursor]);
    cursor += 2 + usize::from(u16::from_be_bytes(hello[cursor..cursor + 2].try_into().unwrap()));
    cursor += 1 + usize::from(hello[cursor]);
    let extensions_offset = cursor;
    let extensions_len = usize::from(u16::from_be_bytes(hello[cursor..cursor + 2].try_into().unwrap()));
    cursor += 2;
    assert_eq!(cursor + extensions_len, hello.len());
    let mut extensions = Vec::new();
    let mut found = false;
    while cursor < hello.len() {
        let kind = u16::from_be_bytes(hello[cursor..cursor + 2].try_into().unwrap());
        let size = usize::from(u16::from_be_bytes(hello[cursor + 2..cursor + 4].try_into().unwrap()));
        let mut data = hello[cursor + 4..cursor + 4 + size].to_vec();
        cursor += 4 + size;
        if kind == 0 && !include_sni {
            continue;
        }
        if kind == 0x0039 {
            found = true;
            data.extend_from_slice(&[0x11, 12]);
            data.extend_from_slice(&original.to_be_bytes());
            data.extend_from_slice(&V1.to_be_bytes());
            data.extend_from_slice(&V2.to_be_bytes());
            if grease {
                data.extend_from_slice(&[0x6a, 0xb2, 0]);
            }
        }
        extensions.extend_from_slice(&kind.to_be_bytes());
        extensions.extend_from_slice(&(data.len() as u16).to_be_bytes());
        extensions.extend(data);
    }
    assert!(found);
    let mut result = hello[..extensions_offset].to_vec();
    result.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
    result.extend(extensions);
    let length = (result.len() - 4) as u32;
    result[1..4].copy_from_slice(&length.to_be_bytes()[1..]);
    result
}
fn protect(
    version: u32,
    request_dcid: &[u8],
    dcid: &[u8],
    scid: &[u8],
    token: &[u8],
    plaintext: Vec<u8>,
    side: Side,
) -> Vec<u8> {
    protect_with_fixed_bit(version, request_dcid, dcid, scid, token, plaintext, side, false)
}
fn protect_with_fixed_bit(
    version: u32,
    request_dcid: &[u8],
    dcid: &[u8],
    scid: &[u8],
    token: &[u8],
    mut plaintext: Vec<u8>,
    side: Side,
    clear_fixed_bit: bool,
) -> Vec<u8> {
    let mut header = vec![if version == V1 { 0xc3 } else { 0xd3 }];
    if clear_fixed_bit {
        header[0] &= !0x40;
    }
    header.extend_from_slice(&version.to_be_bytes());
    header.push(dcid.len() as u8);
    header.extend_from_slice(dcid);
    header.push(scid.len() as u8);
    header.extend_from_slice(scid);
    header.extend(varint(token.len()));
    header.extend_from_slice(token);
    let header_len = header.len() + 2;
    plaintext.resize(1200 - header_len - 4 - 16, 0);
    header.extend(varint(4 + plaintext.len() + 16));
    let pn_offset = header.len();
    header.extend_from_slice(&[0; 4]);
    let key = keys(version, request_dcid, side);
    let tag = key.local.packet.encrypt_in_place(0, &header, &mut plaintext).unwrap();
    let sample = plaintext[..16].to_vec();
    let mut first = header[0];
    let mut pn = [0; 4];
    key.local.header.encrypt_in_place(&sample, &mut first, &mut pn).unwrap();
    header[0] = first;
    header[pn_offset..].copy_from_slice(&pn);
    header.extend(plaintext);
    header.extend_from_slice(tag.as_ref());
    assert_eq!(header.len(), 1200);
    header
}
fn initial_payload(hello: &[u8]) -> Vec<u8> {
    let mut payload = vec![6, 0];
    payload.extend(varint(hello.len()));
    payload.extend_from_slice(hello);
    payload
}
fn retry(version: u32, original_dcid: &[u8], client_scid: &[u8]) -> Vec<u8> {
    // RFC 9001 section 5.8 and RFC 9369 section 3.3.3 public Retry integrity constants.
    // ring seals an empty body against the raw Retry pseudo-packet AAD.
    use ring::aead::{AES_128_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
    let (key, nonce) = if version == V1 {
        (
            [0xbe, 0x0c, 0x69, 0x0b, 0x9f, 0x66, 0x57, 0x5a, 0x1d, 0x76, 0x6b, 0x54, 0xe3, 0x68, 0xc8, 0x4e],
            [0x46, 0x15, 0x99, 0xd3, 0x5d, 0x63, 0x2b, 0xf2, 0x23, 0x98, 0x25, 0xbb],
        )
    } else {
        (
            [0x8f, 0xb4, 0xb0, 0x1b, 0x56, 0xac, 0x48, 0xe2, 0x60, 0xfb, 0xcb, 0xce, 0xad, 0x7c, 0xcc, 0x92],
            [0xd8, 0x69, 0x69, 0xbc, 0x2d, 0x7c, 0x6d, 0x99, 0x90, 0xef, 0xb0, 0x4a],
        )
    };
    let mut packet = vec![if version == V1 { 0xbf } else { 0x8f }];
    packet.extend_from_slice(&version.to_be_bytes());
    packet.push(client_scid.len() as u8);
    packet.extend_from_slice(client_scid);
    packet.push(8);
    packet.extend_from_slice(b"retrycid");
    packet.extend_from_slice(b"public-token");
    let mut aad = vec![original_dcid.len() as u8];
    aad.extend_from_slice(original_dcid);
    aad.extend_from_slice(&packet);
    let mut tag = Vec::new();
    LessSafeKey::new(UnboundKey::new(&AES_128_GCM, &key).unwrap())
        .seal_in_place_append_tag(Nonce::assume_unique_for_key(nonce), Aad::from(&aad), &mut tag)
        .unwrap();
    packet.extend(tag);
    packet
}
fn main() {
    let args: Vec<_> = std::env::args().collect();
    let root = Path::new(&args[1]);
    let output = root.join("compatible");
    for (version, other, rfc, other_rfc, name) in
        [(V1, V2, "rfc9001", "rfc9369", "v1-v2"), (V2, V1, "rfc9369", "rfc9001", "v2-v1")]
    {
        let client = fs::read(root.join(format!("{rfc}-client-initial.bin"))).unwrap();
        let server = fs::read(root.join(format!("{other_rfc}-server-initial.bin"))).unwrap();
        let (dcid, scid, _, _) = header(&client);
        let original_hello = client_hello(&decrypt(&client, &dcid, version, Side::Server));
        let hello = offer(&original_hello, version, true, false);
        let request = protect(version, &dcid, &dcid, &scid, &[], initial_payload(&hello), Side::Client);
        let partial =
            protect(version, &dcid, &dcid, &scid, &[], initial_payload(&hello[..hello.len() / 2]), Side::Client);
        let (server_dcid, server_scid, _, _) = header(&server);
        let plaintext = decrypt(&server, &dcid, other, Side::Client);
        let reply = protect(other, &dcid, &server_dcid, &server_scid, &[], plaintext.clone(), Side::Server);
        fs::write(output.join(format!("{name}-client.bin")), request).unwrap();
        fs::write(output.join(format!("{name}-partial-client.bin")), partial).unwrap();
        fs::write(output.join(format!("{name}-server.bin")), reply).unwrap();
        let grease_hello = offer(&original_hello, version, true, true);
        let grease_name = if version == V1 { "v1" } else { "v2" };
        fs::write(
            output.join(format!("{grease_name}-grease-client.bin")),
            protect(version, &dcid, &dcid, &scid, &[], initial_payload(&grease_hello), Side::Client),
        )
        .unwrap();
        fs::write(
            output.join(format!("{grease_name}-grease-partial-client.bin")),
            protect(
                version,
                &dcid,
                &dcid,
                &scid,
                &[],
                initial_payload(&grease_hello[..grease_hello.len() / 2]),
                Side::Client,
            ),
        )
        .unwrap();
        fs::write(
            output.join(format!("{grease_name}-grease-initial.bin")),
            protect_with_fixed_bit(
                version,
                &dcid,
                &server_dcid,
                &server_scid,
                &[],
                plaintext.clone(),
                Side::Server,
                true,
            ),
        )
        .unwrap();
        fs::write(
            output.join(format!("{name}-grease-initial.bin")),
            protect_with_fixed_bit(
                other,
                &dcid,
                &server_dcid,
                &server_scid,
                &[],
                plaintext.clone(),
                Side::Server,
                true,
            ),
        )
        .unwrap();
        fs::write(output.join(format!("{grease_name}-grease-retry.bin")), retry(version, &dcid, &scid)).unwrap();
        fs::write(output.join(format!("{name}-grease-retry.bin")), retry(other, &dcid, &scid)).unwrap();
        fs::write(
            output.join(format!("{grease_name}-grease-unrelated-initial.bin")),
            protect_with_fixed_bit(version, &dcid, &[0xaa], &server_scid, &[], plaintext.clone(), Side::Server, true),
        )
        .unwrap();
        fs::write(output.join(format!("{grease_name}-grease-unrelated-retry.bin")), retry(version, &dcid, &[0xaa]))
            .unwrap();
        if version == V1 {
            let no_sni = offer(&original_hello, version, false, false);
            fs::write(
                output.join("v1-v2-no-sni-client.bin"),
                protect(version, &dcid, &dcid, &scid, &[], initial_payload(&no_sni), Side::Client),
            )
            .unwrap();
            fs::write(
                output.join("v1-v2-unrelated-server.bin"),
                protect(other, &dcid, &[0xaa], &server_scid, &[], plaintext.clone(), Side::Server),
            )
            .unwrap();
            let retry_dcid = [0x70, 0x6f, 0x73, 0x74, 0x72, 0x65, 0x74, 0x72];
            fs::write(
                output.join("v1-v2-post-retry-client.bin"),
                protect(
                    version,
                    &retry_dcid,
                    &retry_dcid,
                    &scid,
                    b"public-retry-token",
                    initial_payload(&hello),
                    Side::Client,
                ),
            )
            .unwrap();
            fs::write(
                output.join("v1-v2-post-retry-server.bin"),
                protect(other, &retry_dcid, &server_dcid, &server_scid, &[], plaintext, Side::Server),
            )
            .unwrap();
        }
    }
}
