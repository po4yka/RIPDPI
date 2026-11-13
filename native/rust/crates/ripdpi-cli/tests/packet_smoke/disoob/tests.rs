use super::*;

const ORIGINAL: &[u8] = b"abcdef";

#[test]
fn disoob_oracle_accepts_separate_or_combined_urgent_segments() {
    for mut packets in [separate(100), combined(100), separate(u32::MAX - 1)] {
        packets.reverse();
        packets.extend(packets.clone());
        assert_eq!(assert_tcp_disoob(&packets, 8080, ORIGINAL, 2), Ok(()));
    }
}

#[test]
fn disoob_oracle_rejects_unrelated_ttl_or_urgent_evidence() {
    let mut wrong_ttl = combined(100);
    wrong_ttl[0]["layers"]["ip"]["ip.ttl"] = Value::String("64".to_string());
    wrong_ttl.push(packet(500, b"abc", 1, false, 0));
    let mut wrong_urgent = combined(100);
    wrong_urgent[0]["layers"]["tcp"]["tcp.flags.urg"] = Value::String("0".to_string());
    wrong_urgent.push(packet(500, b"abc", 64, true, 3));
    for packets in [wrong_ttl, wrong_urgent] {
        assert!(assert_tcp_disoob(&packets, 8080, ORIGINAL, 2).is_err());
    }
}

#[test]
fn disoob_oracle_rejects_evidence_accepted_by_the_old_disjoint_checks() {
    let mut packets = combined(100);
    packets[0]["layers"]["ip"]["ip.ttl"] = Value::String("64".to_string());
    packets.push(packet(500, b"xyz", 3, false, 0));
    assert_eq!(super::super::assert_outbound_urgent(&packets, 8080), Ok(()));
    assert_eq!(super::super::assert_outbound_ttl(&packets, 8080, 3), Ok(()));
    assert!(assert_tcp_disoob(&packets, 8080, ORIGINAL, 2).is_err());
}

#[test]
fn disoob_oracle_rejects_wrong_ttl_byte_pointer_and_stream() {
    for (layer, field, value) in [
        ("ip", "ip.ttl", "3"),
        ("tcp", "tcp.urgent_pointer", "2"),
        ("tcp", "tcp.urgent_pointer", "65536"),
        ("tcp", "tcp.flags.urg", "0"),
        ("tcp", "tcp.payload", "61:62:59"),
        ("tcp", "tcp.len", "0"),
        ("tcp", "tcp.len", "2"),
        ("tcp", "tcp.seq_raw", "4294967296"),
        ("tcp", "tcp.stream", "8"),
    ] {
        let mut packets = separate(100);
        packets[1]["layers"][layer][field] = Value::String(value.to_string());
        assert!(assert_tcp_disoob(&packets, 8080, ORIGINAL, 2).is_err(), "invalid {field}");
    }
    let mut packets = combined(100);
    packets[1]["layers"]["ip"]["ip.ttl"] = Value::String("1".to_string());
    assert!(assert_tcp_disoob(&packets, 8080, ORIGINAL, 2).is_err(), "tail must use the restored TTL");
}

#[test]
fn disoob_oracle_rejects_missing_urgent_byte_tail_and_conflicting_retransmission() {
    for missing in 0..3 {
        let mut packets = separate(100);
        packets.remove(missing);
        assert!(assert_tcp_disoob(&packets, 8080, ORIGINAL, 2).is_err(), "missing segment {missing}");
    }
    let mut conflicting = combined(100);
    conflicting.push(packet(103, b"cdXf", 64, false, 0));
    assert!(assert_tcp_disoob(&conflicting, 8080, ORIGINAL, 2).is_err());
    let mut second_urgent = combined(100);
    second_urgent.push(packet(103, b"cdef", 64, true, 1));
    assert!(assert_tcp_disoob(&second_urgent, 8080, ORIGINAL, 2).is_err());
}

#[test]
fn disoob_oracle_accepts_normal_ttl_prefix_retransmissions_without_using_them_as_lowering_evidence() {
    let mut packets = combined(100);
    packets.push(packet(100, b"abX", 64, true, 3));
    assert_eq!(assert_tcp_disoob(&packets, 8080, ORIGINAL, 2), Ok(()));
    packets.remove(0);
    assert!(assert_tcp_disoob(&packets, 8080, ORIGINAL, 2).is_err());
}

#[test]
fn disoob_oracle_rejects_corrupt_or_missing_metadata() {
    for field in ["tcp.seq_raw", "tcp.stream", "tcp.len", "tcp.payload", "tcp.flags.urg", "tcp.urgent_pointer"] {
        let mut packets = combined(100);
        packets[0]["layers"]["tcp"][field] = Value::Null;
        assert!(assert_tcp_disoob(&packets, 8080, ORIGINAL, 2).is_err(), "missing {field}");
    }
    for value in ["61:gg:58", "61::58", "616", "61:62:58:00"] {
        let mut packets = combined(100);
        packets[0]["layers"]["tcp"]["tcp.payload"] = Value::String(value.to_string());
        assert!(assert_tcp_disoob(&packets, 8080, ORIGINAL, 2).is_err(), "payload {value}");
    }
}

fn separate(base: u32) -> Vec<Value> {
    vec![
        packet(base, b"ab", 1, false, 0),
        packet(base.wrapping_add(2), b"X", 1, true, 1),
        packet(base.wrapping_add(3), b"cdef", 64, false, 0),
    ]
}

fn combined(base: u32) -> Vec<Value> {
    vec![packet(base, b"abX", 1, true, 3), packet(base.wrapping_add(3), b"cdef", 64, false, 0)]
}

fn packet(sequence: u32, payload: &[u8], ttl: u8, urgent: bool, pointer: u16) -> Value {
    let hex = payload.iter().map(|byte| format!("{byte:02x}")).collect::<Vec<_>>().join(":");
    serde_json::json!({"layers": {"ip": {"ip.ttl": ttl.to_string()}, "tcp": {
        "tcp.dstport": "8080",
        "tcp.stream": "7",
        "tcp.seq_raw": sequence.to_string(),
        "tcp.len": payload.len().to_string(),
        "tcp.payload": hex,
        "tcp.flags.urg": if urgent { "1" } else { "0" },
        "tcp.urgent_pointer": pointer.to_string()
    }}})
}
