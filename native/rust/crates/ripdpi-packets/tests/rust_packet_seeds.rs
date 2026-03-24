use aes::cipher::{generic_array::GenericArray, BlockEncrypt, KeyInit as BlockKeyInit};
use aes::Aes128;
use ring::aead::{self, Aad, LessSafeKey, UnboundKey, AES_128_GCM};
use ring::hkdf::{self, KeyType, Salt, HKDF_SHA256};
use ripdpi_packets::change_tls_sni_seeded_like_c;

struct HkdfLen(usize);
impl KeyType for HkdfLen {
    fn len(&self) -> usize {
        self.0
    }
}

const QUIC_V1_VERSION: u32 = 0x0000_0001;
const QUIC_V2_VERSION: u32 = 0x6b33_43cf;
const QUIC_V1_SALT: [u8; 20] = [
    0x38, 0x76, 0x2c, 0xf7, 0xf5, 0x59, 0x34, 0xb3, 0x4d, 0x17, 0x9a, 0xe6, 0xa4, 0xc8, 0x0c, 0xad, 0xcc, 0xbb, 0x7f,
    0x0a,
];
const QUIC_V2_SALT: [u8; 20] = [
    0x0d, 0xed, 0xe3, 0xde, 0xf7, 0x00, 0xa6, 0xdb, 0x81, 0x93, 0x81, 0xbe, 0x6e, 0x26, 0x9d, 0xcb, 0xf9, 0xbd, 0x2e,
    0xd9,
];

fn decode_hex(input: &str) -> Vec<u8> {
    let filtered: String = input.chars().filter(|ch| !ch.is_ascii_whitespace()).collect();
    assert_eq!(filtered.len() % 2, 0, "hex payload must have even length");

    filtered
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            let high = (pair[0] as char).to_digit(16).expect("hex digit") as u8;
            let low = (pair[1] as char).to_digit(16).expect("hex digit") as u8;
            (high << 4) | low
        })
        .collect()
}

fn read_u16(data: &[u8], offset: usize) -> u16 {
    ((data[offset] as u16) << 8) | data[offset + 1] as u16
}

fn write_u16(data: &mut [u8], offset: usize, value: u16) {
    data[offset] = ((value >> 8) & 0xff) as u8;
    data[offset + 1] = (value & 0xff) as u8;
}

fn write_u24(data: &mut [u8], offset: usize, value: u32) {
    data[offset] = ((value >> 16) & 0xff) as u8;
    data[offset + 1] = ((value >> 8) & 0xff) as u8;
    data[offset + 2] = (value & 0xff) as u8;
}

fn _write_u32(data: &mut [u8], offset: usize, value: u32) {
    data[offset..offset + 4].copy_from_slice(&value.to_be_bytes());
}

fn find_ext_block(data: &[u8]) -> usize {
    let sid_len = data[43] as usize;
    let cip_len = read_u16(data, 44 + sid_len) as usize;
    44 + sid_len + 2 + cip_len + 2
}

fn find_extension(data: &[u8], ext_type: u16) -> usize {
    let block = find_ext_block(data);
    let mut pos = block + 2;
    let end = pos + read_u16(data, block) as usize;
    while pos + 4 <= end {
        let curr_type = read_u16(data, pos);
        let curr_len = read_u16(data, pos + 2) as usize;
        if curr_type == ext_type {
            return pos;
        }
        pos += 4 + curr_len;
    }
    panic!("extension 0x{ext_type:04x} not found");
}

pub fn http_request() -> Vec<u8> {
    b"GET / HTTP/1.1\r\nHost: www.wikipedia.org\r\n\r\n".to_vec()
}

pub fn http_redirect_response() -> Vec<u8> {
    concat!("HTTP/1.1 302 Found\r\n", "Location: https://example.net/wiki\r\n", "Content-Length: 0\r\n", "\r\n")
        .as_bytes()
        .to_vec()
}

pub fn tls_client_hello() -> Vec<u8> {
    let mut data = decode_hex(
        "
1603010200010001fc0303035f6f2ced1322f8dcb2f260482d72666f57dd139d1b37dcfa362ebaf992993a20
f9df0c2e8a55898231631aefa8be0858a7a35a18d3965f045cb462af89d70f8b003e130213031301c02cc030
009fcca9cca8ccaac02bc02f009ec024c028006bc023c0270067c00ac0140039c009c0130033009d009c003d
003c0035002f00ff010001750000001600140000117777772e77696b6970656469612e6f7267000b00040300
0102000a00160014001d0017001e00190018010001010102010301040010000e000c02683208687474702f31
2e31001600000017000000310000000d002a0028040305030603080708080809080a080b0804080508060401
05010601030303010302040205020602002b0009080304030303020301002d00020101003300260024001d00
20118cb88ce88a08901eee19d9dde8d406b1d1e2abe01663d6dcda84a4b84bfb0e001500ac000000000000
",
    );
    data.resize(517, 0);
    data
}

pub fn tls_server_hello_like() -> Vec<u8> {
    let mut data = tls_client_hello();
    let sid_len = data[43] as usize;
    let ext_block = 44 + sid_len + 3;
    data[5] = 0x02;
    if data[43] != 0 {
        data[44] ^= 0x01;
    }
    write_u16(&mut data, ext_block, 5);
    write_u16(&mut data, ext_block + 2, 0x002b);
    write_u16(&mut data, ext_block + 4, 1);
    data[ext_block + 6] = 0;
    data
}

pub fn tls_client_hello_ech() -> Vec<u8> {
    let mut data = tls_client_hello();
    let ech_extension = decode_hex("fe0d000e000000000000000201020002aabb");
    let insert_at = find_extension(&data, 0x0015);

    data.splice(insert_at..insert_at, ech_extension.iter().copied());

    let ext_block = find_ext_block(&data);
    let ext_block_len = read_u16(&data, ext_block) + ech_extension.len() as u16;
    let record_len = read_u16(&data, 3) + ech_extension.len() as u16;
    write_u16(&mut data, ext_block, ext_block_len);
    write_u16(&mut data, 3, record_len);

    let handshake_len =
        (((data[6] as u32) << 16) | ((data[7] as u32) << 8) | data[8] as u32) + ech_extension.len() as u32;
    assert!(handshake_len <= 0x00ff_ffff, "handshake length overflow");
    write_u24(&mut data, 6, handshake_len);

    data
}

fn encode_quic_varint(value: u64) -> Vec<u8> {
    match value {
        0..=63 => vec![value as u8],
        64..=16_383 => ((0x4000 | value as u16).to_be_bytes()).to_vec(),
        16_384..=1_073_741_823 => ((0x8000_0000 | value as u32).to_be_bytes()).to_vec(),
        _ => {
            let mut bytes = value.to_be_bytes();
            bytes[0] |= 0xc0;
            bytes.to_vec()
        }
    }
}

fn quic_hkdf_label(label: &str, out_len: usize) -> Vec<u8> {
    let mut info = Vec::with_capacity(2 + 1 + label.len() + 1);
    info.extend_from_slice(&(out_len as u16).to_be_bytes());
    info.push(label.len() as u8);
    info.extend_from_slice(label.as_bytes());
    info.push(0);
    info
}

fn quic_expand_label(secret: &[u8], label: &str, out: &mut [u8]) {
    let prk = hkdf::Prk::new_less_safe(HKDF_SHA256, secret);
    let info = quic_hkdf_label(label, out.len());
    let info_refs: &[&[u8]] = &[&info];
    let okm = prk.expand(info_refs, HkdfLen(out.len())).expect("quic seed expand");
    okm.fill(out).expect("quic seed fill");
}

fn quic_client_initial_secret(version: u32, dcid: &[u8]) -> [u8; 32] {
    let salt_bytes = match version {
        QUIC_V1_VERSION => &QUIC_V1_SALT,
        QUIC_V2_VERSION => &QUIC_V2_SALT,
        _ => panic!("unsupported quic version: {version:#x}"),
    };
    let salt = Salt::new(HKDF_SHA256, salt_bytes);
    let prk = salt.extract(dcid);
    let mut secret = [0u8; 32];
    let info = quic_hkdf_label("tls13 client in", secret.len());
    let info_refs: &[&[u8]] = &[&info];
    let okm = prk.expand(info_refs, HkdfLen(secret.len())).expect("quic seed initial secret");
    okm.fill(&mut secret).expect("quic seed fill");
    secret
}

fn append_crypto_frame(out: &mut Vec<u8>, offset: u64, data: &[u8]) {
    out.push(0x06);
    out.extend_from_slice(&encode_quic_varint(offset));
    out.extend_from_slice(&encode_quic_varint(data.len() as u64));
    out.extend_from_slice(data);
}

fn tls_client_hello_for_host(host: &str) -> Vec<u8> {
    let data = tls_client_hello();
    let mutation = change_tls_sni_seeded_like_c(&data, host.as_bytes(), data.len() + 64, 7);
    assert_eq!(mutation.rc, 0, "seed TLS SNI mutation failed");
    mutation.bytes
}

fn tls_client_hello_without_sni() -> Vec<u8> {
    let mut data = tls_client_hello();
    let ext_block = find_ext_block(&data);
    let sni_offs = find_extension(&data, 0x0000);
    let sni_size = 4 + read_u16(&data, sni_offs + 2) as usize;
    let old_ext_len = read_u16(&data, ext_block) as usize;
    let old_record_len = read_u16(&data, 3) as usize;
    let old_handshake_len = ((data[6] as usize) << 16) | ((data[7] as usize) << 8) | data[8] as usize;

    data.copy_within(sni_offs + sni_size.., sni_offs);
    data.truncate(data.len() - sni_size);

    write_u16(&mut data, ext_block, (old_ext_len - sni_size).try_into().expect("ext len fits"));
    write_u16(&mut data, 3, (old_record_len - sni_size).try_into().expect("record len fits"));
    write_u24(&mut data, 6, (old_handshake_len - sni_size) as u32);
    data
}

fn quic_initial_from_tls(version: u32, client_hello: &[u8], gap_after_split: usize) -> Vec<u8> {
    let dcid = [0x83, 0x94, 0xc8, 0xf0, 0x3e, 0x51, 0x57, 0x08];
    let scid = [0x11, 0x22, 0x33, 0x44];
    let crypto = client_hello[5..].to_vec();
    let split = crypto.len() / 2;
    let mut plaintext = Vec::new();
    append_crypto_frame(&mut plaintext, 0, &crypto[..split]);
    append_crypto_frame(&mut plaintext, (split + gap_after_split) as u64, &crypto[split..]);

    loop {
        let payload_len = 4 + plaintext.len() + 16;
        let header_len = 1 + 4 + 1 + dcid.len() + 1 + scid.len() + 1 + encode_quic_varint(payload_len as u64).len() + 4;
        let total_len = header_len + payload_len;
        if total_len >= 1200 {
            break;
        }
        plaintext.extend(std::iter::repeat_n(0u8, 1200 - total_len));
    }

    let payload_len = 4 + plaintext.len() + 16;
    let payload_len_varint = encode_quic_varint(payload_len as u64);
    let first_byte = if version == QUIC_V2_VERSION { 0xd3 } else { 0xc3 };

    let mut header = Vec::new();
    header.push(first_byte);
    header.extend_from_slice(&version.to_be_bytes());
    header.push(dcid.len() as u8);
    header.extend_from_slice(&dcid);
    header.push(scid.len() as u8);
    header.extend_from_slice(&scid);
    header.push(0);
    header.extend_from_slice(&payload_len_varint);

    let packet_number = [0u8; 4];
    let mut aad = header.clone();
    aad.extend_from_slice(&packet_number);

    let secret = quic_client_initial_secret(version, &dcid);
    let (key_label, iv_label, hp_label) = if version == QUIC_V2_VERSION {
        ("tls13 quicv2 key", "tls13 quicv2 iv", "tls13 quicv2 hp")
    } else {
        ("tls13 quic key", "tls13 quic iv", "tls13 quic hp")
    };
    let mut key = [0u8; 16];
    let mut iv = [0u8; 12];
    let mut hp = [0u8; 16];
    quic_expand_label(&secret, key_label, &mut key);
    quic_expand_label(&secret, iv_label, &mut iv);
    quic_expand_label(&secret, hp_label, &mut hp);

    let unbound = UnboundKey::new(&AES_128_GCM, &key).expect("quic seed aes-gcm");
    let sealing_key = LessSafeKey::new(unbound);
    let nonce = aead::Nonce::try_assume_unique_for_key(&iv).expect("quic seed nonce");
    let mut ciphertext = plaintext;
    let tag = sealing_key
        .seal_in_place_separate_tag(nonce, Aad::from(&aad), &mut ciphertext)
        .expect("quic seed encrypt");

    let hp_cipher = Aes128::new_from_slice(&hp).expect("quic seed hp");
    let mut sample = GenericArray::clone_from_slice(&ciphertext[..16]);
    hp_cipher.encrypt_block(&mut sample);

    let mut packet = header;
    packet.extend((0..4).map(|idx| packet_number[idx] ^ sample[1 + idx]));
    packet.extend_from_slice(&ciphertext);
    packet.extend_from_slice(tag.as_ref());
    packet[0] ^= sample[0] & 0x0f;
    packet
}

pub fn quic_initial_with_host(version: u32, host: &str) -> Vec<u8> {
    let client_hello = tls_client_hello_for_host(host);
    quic_initial_from_tls(version, &client_hello, 0)
}

pub fn quic_initial_v1() -> Vec<u8> {
    quic_initial_with_host(QUIC_V1_VERSION, "docs.example.test")
}

pub fn quic_initial_v2() -> Vec<u8> {
    quic_initial_with_host(QUIC_V2_VERSION, "media.example.test")
}

pub fn quic_initial_with_crypto_gap(version: u32, host: &str) -> Vec<u8> {
    let client_hello = tls_client_hello_for_host(host);
    quic_initial_from_tls(version, &client_hello, 5)
}

pub fn quic_initial_missing_sni(version: u32) -> Vec<u8> {
    let client_hello = tls_client_hello_without_sni();
    quic_initial_from_tls(version, &client_hello, 0)
}
