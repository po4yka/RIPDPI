use aes::Aes256;
use cipher::{KeyIvInit, StreamCipher};
use ripdpi_protocol_detect::classify_l7;
use ripdpi_strategy_trait::L7Protocol;

type Aes256Ctr = ctr::Ctr128BE<Aes256>;

fn is_mtproto(payload: &[u8], dst_port: u16) -> bool {
    matches!(classify_l7(payload, 50000, dst_port, false), L7Protocol::Mtproto(_))
}

/// Deterministic xorshift64 byte stream; the test needs reproducible "random" bytes.
fn pseudo_random(seed: u64, len: usize) -> Vec<u8> {
    let mut state = seed;
    (0..len)
        .map(|_| {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            (state >> 24) as u8
        })
        .collect()
}

/// Builds an init as core.telegram.org "Transport obfuscation" describes: random bytes that
/// pass the generator's rejection rules, plaintext tag at [56..60], then bytes [56..64]
/// replaced by their AES-256-CTR encryption (key=init[8..40], iv=init[40..56]).
fn build_init(seed: u64, tag: [u8; 4]) -> [u8; 64] {
    let mut counter = seed;
    loop {
        let mut init: [u8; 64] = pseudo_random(counter, 64).try_into().unwrap();
        counter += 1;
        let first = u32::from_le_bytes([init[0], init[1], init[2], init[3]]);
        let rejected = [0x4441_4548, 0x5453_4f50, 0x2054_4547, 0x4954_504f, 0x0201_0316, 0xdddd_dddd, 0xeeee_eeee];
        if init[0] == 0xef || rejected.contains(&first) || init[4..8] == [0; 4] {
            continue;
        }
        init[56..60].copy_from_slice(&tag);
        let mut encrypted = init;
        Aes256Ctr::new_from_slices(&init[8..40], &init[40..56]).unwrap().apply_keystream(&mut encrypted);
        init[56..64].copy_from_slice(&encrypted[56..64]);
        return init;
    }
}

#[test]
fn detects_valid_obfuscated2_init_on_any_port() {
    for (n, tag) in [[0xef; 4], [0xee; 4], [0xdd; 4]].into_iter().enumerate() {
        for seed in 1..=20_u64 {
            let init = build_init(seed * 1000 + n as u64, tag);
            for port in [443, 5222, 8888] {
                assert!(is_mtproto(&init, port), "seed {seed} tag {tag:02x?} port {port}");
            }
        }
    }
}

#[test]
fn detects_init_followed_by_more_stream_bytes() {
    let mut payload = build_init(7, [0xee; 4]).to_vec();
    payload.extend_from_slice(&pseudo_random(99, 200));
    assert!(is_mtproto(&payload, 443));
}

#[test]
fn rejects_obfuscated2_init_with_wrong_tag_or_udp() {
    let mut init = build_init(3, [0xef; 4]);
    init[59] ^= 0x01;
    assert!(!is_mtproto(&init, 443));

    let valid = build_init(3, [0xef; 4]);
    assert!(!matches!(classify_l7(&valid, 50000, 443, true), L7Protocol::Mtproto(_)));
    assert!(!is_mtproto(&valid[..63], 443));
}

#[test]
fn rejects_plain_text_and_binary_protocols_that_are_not_mtproto() {
    let mut h2 = b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".to_vec();
    h2.extend_from_slice(&[0, 0, 0, 4, 0, 0, 0, 0, 0]);
    h2.extend_from_slice(&[0, 0, 4, 8, 0, 0, 0, 0, 0, 0, 0xff, 0, 1]);
    h2.resize(64, 0);
    assert!(!is_mtproto(&h2, 80));

    let mut propfind = b"PROPFIND / HTTP/1.1\r\nHost: x\r\nDepth: 0\r\nContent-Length: 0\r\n\r\n".to_vec();
    propfind.resize(80, b' ');
    assert!(!is_mtproto(&propfind, 80));

    let mut bittorrent = vec![19];
    bittorrent.extend_from_slice(b"BitTorrent protocol");
    bittorrent.extend_from_slice(&[0; 8]);
    bittorrent.extend_from_slice(&pseudo_random(5, 40));
    assert_eq!(bittorrent.len(), 68);
    assert!(!is_mtproto(&bittorrent, 80));

    let xmpp = b"<?xml version='1.0'?><stream:stream to='example.org' xmlns='jabber:client' \
                 xmlns:stream='http://etherx.jabber.org/streams' version='1.0'>";
    assert!(!is_mtproto(xmpp, 443));

    let mut ssh = b"SSH-2.0-OpenSSH_9.6\r\n".to_vec();
    ssh.resize(64, 0x41);
    assert!(!is_mtproto(&ssh, 443));

    assert!(!is_mtproto(&pseudo_random(0xdead_beef, 1400), 443));
}

/// Known-answer init built outside this crate with OpenSSL, an AES implementation
/// independent of the `aes`/`ctr` crates:
/// bytes [0..56] = SHA-256("ripdpi-std11-kat-a") || SHA-256("ripdpi-std11-kat-b")[..24];
/// keystream = `openssl enc -aes-256-ctr -K <init[8..40]> -iv <init[40..56]> -nosalt` over 64 zero bytes;
/// bytes [56..64] = keystream[56..64] XOR (ef ef ef ef || DC 2 as i16 LE || SHA-256("...-b")[24..26]).
const OPENSSL_KAT_INIT: &str = "5fa9c298b5c86dbaf34f0f27e6df77d1c9b4a926963cd35463a296b1e7e6797562ded4ff7d72e996c64f3198c8cabecbc1d5cc6489572ac25aeddf5b25cf3e22";

fn kat_init() -> [u8; 64] {
    let mut init = [0u8; 64];
    for (index, byte) in init.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&OPENSSL_KAT_INIT[index * 2..index * 2 + 2], 16).unwrap();
    }
    init
}

#[test]
fn detects_openssl_known_answer_init() {
    assert!(is_mtproto(&kat_init(), 443));
    assert!(is_mtproto(&kat_init(), 5222));
}

#[test]
fn rejects_init_prefixes_that_the_generator_regenerates() {
    // Only [0..8] change, so the key, IV and encrypted tag stay valid: each rule alone must reject.
    let rejected_starts: [&[u8]; 9] = [
        &[0xef],
        b"HEAD",
        b"POST",
        b"GET ",
        b"OPTI",
        &[0x16, 0x03, 0x01, 0x02],
        &[0xdd; 4],
        &[0xee; 4],
        &[0x5f, 0xa9, 0xc2, 0x98, 0, 0, 0, 0],
    ];
    for start in rejected_starts {
        let mut init = kat_init();
        init[..start.len()].copy_from_slice(start);
        assert!(!is_mtproto(&init, 443), "{start:02x?}");
    }
}
