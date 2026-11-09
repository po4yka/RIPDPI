#![forbid(unsafe_code)]

use aes::Aes256;
use cipher::{KeyIvInit, StreamCipher};
use ripdpi_strategy_trait::{
    DhtDissect, DnsDissect, DtlsDissect, L7Protocol, MtprotoDissect, QuicDissect, StunDissect, TlsDissect,
    WireGuardDissect,
};

/// Classifies a first payload slice into an L7 protocol without heap allocation.
pub fn classify_l7(payload: &[u8], src_port: u16, dst_port: u16, is_udp: bool) -> L7Protocol {
    if payload.len() < 2 {
        return L7Protocol::Unknown;
    }

    if is_udp {
        if is_quic_initial(payload) {
            return L7Protocol::Quic(QuicDissect::default());
        }
        if is_wireguard(payload) {
            return L7Protocol::WireGuard(WireGuardDissect { message_type: Some(u32::from(payload[0])) });
        }
        if is_dtls(payload) {
            return L7Protocol::Dtls(DtlsDissect { is_client_hello: payload[0] == 22 && payload.get(13) == Some(&1) });
        }
        if is_stun(payload) {
            return L7Protocol::Stun(StunDissect);
        }
    } else if is_tls_client_hello(payload) {
        return L7Protocol::Tls(TlsDissect { sni: None, is_client_hello: true });
    }

    if is_dns(payload, src_port, dst_port, is_udp) {
        return L7Protocol::Dns(DnsDissect { is_query: dns_is_query(payload, is_udp) });
    }

    // DHT runs after DNS: a DNS name can contain the bytes `1:y`.
    if is_udp && is_dht(payload) {
        return L7Protocol::Dht(DhtDissect);
    }

    // Obfuscated2 is carried over TCP only; the init is validated cryptographically.
    if !is_udp && is_obfuscated2_init(payload) {
        return L7Protocol::Mtproto(MtprotoDissect);
    }

    L7Protocol::Unknown
}

fn is_tls_client_hello(payload: &[u8]) -> bool {
    payload.len() >= 6
        && payload[0] == 0x16
        && payload[1] == 0x03
        && (0x01..=0x04).contains(&payload[2])
        && payload[5] == 0x01
}

fn is_quic_initial(payload: &[u8]) -> bool {
    if payload.len() < 6 || payload[0] & 0xC0 != 0xC0 {
        return false;
    }
    match &payload[1..5] {
        [0, 0, 0, 1] => payload[0] & 0x30 == 0,
        [0x6b, 0x33, 0x43, 0xcf] => payload[0] & 0x30 == 0x10,
        _ => false,
    }
}

fn is_wireguard(payload: &[u8]) -> bool {
    let Some(prefix) = payload.get(..4) else {
        return false;
    };
    match prefix {
        [0x01, 0, 0, 0] => payload.len() == 148,
        [0x02, 0, 0, 0] => payload.len() == 92,
        [0x03, 0, 0, 0] => payload.len() == 64,
        [0x04, 0, 0, 0] => payload.len() >= 32,
        _ => false,
    }
}

fn is_dtls(payload: &[u8]) -> bool {
    payload.len() >= 3 && matches!(payload[0], 0x14..=0x17) && matches!((payload[1], payload[2]), (0xFE, 0xFF | 0xFD))
}

fn is_stun(payload: &[u8]) -> bool {
    payload.len() >= 8 && payload[0] & 0xC0 == 0 && payload.get(4..8) == Some(&[0x21, 0x12, 0xA4, 0x42][..])
}

/// BEP-5 KRPC: a bencoded dictionary with sorted keys, so it starts with `d1:` and holds the
/// key `y` whose value is the one-character type `q`, `r` or `e`.
fn is_dht(payload: &[u8]) -> bool {
    payload.starts_with(b"d1:")
        && payload.windows(6).any(|w| w.starts_with(b"1:y1:") && matches!(w[5], b'q' | b'r' | b'e'))
}

fn is_dns(payload: &[u8], src_port: u16, dst_port: u16, is_udp: bool) -> bool {
    if src_port != 53 && dst_port != 53 {
        return false;
    }
    if is_udp {
        payload.len() >= 12
    } else {
        payload.len() >= 14 && u16::from_be_bytes([payload[0], payload[1]]) as usize == payload.len() - 2
    }
}

fn dns_is_query(payload: &[u8], is_udp: bool) -> bool {
    let flags_offset = if is_udp { 2 } else { 4 };
    payload.get(flags_offset).is_some_and(|flags| flags & 0x80 == 0)
}

/// Obfuscated2 init prefixes that Telegram clients regenerate (tdlib `TcpTransport.cpp`):
/// `HEAD`, `POST`, `GET `, `OPTI`, a TLS record start, and the plain `dd`/`ee` transport tags.
const OBFUSCATED2_REJECTED_PREFIXES: [u32; 7] =
    [0x4441_4548, 0x5453_4f50, 0x2054_4547, 0x4954_504f, 0x0201_0316, 0xdddd_dddd, 0xeeee_eeee];

/// Transport tags valid at `[56..60]` of the decrypted init: abridged, intermediate, padded intermediate.
const OBFUSCATED2_TAGS: [[u8; 4]; 3] = [[0xef; 4], [0xee; 4], [0xdd; 4]];

type Aes256Ctr = ctr::Ctr128BE<Aes256>;

/// Validates a Telegram obfuscated2 init (core.telegram.org "Transport obfuscation").
///
/// Port-independent and TCP-only. The 64-byte init is checked against the generator's
/// rejection rules, then decrypted with AES-256-CTR (key `[8..40]`, IV `[40..56]`), and the
/// transport tag at `[56..60]` must be a valid one. A false positive on random data has
/// probability about 3 * 2^-32. MTProxy inits are keyed with the proxy secret, so they
/// cannot be verified without it and are intentionally not detected.
fn is_obfuscated2_init(payload: &[u8]) -> bool {
    let Some(init) = payload.first_chunk::<64>() else {
        return false;
    };
    let first = u32::from_le_bytes([init[0], init[1], init[2], init[3]]);
    if init[0] == 0xef || OBFUSCATED2_REJECTED_PREFIXES.contains(&first) || init[4..8] == [0; 4] {
        return false;
    }
    let mut decrypted: [u8; 64] = *init;
    let Ok(mut cipher) = Aes256Ctr::new_from_slices(&init[8..40], &init[40..56]) else {
        return false;
    };
    cipher.apply_keystream(&mut decrypted);
    OBFUSCATED2_TAGS.iter().any(|tag| decrypted[56..60] == tag[..])
}
