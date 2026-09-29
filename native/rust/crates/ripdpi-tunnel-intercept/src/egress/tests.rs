use std::io;
use std::net::{IpAddr, Ipv6Addr};

use ripdpi_packets::{QUIC_V1_VERSION, QUIC_V2_VERSION, build_realistic_quic_initial, parse_quic_initial_layout};

use super::*;

#[test]
fn udp_protocol_rules_do_not_inject_other_protocols_on_either_ip_family() {
    let payloads = udp_protocol_payloads();
    for protocol in ["quic", "dtls", "stun", "dht", "wireguard"] {
        let yaml = format!(
            "version: 1\nstrategies:\n  - id: udp-scoped\n    match:\n      proto: [{protocol}]\n      port: [443]\n    steps:\n      - type: udplen\n        delta: 4\n"
        );
        for ipv6 in [false, true] {
            let mut interceptor = TunEgressInterceptor::new(Some(&yaml), RecordingInjector::default());
            for (detected, payload, _) in &payloads {
                let packet = udp_packet(ipv6, 49152, 443, payload);
                let before = interceptor.injector.packets.len();
                assert!(!interceptor.handle_packet(&packet));
                assert_eq!(
                    interceptor.injector.packets.len() - before,
                    usize::from(*detected == protocol),
                    "rule={protocol} payload={detected} ipv6={ipv6}"
                );
                if *detected == protocol {
                    let injected = interceptor.injector.packets.last().unwrap();
                    assert_eq!(packet_destination(injected), packet_destination(&packet));
                    assert_eq!(packet_payload(injected), payload);
                    if protocol == "quic" {
                        let meta = PacketMeta::parse(&packet).unwrap();
                        let L7Protocol::Quic(quic) = dissect_packet(meta, payload).proto else {
                            panic!("missing QUIC dissection");
                        };
                        assert_eq!(quic.version, Some(u32::from_be_bytes(payload[1..5].try_into().unwrap())));
                    }
                }
                let wrong_port = udp_packet(ipv6, 49152, 500, payload);
                let before = interceptor.injector.packets.len();
                assert!(!interceptor.handle_packet(&wrong_port));
                assert_eq!(interceptor.injector.packets.len(), before);
            }
            for payload in [b"".as_slice(), &[0], b"abc", &[0xc0, 0, 0, 0], &[1, 0, 0, 0], &[0xaa; 64]] {
                let before = interceptor.injector.packets.len();
                assert!(!interceptor.handle_packet(&udp_packet(ipv6, 49152, 443, payload)));
                assert_eq!(interceptor.injector.packets.len(), before);
            }
            let before = interceptor.injector.packets.len();
            assert!(!interceptor.handle_packet(&udp_packet(ipv6, 53, 443, &[0; 12])));
            assert_eq!(interceptor.injector.packets.len(), before);
        }
    }
}

#[test]
fn udp_empty_and_any_filters_keep_unknown_datagrams() {
    for matcher in ["", "    match:\n      proto: [any]\n"] {
        let yaml = format!(
            "version: 1\nstrategies:\n  - id: udp-any\n{matcher}    steps:\n      - type: udplen\n        delta: 4\n"
        );
        let mut interceptor = TunEgressInterceptor::new(Some(&yaml), RecordingInjector::default());
        for ipv6 in [false, true] {
            assert!(!interceptor.handle_packet(&udp_packet(ipv6, 49152, 443, b"abc")));
        }
        assert_eq!(interceptor.injector.packets.len(), 2);
    }
}

#[test]
fn tun_lua_receives_existing_udp_protocol_and_subtype() {
    let script =
        write_lua_script("lua-udp-protocol", "function candidate(d) return d.dis.proto .. '/' .. d.l7payload end");
    let yaml = format!(
        "version: 1\nstrategies:\n  - id: udp-context\n    steps:\n      - type: lua\n        function: candidate\n        script_paths: [{}]\n",
        script.display()
    );
    let mut interceptor =
        TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), RecordingInjector::default());
    for ipv6 in [false, true] {
        for (protocol, payload, subtype) in udp_protocol_payloads() {
            let packet = udp_packet(ipv6, 49152, 443, &payload);
            assert!(interceptor.handle_packet(&packet), "{protocol}/{subtype}");
            let injected = interceptor.injector.packets.last().unwrap();
            assert_eq!(packet_payload(injected), format!("{protocol}/{subtype}").as_bytes());
        }
        for (src, dst) in [(49152, 53), (53, 49152)] {
            assert!(interceptor.handle_packet(&udp_packet(ipv6, src, dst, &[0; 12])));
            assert_eq!(packet_payload(interceptor.injector.packets.last().unwrap()), b"dns/dns_query");
        }
        assert!(interceptor.handle_packet(&udp_packet(ipv6, 49152, 500, &[0; 12])));
        assert_eq!(packet_payload(interceptor.injector.packets.last().unwrap()), b"unknown/unknown");
    }
}

fn udp_protocol_payloads() -> Vec<(&'static str, Vec<u8>, &'static str)> {
    let mut payloads = vec![
        ("quic", build_realistic_quic_initial(QUIC_V1_VERSION, Some("example.org")).unwrap(), "quic_initial"),
        ("quic", build_realistic_quic_initial(QUIC_V2_VERSION, Some("example.org")).unwrap(), "quic_initial"),
        ("stun", vec![0, 1, 0, 0, 0x21, 0x12, 0xa4, 0x42], "stun"),
        ("dht", b"d1:y1:qe".to_vec(), "dht"),
    ];
    for (kind, length, subtype) in [
        (1, 148, "wireguard_initiation"),
        (2, 92, "wireguard_response"),
        (3, 64, "wireguard_cookie"),
        (4, 32, "wireguard_keepalive"),
        (4, 64, "wireguard_data"),
    ] {
        let mut payload = vec![0; length];
        payload[0] = kind;
        payloads.push(("wireguard", payload, subtype));
    }
    for (kind, subtype) in [(1, "dtls_client_hello"), (2, "dtls_server_hello"), (11, "unknown")] {
        let mut payload = vec![0; 14];
        payload[..3].copy_from_slice(&[22, 0xfe, 0xfd]);
        payload[13] = kind;
        payloads.push(("dtls", payload, subtype));
    }
    payloads
}

fn udp_packet(ipv6: bool, src: u16, dst: u16, payload: &[u8]) -> Vec<u8> {
    if !ipv6 {
        return ipv4_udp_packet(src, dst, payload);
    }
    let udp_len = 8 + payload.len();
    let mut packet = vec![0; IPV6_HEADER_LEN + udp_len];
    packet[0] = 0x60;
    packet[4..6].copy_from_slice(&(udp_len as u16).to_be_bytes());
    packet[6] = UDP_PROTO;
    packet[7] = 64;
    packet[8..24].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
    packet[24..40].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
    packet[40..42].copy_from_slice(&src.to_be_bytes());
    packet[42..44].copy_from_slice(&dst.to_be_bytes());
    packet[44..46].copy_from_slice(&(udp_len as u16).to_be_bytes());
    packet[48..].copy_from_slice(payload);
    packet
}

#[test]
fn udplen_rule_injects_modified_udp_packet_and_forwards_original() {
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some("example.org")).expect("QUIC initial");
    let packet = ipv4_udp_packet(443, 443, &payload);
    let yaml = r#"
version: 1
strategies:
  - id: quic-udp
    match:
      proto: [quic]
      port: [443]
    steps:
      - type: udplen
        delta: 4
"#;
    let mut interceptor = TunEgressInterceptor::new(Some(yaml), RecordingInjector::default());

    assert!(!interceptor.handle_packet(&packet));

    let injected = &interceptor.injector.packets[0];
    let udp_len_offset = IPV4_MIN_HEADER_LEN + 4;
    assert_eq!(
        u16::from_be_bytes([injected[udp_len_offset], injected[udp_len_offset + 1]]),
        (payload.len() + 12) as u16
    );
    assert_eq!(&injected[2..4], &packet[2..4], "IP total length must stay unchanged");
}

#[test]
fn host_rule_only_injects_for_matching_host() {
    let yaml = r#"
version: 1
strategies:
  - id: host-scoped
    match:
      proto: [http]
      port: [80]
      hosts: [example.com]
    steps:
      - type: fake
        ttl: 5
"#;
    let mut interceptor = TunEgressInterceptor::new(Some(yaml), RecordingInjector::default());
    let other = ipv4_tcp_packet(49152, 80, b"GET / HTTP/1.1\r\nHost: notexample.com\r\n\r\n");
    let matching = ipv4_tcp_packet(49152, 80, b"GET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n");
    let unknown = ipv4_tcp_packet(49152, 80, b"raw tcp payload");

    assert!(!interceptor.handle_packet(&other));
    assert!(!interceptor.handle_packet(&unknown));
    assert!(interceptor.injector.packets.is_empty());
    assert!(!interceptor.handle_packet(&matching));
    assert_eq!(interceptor.injector.packets.len(), 1);

    let tls_rule = yaml.replace("proto: [http]", "proto: [tls]");
    let mut tls_interceptor = TunEgressInterceptor::new(Some(&tls_rule), RecordingInjector::default());
    assert!(!tls_interceptor.handle_packet(&matching));
    assert!(tls_interceptor.injector.packets.is_empty());
}

#[test]
fn quic_host_rule_only_injects_for_matching_sni() {
    let yaml = r#"
version: 1
strategies:
  - id: quic-host-scoped
    match:
      proto: [quic]
      port: [443]
      hosts: [example.com]
    steps:
      - type: udplen
        delta: 4
"#;
    let mut interceptor = TunEgressInterceptor::new(Some(yaml), RecordingInjector::default());
    let other = build_realistic_quic_initial(QUIC_V1_VERSION, Some("other.example.test")).expect("QUIC initial");
    let matching = build_realistic_quic_initial(QUIC_V1_VERSION, Some("video.example.com")).expect("QUIC initial");

    assert!(!interceptor.handle_packet(&ipv4_udp_packet(49152, 443, &other)));
    assert!(interceptor.injector.packets.is_empty());
    assert!(!interceptor.handle_packet(&ipv4_udp_packet(49152, 443, &matching)));
    assert_eq!(interceptor.injector.packets.len(), 1);
}

#[test]
fn fake_rule_injects_low_ttl_tcp_copy_and_forwards_original() {
    let packet = ipv4_tcp_packet(49152, 443, b"GET / HTTP/1.1\r\nHost: example.org\r\n\r\n");
    let yaml = r#"
version: 1
strategies:
  - id: fake-tcp
    match:
      proto: [tls]
      port: [443]
    steps:
      - type: fake
        ttl: 5
"#;
    let mut interceptor = TunEgressInterceptor::new(Some(yaml), RecordingInjector::default());

    assert!(!interceptor.handle_packet(&packet));

    let injected = &interceptor.injector.packets[0];
    assert_eq!(injected[8], 5);
    assert_eq!(packet[8], 64, "original packet must remain untouched for normal TUN forwarding");
    assert_ne!(&injected[10..12], &packet[10..12], "IPv4 checksum should be refreshed after TTL change");
}

#[test]
fn ipv6_ext_rule_injects_modified_tcp_packet_and_forwards_original() {
    let packet = ipv6_tcp_packet();
    let yaml = r#"
version: 1
strategies:
  - id: ipv6-ext
    match:
      proto: [tls]
      port: [443]
    steps:
      - type: ipv6Ext
        ext_type: destopts
"#;
    let mut interceptor = TunEgressInterceptor::new(Some(yaml), RecordingInjector::default());

    assert!(!interceptor.handle_packet(&packet));

    let injected = &interceptor.injector.packets[0];
    assert_eq!(injected[6], 60);
    assert_eq!(u16::from_be_bytes([injected[4], injected[5]]), 28);
    assert_eq!(packet_destination(injected), Some(SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 443)));
}

#[test]
fn failed_injection_forwards_original_normally() {
    let packet = ipv4_udp_packet(443, 443, b"abc");
    let yaml = r#"
version: 1
strategies:
  - id: quic-udp
    steps:
      - type: udplen
"#;
    let mut interceptor = TunEgressInterceptor::new(Some(yaml), FailingInjector);

    assert!(!interceptor.handle_packet(&packet));
}

#[test]
fn lua_rawsend_injects_packet_and_consumes_original_by_default() {
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some("example.org")).expect("QUIC initial");
    let packet = ipv4_udp_packet(443, 443, &payload);
    let script = write_lua_script(
        "lua-egress-consume",
        r#"
function candidate(desync)
    desync.rawsend("lua-raw")
    return VERDICT_MODIFY
end
"#,
    );
    let yaml = format!(
        r#"
version: 1
strategies:
  - id: lua-egress
    match:
      proto: [quic]
      port: [443]
    steps:
      - type: lua
        function: candidate
        script_paths:
          - "{}"
"#,
        script.display()
    );
    let mut interceptor =
        TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), RecordingInjector::default());

    assert!(interceptor.handle_packet(&packet));
    assert_eq!(packet_payload(&interceptor.injector.packets[0]), b"lua-raw");
    assert!(packet_destination(&interceptor.injector.packets[0]).is_some());
}

#[test]
fn lua_keeps_active_state_at_capacity_and_reuses_closed_flow_slot() {
    let script = write_lua_script(
        "lua-egress-active-capacity",
        r#"
function candidate(desync)
    desync.conn.count = (desync.conn.count or 0) + 1
    desync.rawsend(tostring(desync.conn.count))
    return VERDICT_MODIFY
end
"#,
    );
    let yaml = format!(
        "version: 1\nstrategies:\n  - id: lua-egress\n    steps:\n      - type: lua\n        function: candidate\n        script_paths:\n          - \"{}\"\n",
        script.display()
    );
    let mut interceptor =
        TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), RecordingInjector::default());

    for port in 10_000..11_024 {
        assert!(interceptor.handle_packet(&ipv4_tcp_packet(port, 443, b"abc")));
    }
    assert_eq!(interceptor.injector.packets.len(), 1024);
    assert!(!interceptor.handle_packet(&ipv4_tcp_packet(11_024, 443, b"abc")));
    assert_eq!(interceptor.injector.packets.len(), 1024);

    assert!(interceptor.handle_packet(&ipv4_tcp_packet(10_000, 443, b"abc")));
    assert_eq!(packet_payload(interceptor.injector.packets.last().expect("first flow packet")), b"2");

    let src = SocketAddr::new([10, 0, 0, 2].into(), 10_000);
    let dst = SocketAddr::new([93, 184, 216, 34].into(), 443);
    interceptor.close_flow(TCP_PROTO, src, dst);
    assert!(interceptor.handle_packet(&ipv4_tcp_packet(11_024, 443, b"abc")));
    assert_eq!(packet_payload(interceptor.injector.packets.last().expect("new flow packet")), b"1");
}

#[test]
fn lua_udp_source_close_releases_all_destinations_and_idle_orphans() {
    let script = write_lua_script(
        "lua-egress-udp-source-close",
        r#"
function candidate(desync)
    desync.conn.count = (desync.conn.count or 0) + 1
    desync.rawsend(tostring(desync.conn.count))
    return VERDICT_MODIFY
end
"#,
    );
    let yaml = format!(
        "version: 1\nstrategies:\n  - id: lua-egress\n    steps:\n      - type: lua\n        function: candidate\n        script_paths:\n          - \"{}\"\n",
        script.display()
    );
    let mut interceptor =
        TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), RecordingInjector::default());
    let source = SocketAddr::new([10, 0, 0, 2].into(), 20_000);
    for port in 10_000..10_070 {
        assert!(interceptor.handle_packet(&ipv4_udp_packet(20_000, port, b"abc")));
        interceptor.mark_udp_associated(source, SocketAddr::new([93, 184, 216, 34].into(), port));
    }
    interceptor.expire_idle_udp_flows(Duration::ZERO, &|src| src == source);
    assert!(interceptor.handle_packet(&ipv4_udp_packet(20_000, 10_000, b"abc")));
    assert_eq!(packet_payload(interceptor.injector.packets.last().expect("active source packet")), b"2");
    assert!(interceptor.handle_packet(&ipv4_udp_packet(20_000, 11_000, b"abc")));
    assert!(interceptor.handle_packet(&ipv4_udp_packet(20_001, 443, b"abc")));
    interceptor.close_udp_source(source);
    assert!(interceptor.handle_packet(&ipv4_udp_packet(20_000, 10_000, b"abc")));
    assert_eq!(packet_payload(interceptor.injector.packets.last().expect("closed source packet")), b"1");
    assert!(interceptor.handle_packet(&ipv4_udp_packet(20_000, 11_000, b"abc")));
    assert_eq!(packet_payload(interceptor.injector.packets.last().expect("consumed flow packet")), b"2");
    assert!(interceptor.handle_packet(&ipv4_udp_packet(20_001, 443, b"abc")));
    assert_eq!(packet_payload(interceptor.injector.packets.last().expect("unrelated source packet")), b"2");

    interceptor.expire_idle_udp_flows(Duration::ZERO, &|_| false);
    assert!(interceptor.handle_packet(&ipv4_udp_packet(20_001, 443, b"abc")));
    assert_eq!(packet_payload(interceptor.injector.packets.last().expect("expired source packet")), b"1");
}

#[test]
fn lua_consumed_tcp_fin_releases_state() {
    let script = write_lua_script(
        "lua-egress-tcp-fin",
        "function candidate(desync) desync.conn.count = (desync.conn.count or 0) + 1; desync.rawsend(tostring(desync.conn.count)); return VERDICT_MODIFY end",
    );
    let yaml = format!(
        "version: 1\nstrategies:\n  - id: lua-egress\n    steps:\n      - type: lua\n        function: candidate\n        script_paths:\n          - \"{}\"\n",
        script.display()
    );
    let mut interceptor =
        TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), RecordingInjector::default());
    let packet = ipv4_tcp_packet(20_000, 443, b"abc");
    assert!(interceptor.handle_packet(&packet));
    let mut fin = ipv4_tcp_packet(20_000, 443, b"");
    fin[33] = 0x11;
    assert!(interceptor.handle_packet(&fin));
    assert!(interceptor.handle_packet(&packet));
    assert_eq!(packet_payload(interceptor.injector.packets.last().expect("reused flow packet")), b"1");
}

#[test]
fn lua_tun_failure_uses_configured_drop_policy() {
    let script = write_lua_script("lua-egress-on-fail", "function candidate(desync) return {} end");
    let yaml = format!(
        "version: 1\nstrategies:\n  - id: lua-egress\n    on_fail: drop\n    steps:\n      - type: lua\n        function: candidate\n        script_paths:\n          - \"{}\"\n",
        script.display()
    );
    let mut interceptor =
        TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), RecordingInjector::default());
    assert!(interceptor.handle_packet(&ipv4_tcp_packet(20_000, 443, b"abc")));
    assert!(interceptor.injector.packets.is_empty());
}

#[test]
fn lua_rawsend_can_forward_original() {
    let packet = ipv4_udp_packet(443, 443, b"abc");
    let script = write_lua_script(
        "lua-egress-forward",
        r#"
function candidate(desync)
    desync.rawsend("lua-sidecar")
    return VERDICT_MODIFY
end
"#,
    );
    let yaml = format!(
        r#"
version: 1
strategies:
  - id: lua-egress
    steps:
      - type: lua
        function: candidate
        script_paths:
          - "{}"
        forward_original: true
"#,
        script.display()
    );
    let mut interceptor =
        TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), RecordingInjector::default());

    assert!(!interceptor.handle_packet(&packet));
    assert_eq!(packet_payload(&interceptor.injector.packets[0]), b"lua-sidecar");
    assert!(packet_destination(&interceptor.injector.packets[0]).is_some());
}

#[test]
fn lua_rawsend_then_drop_injects_packet_and_consumes_original() {
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some("example.org")).expect("QUIC initial");
    let packet = ipv4_udp_packet(443, 443, &payload);
    let script = write_lua_script(
        "lua-egress-drop",
        r#"
function candidate(desync)
    desync.rawsend("lua-drop-raw")
    return VERDICT_DROP
end
"#,
    );
    let yaml = format!(
        r#"
version: 1
strategies:
  - id: lua-egress
    match:
      proto: [quic]
      port: [443]
    steps:
      - type: lua
        function: candidate
        script_paths:
          - "{}"
"#,
        script.display()
    );
    let mut interceptor =
        TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), RecordingInjector::default());

    assert!(interceptor.handle_packet(&packet));
    assert_eq!(interceptor.injector.packets.len(), 1);
    assert_eq!(packet_payload(&interceptor.injector.packets[0]), b"lua-drop-raw");
    assert!(packet_destination(&interceptor.injector.packets[0]).is_some());
}

#[test]
fn lua_context_exposes_http_markers_to_tun_strategy() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    let packet = ipv4_tcp_packet(49152, 80, payload);
    let script = write_lua_script(
        "lua-egress-http-markers",
        r#"
function candidate(desync)
    if desync.detect("http")
        and desync.dis.hostname == "example.com"
        and desync.pos("host")
        and desync.pos("endhost") then
        desync.set_ttl(9)
        desync.rawsend(desync.payload)
        return VERDICT_MODIFY
    end
    return VERDICT_PASS
end
"#,
    );
    let yaml = format!(
        r#"
version: 1
strategies:
  - id: lua-egress
    match:
      proto: [http]
      port: [80]
    steps:
      - type: lua
        function: candidate
        script_paths:
          - "{}"
"#,
        script.display()
    );
    let mut interceptor =
        TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), RecordingInjector::default());

    assert!(interceptor.handle_packet(&packet));
    assert_eq!(interceptor.injector.packets[0][8], 9);
    assert_eq!(packet_payload(&interceptor.injector.packets[0]), payload);
    assert!(packet_destination(&interceptor.injector.packets[0]).is_some());
}

#[test]
fn quic_markers_are_raw_udp_payload_offsets() {
    let payload =
        build_realistic_quic_initial(QUIC_V1_VERSION, Some("video.example.test")).expect("build QUIC initial");
    let layout = parse_quic_initial_layout(&payload).expect("parse QUIC layout");
    let packet = ipv4_udp_packet(49152, 443, &payload);
    let meta = PacketMeta::parse(&packet).expect("packet metadata");

    let dissect = dissect_packet(meta, meta.payload(&packet));

    let host = *dissect.markers.get(&MarkerName::Host).expect("host marker");
    let host_end = *dissect.markers.get(&MarkerName::HostEnd).expect("host end marker");
    let sni_ext = *dissect.markers.get(&MarkerName::SniExt).expect("sni extension marker");
    assert_eq!(host, expected_quic_marker_start(&layout, layout.info.tls_info.host_start));
    assert_eq!(host_end, expected_quic_marker_end(&layout, layout.info.tls_info.host_end));
    assert_eq!(sni_ext, expected_quic_marker_start(&layout, layout.info.tls_info.sni_ext_start));
    assert_ne!(host, layout.info.tls_info.host_start, "QUIC markers must not use reassembled TLS offsets");
    assert!(host < payload.len());
    assert!(host_end <= payload.len());
    assert!(sni_ext < payload.len());
}

#[test]
fn lua_quic_split_uses_raw_udp_payload_marker_offset() {
    let payload =
        build_realistic_quic_initial(QUIC_V1_VERSION, Some("video.example.test")).expect("build QUIC initial");
    let layout = parse_quic_initial_layout(&payload).expect("parse QUIC layout");
    let expected_host = expected_quic_marker_start(&layout, layout.info.tls_info.host_start);
    let packet = ipv4_udp_packet(49152, 443, &payload);
    let script = write_lua_script(
        "lua-egress-quic-markers",
        r#"
function candidate(desync)
    local host = desync.pos("host")
    local endhost = desync.pos("endhost")
    local sni_ext = desync.pos("sni_ext")
    if host and endhost and sni_ext then
        desync.split(host, false)
        return VERDICT_MODIFY
    end
    return VERDICT_PASS
end
"#,
    );
    let yaml = format!(
        r#"
version: 1
strategies:
  - id: lua-egress-quic-markers
    match:
      proto: [quic]
      port: [443]
    steps:
      - type: lua
        function: candidate
        script_paths:
          - "{}"
"#,
        script.display()
    );
    let mut interceptor =
        TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), RecordingInjector::default());

    assert!(interceptor.handle_packet(&packet));
    assert_eq!(packet_payload(&interceptor.injector.packets[0]), &payload[..expected_host]);
    assert_eq!(packet_payload(&interceptor.injector.packets[1]), &payload[expected_host..]);
    assert_ne!(expected_host, layout.info.tls_info.host_start, "test must catch client-hello coordinate reuse");
}

#[test]
fn lua_udplen_action_injects_valid_packet_before_consuming_original() {
    let payload = build_realistic_quic_initial(QUIC_V1_VERSION, Some("example.org")).expect("QUIC initial");
    let packet = ipv4_udp_packet(443, 443, &payload);
    let script = write_lua_script(
        "lua-egress-udplen",
        r#"
function candidate(desync)
    desync.udplen(4)
    return VERDICT_MODIFY
end
"#,
    );
    let yaml = format!(
        r#"
version: 1
strategies:
  - id: lua-egress
    match:
      proto: [quic]
      port: [443]
    steps:
      - type: lua
        function: candidate
        script_paths:
          - "{}"
"#,
        script.display()
    );
    let mut interceptor =
        TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), RecordingInjector::default());

    assert!(interceptor.handle_packet(&packet));

    let injected = &interceptor.injector.packets[0];
    let udp_len_offset = IPV4_MIN_HEADER_LEN + 4;
    assert_eq!(
        u16::from_be_bytes([injected[udp_len_offset], injected[udp_len_offset + 1]]),
        (payload.len() + 12) as u16
    );
    assert!(packet_destination(injected).is_some());
}

#[test]
fn lua_split_action_injects_valid_payload_packets_before_consuming_original() {
    let packet = ipv4_tcp_packet(49152, 443, b"abcdef");
    let script = write_lua_script(
        "lua-egress-split",
        r#"
function candidate(desync)
    desync.split(2, false)
    return VERDICT_MODIFY
end
"#,
    );
    let yaml = format!(
        r#"
version: 1
strategies:
  - id: lua-egress
    steps:
      - type: lua
        function: candidate
        script_paths:
          - "{}"
"#,
        script.display()
    );
    let mut interceptor =
        TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), RecordingInjector::default());

    assert!(interceptor.handle_packet(&packet));
    assert_eq!(packet_payload(&interceptor.injector.packets[0]), b"ab");
    assert_eq!(packet_payload(&interceptor.injector.packets[1]), b"cdef");
    assert!(packet_destination(&interceptor.injector.packets[0]).is_some());
    assert!(packet_destination(&interceptor.injector.packets[1]).is_some());
}

#[test]
fn lua_replacement_drop_failure_forwards_original() {
    let packet = ipv4_tcp_packet(49152, 443, b"abcdef");
    let script = write_lua_script(
        "lua-egress-split-failure",
        r#"
function candidate(desync)
    desync.split(2, false)
    return VERDICT_DROP
end
"#,
    );
    let yaml = format!(
        r#"
version: 1
strategies:
  - id: lua-egress
    steps:
      - type: lua
        function: candidate
        script_paths:
          - "{}"
"#,
        script.display()
    );
    let mut interceptor = TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), FailsSecondInjector(0));

    assert!(!interceptor.handle_packet(&packet));
}

#[test]
fn lua_stops_after_failed_injection() {
    let packet = ipv4_tcp_packet(49152, 443, b"abcdef");
    let script = write_lua_script(
        "lua-egress-stop-after-failure",
        r#"
function candidate(desync)
    desync.rawsend("one")
    desync.rawsend("two")
    desync.rawsend("three")
    return VERDICT_MODIFY
end
"#,
    );
    let yaml = format!(
        r#"
version: 1
strategies:
  - id: lua-egress
    steps:
      - type: lua
        function: candidate
        script_paths:
          - "{}"
"#,
        script.display()
    );
    let mut interceptor = TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), FailsSecondInjector(0));

    assert!(!interceptor.handle_packet(&packet));
    assert_eq!(interceptor.injector.0, 2);
}

#[test]
fn socket_lua_owner_keeps_other_tun_rules() {
    let yaml = r#"
version: 1
strategies:
  - id: owner-test
    steps:
      - type: lua
        function: candidate
        script_paths: [missing.lua]
      - type: fake
        ttl: 5
"#;
    let mut interceptor = TunEgressInterceptor::new_with_lua_owner(
        Some(yaml),
        std::path::Path::new("."),
        true,
        RecordingInjector::default(),
    );
    let packet = ipv4_tcp_packet(49152, 443, b"abcdef");
    assert!(!interceptor.handle_packet(&packet));
    assert_eq!(interceptor.rules.len(), 1, "only Lua must move to the socket owner");
    assert_eq!(interceptor.injector.packets.len(), 1);
}

struct FailsSecondInjector(usize);

impl TunPacketInjector for FailsSecondInjector {
    fn inject_packet(&mut self, _packet: &[u8]) -> io::Result<()> {
        self.0 += 1;
        if self.0 == 2 { Err(io::Error::other("boom")) } else { Ok(()) }
    }
}

#[derive(Default)]
struct RecordingInjector {
    packets: Vec<Vec<u8>>,
}

impl TunPacketInjector for RecordingInjector {
    fn inject_packet(&mut self, packet: &[u8]) -> io::Result<()> {
        self.packets.push(packet.to_vec());
        Ok(())
    }
}

struct FailingInjector;

impl TunPacketInjector for FailingInjector {
    fn inject_packet(&mut self, _packet: &[u8]) -> io::Result<()> {
        Err(io::Error::other("boom"))
    }
}

/// A Lua script written into a per-test jail directory. The embedded
/// [`Display`] form is the **relative** filename, so YAML `script_paths`
/// stay inside the jail the egress interceptor is built with (see
/// [`TunEgressInterceptor::new_with_base_dir`]). The temp directory is
/// removed on drop.
struct LuaScriptFixture {
    dir: std::path::PathBuf,
    filename: String,
}

impl LuaScriptFixture {
    fn dir(&self) -> &Path {
        &self.dir
    }

    /// The relative `script_paths` entry to embed in YAML — a bare
    /// filename inside [`dir`](Self::dir), kept relative so the jail
    /// resolves it inside the base directory.
    fn display(&self) -> &str {
        &self.filename
    }
}

impl Drop for LuaScriptFixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

fn write_lua_script(name: &str, contents: &str) -> LuaScriptFixture {
    let dir = std::env::temp_dir().join(format!("{name}-{}-{:?}", std::process::id(), std::thread::current().id()));
    std::fs::create_dir_all(&dir).expect("create Lua script dir");
    let filename = "candidate.lua".to_owned();
    std::fs::write(dir.join(&filename), contents).expect("write Lua script");
    LuaScriptFixture { dir, filename }
}

fn expected_quic_marker_start(layout: &QuicInitialLayout, crypto_offset: usize) -> usize {
    layout
        .crypto_frames
        .iter()
        .find_map(|frame| {
            let frame_end = frame.crypto_offset + frame.data_len;
            if crypto_offset < frame.crypto_offset || crypto_offset >= frame_end {
                return None;
            }
            Some(layout.ciphertext_payload_offset + frame.data_offset + crypto_offset - frame.crypto_offset)
        })
        .expect("QUIC marker start must map into a CRYPTO frame")
}

fn expected_quic_marker_end(layout: &QuicInitialLayout, crypto_offset: usize) -> usize {
    expected_quic_marker_start(layout, crypto_offset - 1) + 1
}

fn packet_payload(packet: &[u8]) -> &[u8] {
    let meta = PacketMeta::parse(packet).expect("packet metadata");
    meta.payload(packet)
}

fn ipv4_udp_packet(src_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let total_len = IPV4_MIN_HEADER_LEN + 8 + payload.len();
    let mut packet = vec![0u8; total_len];
    packet[0] = 0x45;
    packet[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
    packet[8] = 64;
    packet[9] = UDP_PROTO;
    packet[12..16].copy_from_slice(&[10, 0, 0, 2]);
    packet[16..20].copy_from_slice(&[93, 184, 216, 34]);
    packet[20..22].copy_from_slice(&src_port.to_be_bytes());
    packet[22..24].copy_from_slice(&dst_port.to_be_bytes());
    packet[24..26].copy_from_slice(&((8 + payload.len()) as u16).to_be_bytes());
    packet[28..].copy_from_slice(payload);
    packet
}

fn ipv4_tcp_packet(src_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let total_len = IPV4_MIN_HEADER_LEN + 20 + payload.len();
    let mut packet = vec![0u8; total_len];
    packet[0] = 0x45;
    packet[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
    packet[8] = 64;
    packet[9] = TCP_PROTO;
    packet[12..16].copy_from_slice(&[10, 0, 0, 2]);
    packet[16..20].copy_from_slice(&[93, 184, 216, 34]);
    packet[20..22].copy_from_slice(&src_port.to_be_bytes());
    packet[22..24].copy_from_slice(&dst_port.to_be_bytes());
    packet[32] = 0x50;
    packet[33] = 0x18;
    packet[34..36].copy_from_slice(&65535u16.to_be_bytes());
    packet[40..].copy_from_slice(payload);
    recompute_ipv4_header_checksum(&mut packet[..IPV4_MIN_HEADER_LEN]);
    packet
}

fn ipv6_tcp_packet() -> Vec<u8> {
    let mut packet = vec![0u8; IPV6_HEADER_LEN + 20];
    packet[0] = 0x60;
    packet[4..6].copy_from_slice(&20u16.to_be_bytes());
    packet[6] = TCP_PROTO;
    packet[7] = 64;
    packet[8..24].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
    packet[24..40].copy_from_slice(&Ipv6Addr::LOCALHOST.octets());
    packet[40..42].copy_from_slice(&49152u16.to_be_bytes());
    packet[42..44].copy_from_slice(&443u16.to_be_bytes());
    packet[52] = 0x50;
    packet[53] = 0x18;
    packet[54..56].copy_from_slice(&65535u16.to_be_bytes());
    packet
}

#[test]
fn upstream_lua_split_matches_complete_option_packets() {
    use super::upstream_vectors::*;
    let script =
        write_lua_script("upstream-vector-split", "function candidate(d) d.split(2,false); return VERDICT_MODIFY end");
    let yaml = format!(
        "version: 1\nstrategies:\n  - id: upstream-split\n    steps:\n      - type: lua\n        function: candidate\n        script_paths: [{}]\n",
        script.display()
    );
    for (original, first, second) in [
        (IPV4_TCP, IPV4_TCP_FIRST, IPV4_TCP_SECOND),
        (IPV6_TCP, IPV6_TCP_FIRST, IPV6_TCP_SECOND),
        (IPV6_HOP_TCP, IPV6_HOP_TCP_FIRST, IPV6_HOP_TCP_SECOND),
    ] {
        let packet = decode(original);
        let mut interceptor =
            TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), RecordingInjector::default());
        assert!(interceptor.handle_packet(&packet));
        assert_eq!(interceptor.injector.packets, vec![decode(first), decode(second)]);
        assert_eq!(packet, decode(original));
    }
}

#[test]
fn upstream_lua_rewrite_matches_complete_tcp_and_udp_packets() {
    use super::upstream_vectors::*;
    let script = write_lua_script("upstream-vector-modify", r"function candidate(d) return '\0new\255' end");
    let yaml = format!(
        "version: 1\nstrategies:\n  - id: upstream-modify\n    steps:\n      - type: lua\n        function: candidate\n        script_paths: [{}]\n",
        script.display()
    );
    for (original, expected) in [
        (IPV4_TCP, IPV4_TCP_REWRITE),
        (IPV6_TCP, IPV6_TCP_REWRITE),
        (IPV6_HOP_TCP, IPV6_HOP_TCP_REWRITE),
        (IPV4_UDP, IPV4_UDP_REWRITE_ZERO_SUM),
        (IPV6_HOP_UDP, IPV6_HOP_UDP_REWRITE),
    ] {
        let mut interceptor =
            TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), RecordingInjector::default());
        assert!(interceptor.handle_packet(&decode(original)));
        assert_eq!(interceptor.injector.packets, vec![decode(expected)]);
    }
    let mut interceptor =
        TunEgressInterceptor::new_with_base_dir(Some(&yaml), script.dir(), RecordingInjector::default());
    assert!(!interceptor.handle_packet(&decode(IPV6_AH_UDP)));
    assert!(interceptor.injector.packets.is_empty());
}

#[test]
fn upstream_fake_ttl_matches_complete_packets_and_forwards_original() {
    use super::upstream_vectors::*;
    let yaml = "version: 1\nstrategies:\n  - id: upstream-fake\n    steps:\n      - type: fake\n        ttl: 3\n";
    for (original, expected) in [(IPV4_TCP, IPV4_TCP_LOW_TTL), (IPV6_TCP, IPV6_TCP_LOW_TTL)] {
        let packet = decode(original);
        let mut interceptor = TunEgressInterceptor::new(Some(yaml), RecordingInjector::default());
        assert!(!interceptor.handle_packet(&packet));
        assert_eq!(interceptor.injector.packets, vec![decode(expected)]);
        assert_eq!(packet, decode(original));
    }
}
