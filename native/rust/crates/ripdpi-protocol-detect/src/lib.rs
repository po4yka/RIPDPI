#![forbid(unsafe_code)]

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
            return L7Protocol::WireGuard(WireGuardDissect::default());
        }
        if is_dtls(payload) {
            return L7Protocol::Dtls(DtlsDissect::default());
        }
        if is_stun(payload) {
            return L7Protocol::Stun(StunDissect);
        }
        if is_dht(payload) {
            return L7Protocol::Dht(DhtDissect);
        }
    } else if is_tls_client_hello(payload) {
        return L7Protocol::Tls(TlsDissect { sni: None, is_client_hello: true });
    }

    if is_dns(payload, src_port, dst_port, is_udp) {
        return L7Protocol::Dns(DnsDissect { is_query: dns_is_query(payload, is_udp) });
    }

    // MTProto has no stable fixed signature in the first bytes. This is only a
    // port-and-absence heuristic and must run after stronger protocol checks.
    if is_mtproto_heuristic(payload, src_port, dst_port) {
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
    payload.len() >= 6 && payload[0] & 0xC0 == 0xC0 && payload[0] & 0x30 == 0
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

fn is_dht(payload: &[u8]) -> bool {
    payload.starts_with(b"d1:") || payload.starts_with(b"d8:") || payload.windows(3).any(|window| window == b"1:y")
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

fn is_mtproto_heuristic(payload: &[u8], src_port: u16, dst_port: u16) -> bool {
    payload.len() >= 64 && matches!(src_port, 80 | 443) || payload.len() >= 64 && matches!(dst_port, 80 | 443)
}
