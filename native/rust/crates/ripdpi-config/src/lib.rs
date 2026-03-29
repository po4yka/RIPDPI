#![forbid(unsafe_code)]

mod cache;
mod constants;
mod error;
mod model;
mod parse;

pub use self::cache::*;
pub use self::constants::*;
pub use self::error::*;
pub use self::model::*;
pub use self::parse::*;

#[cfg(test)]
use self::model::common_suffix_match;
#[cfg(test)]
use self::parse::cli::{parse_numeric_addr, seconds_to_millis};
#[cfg(test)]
use self::parse::fake_profiles::lower_host_char;

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, SocketAddr};
    use std::str::FromStr;

    use ripdpi_packets::{
        HttpFakeProfile, TlsFakeProfile, UdpFakeProfile, MH_DMIX, MH_HMIX, MH_METHODEOL, MH_SPACE, MH_UNIXEOL,
    };

    #[test]
    fn parse_hosts_spec_normalizes_and_skips_invalid_tokens() {
        let hosts = parse_hosts_spec("Example.COM bad^host api-1.test").expect("parse hosts spec");

        assert_eq!(hosts, vec!["example.com", "api-1.test"]);
    }

    #[test]
    fn parse_ipset_spec_defaults_and_clamps_prefix_lengths() {
        let entries = parse_ipset_spec("192.0.2.1 2001:db8::1/129").expect("parse ipset spec");

        assert_eq!(
            entries,
            vec![
                Cidr { addr: IpAddr::from_str("192.0.2.1").expect("ipv4 addr"), bits: 32 },
                Cidr { addr: IpAddr::from_str("2001:db8::1").expect("ipv6 addr"), bits: 128 },
            ]
        );
    }

    #[test]
    fn prefix_match_bytes_honors_partial_bits() {
        assert!(prefix_match_bytes(&[0b1011_0000], &[0b1011_1111], 4));
        assert!(!prefix_match_bytes(&[0b1011_0000], &[0b1001_1111], 4));
    }

    #[test]
    fn data_from_str_all_cform_branches() {
        assert_eq!(data_from_str("\\r").unwrap(), vec![b'\r']);
        assert_eq!(data_from_str("\\n").unwrap(), vec![b'\n']);
        assert_eq!(data_from_str("\\t").unwrap(), vec![b'\t']);
        assert_eq!(data_from_str("\\\\").unwrap(), vec![b'\\']);
        assert_eq!(data_from_str("\\f").unwrap(), vec![0x0c]);
        assert_eq!(data_from_str("\\b").unwrap(), vec![0x08]);
        assert_eq!(data_from_str("\\v").unwrap(), vec![0x0b]);
        assert_eq!(data_from_str("\\a").unwrap(), vec![0x07]);
        // hex escape
        assert_eq!(data_from_str("\\x41").unwrap(), vec![0x41]);
        // octal escape
        assert_eq!(data_from_str("\\101").unwrap(), vec![0x41]);
    }

    #[test]
    fn data_from_str_trailing_backslash() {
        // Trailing backslash is emitted as literal backslash
        assert_eq!(data_from_str("abc\\").unwrap(), vec![97, 98, 99, b'\\']);
    }

    #[test]
    fn common_suffix_match_dot_boundary() {
        // "notexample.com" should NOT match rule "example.com"
        assert!(!common_suffix_match("notexample.com", "example.com"));
        // "sub.example.com" SHOULD match
        assert!(common_suffix_match("sub.example.com", "example.com"));
        // exact match
        assert!(common_suffix_match("example.com", "example.com"));
    }

    #[test]
    fn prefix_match_bytes_full_byte_boundary() {
        // 24-bit prefix (rem == 0 early return)
        assert!(prefix_match_bytes(&[192, 168, 1, 100], &[192, 168, 1, 200], 24));
        assert!(!prefix_match_bytes(&[192, 168, 2, 100], &[192, 168, 1, 200], 24));
    }

    #[test]
    fn seconds_to_millis_negative_rejected() {
        assert!(seconds_to_millis("-1").is_err());
    }

    #[test]
    fn parse_offset_expr_repeat_and_skip_values() {
        let repeated = parse_offset_expr("host+2:3").unwrap();
        assert_eq!(repeated, OffsetExpr::marker(OffsetBase::Host, 2).with_repeat_skip(3, 0));

        let repeated_with_skip = parse_offset_expr("endhost-1:2:1").unwrap();
        assert_eq!(repeated_with_skip, OffsetExpr::marker(OffsetBase::EndHost, -1).with_repeat_skip(2, 1));
    }

    #[test]
    fn parse_offset_expr_named_markers() {
        assert_eq!(parse_offset_expr("method+2").unwrap(), OffsetExpr::marker(OffsetBase::Method, 2));
        assert_eq!(parse_offset_expr("midsld").unwrap(), OffsetExpr::marker(OffsetBase::MidSld, 0));
        assert_eq!(parse_offset_expr("midsld-1").unwrap(), OffsetExpr::marker(OffsetBase::MidSld, -1));
        assert_eq!(parse_offset_expr("echext").unwrap(), OffsetExpr::marker(OffsetBase::EchExt, 0));
        assert_eq!(parse_offset_expr("echext+4").unwrap(), OffsetExpr::marker(OffsetBase::EchExt, 4));
        assert_eq!(parse_offset_expr("sniext+4").unwrap(), OffsetExpr::marker(OffsetBase::SniExt, 4));
        assert_eq!(parse_offset_expr("extlen").unwrap(), OffsetExpr::marker(OffsetBase::ExtLen, 0));
        assert_eq!(parse_offset_expr("abs-5").unwrap(), OffsetExpr::absolute(-5));
        assert_eq!(parse_offset_expr("-5").unwrap(), OffsetExpr::absolute(-5));
    }

    #[test]
    fn parse_offset_expr_adaptive_markers() {
        assert_eq!(parse_offset_expr("auto(balanced)").unwrap(), OffsetExpr::adaptive(OffsetBase::AutoBalanced));
        assert_eq!(parse_offset_expr("auto(host)").unwrap(), OffsetExpr::adaptive(OffsetBase::AutoHost));
        assert_eq!(parse_offset_expr("auto(midsld)").unwrap(), OffsetExpr::adaptive(OffsetBase::AutoMidSld));
        assert_eq!(parse_offset_expr("auto(endhost)").unwrap(), OffsetExpr::adaptive(OffsetBase::AutoEndHost));
        assert_eq!(parse_offset_expr("auto(method)").unwrap(), OffsetExpr::adaptive(OffsetBase::AutoMethod));
        assert_eq!(parse_offset_expr("auto(sniext)").unwrap(), OffsetExpr::adaptive(OffsetBase::AutoSniExt));
        assert_eq!(parse_offset_expr("auto(extlen)").unwrap(), OffsetExpr::adaptive(OffsetBase::AutoExtLen));
    }

    #[test]
    fn parse_offset_expr_rejects_invalid_marker_syntax() {
        for spec in [
            "host+",
            "midsld-",
            "unknown",
            "host+nope",
            "5+zz",
            "1+ss",
            "5+se",
            "3+hm",
            "0+nr",
            "method++1",
            "auto()",
            "auto(foo)",
            "auto(echext)",
        ] {
            assert!(parse_offset_expr(spec).is_err(), "{spec} should be rejected");
        }
    }

    #[test]
    fn fake_offset_support_rejects_adaptive_and_ech_markers() {
        assert!(OffsetExpr::marker(OffsetBase::Host, 1).supports_fake_offset());
        assert!(OffsetExpr::marker(OffsetBase::ExtLen, 0).supports_fake_offset());
        assert!(!OffsetExpr::marker(OffsetBase::EchExt, 0).supports_fake_offset());
        assert!(!OffsetExpr::adaptive(OffsetBase::AutoHost).supports_fake_offset());
    }

    #[test]
    fn parse_activation_range_specs_validate_bounds() {
        assert_eq!(parse_round_range_spec("1-3").unwrap(), NumericRange::new(1, 3));
        assert_eq!(parse_payload_size_range_spec("64-512").unwrap(), NumericRange::new(64, 512));
        assert_eq!(parse_stream_byte_range_spec("0-1199").unwrap(), NumericRange::new(0, 1199));

        assert!(parse_round_range_spec("0-2").is_err());
        assert!(parse_payload_size_range_spec("-1-5").is_err());
        assert!(parse_stream_byte_range_spec("10-2").is_err());
    }

    #[test]
    fn parse_auto_ttl_spec_validates_bounds() {
        assert_eq!(parse_auto_ttl_spec("-1,3-12").unwrap(), AutoTtlConfig { delta: -1, min_ttl: 3, max_ttl: 12 });
        assert_eq!(parse_auto_ttl_spec("0,8-8").unwrap(), AutoTtlConfig { delta: 0, min_ttl: 8, max_ttl: 8 });

        for value in ["", "-1", "-1,3", "-1,0-12", "-1,12-3", "-1,3-256", "x,3-12"] {
            assert!(parse_auto_ttl_spec(value).is_err(), "{value} should fail");
        }
    }

    #[test]
    fn parse_cli_maps_activation_ranges_into_group_filter() {
        let args = vec![
            "--round".to_string(),
            "2-4".to_string(),
            "--payload-size-range".to_string(),
            "64-512".to_string(),
            "--stream-byte-range".to_string(),
            "0-2047".to_string(),
        ];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let group = &config.groups[0];
        let activation = group.activation_filter().expect("group activation filter");

        assert_eq!(group.matches.activation_filter.and_then(|filter| filter.round), Some(NumericRange::new(2, 4)));
        assert_eq!(activation.round, Some(NumericRange::new(2, 4)));
        assert_eq!(activation.payload_size, Some(NumericRange::new(64, 512)));
        assert_eq!(activation.stream_bytes, Some(NumericRange::new(0, 2047)));
    }

    #[test]
    fn parse_cli_reads_auto_ttl_and_fixed_ttl_fallback() {
        let args = vec!["--ttl".to_string(), "9".to_string(), "--auto-ttl".to_string(), "-1,3-12".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let group = &config.groups[0];

        assert_eq!(group.actions.ttl, Some(9));
        assert_eq!(group.actions.auto_ttl, Some(AutoTtlConfig { delta: -1, min_ttl: 3, max_ttl: 12 }));
    }

    #[test]
    fn parse_cli_rejects_ech_fake_offset_marker() {
        for value in ["echext", "echext+4"] {
            let args = vec!["--fake-offset".to_string(), value.to_string()];
            assert!(parse_cli(&args, &StartupEnv::default()).is_err(), "{value} should be rejected");
        }
    }

    #[test]
    fn parse_cli_accepts_ech_split_marker() {
        let args = vec!["--split".to_string(), "echext".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };

        assert_eq!(config.groups[0].actions.tcp_chain.len(), 1);
        assert_eq!(config.groups[0].actions.tcp_chain[0].kind, TcpChainStepKind::Split);
        assert_eq!(config.groups[0].actions.tcp_chain[0].offset, OffsetExpr::marker(OffsetBase::EchExt, 0));
    }

    #[test]
    fn parse_cli_parses_seqovl_step_and_fields() {
        let args = vec![
            "--tlsrec".to_string(),
            "extlen".to_string(),
            "--seqovl".to_string(),
            "auto(midsld)".to_string(),
            "--seqovl-overlap".to_string(),
            "14".to_string(),
            "--seqovl-fake-mode".to_string(),
            "rand".to_string(),
        ];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let tcp_chain = &config.groups[0].actions.tcp_chain;

        assert_eq!(tcp_chain.len(), 2);
        assert_eq!(tcp_chain[0].kind, TcpChainStepKind::TlsRec);
        assert_eq!(tcp_chain[1].kind, TcpChainStepKind::SeqOverlap);
        assert_eq!(tcp_chain[1].offset, OffsetExpr::adaptive(OffsetBase::AutoMidSld));
        assert_eq!(tcp_chain[1].overlap_size, 14);
        assert_eq!(tcp_chain[1].seqovl_fake_mode, SeqOverlapFakeMode::Rand);
    }

    #[test]
    fn parse_cli_rejects_duplicate_seqovl_step() {
        let args = vec!["--seqovl".to_string(), "host+1".to_string(), "--seqovl".to_string(), "midsld".to_string()];

        let err = parse_cli(&args, &StartupEnv::default()).expect_err("duplicate seqovl");

        assert!(err.to_string().contains("seqovl already declared"));
    }

    #[test]
    fn parse_cli_rejects_non_leading_seqovl_step() {
        let args = vec!["--split".to_string(), "host+1".to_string(), "--seqovl".to_string(), "midsld".to_string()];

        let err = parse_cli(&args, &StartupEnv::default()).expect_err("non-leading seqovl");

        assert!(err.to_string().contains("seqovl must be the first tcp send step"));
    }

    #[test]
    fn normalize_quic_fake_host_rejects_invalid_values() {
        assert_eq!(normalize_quic_fake_host(" Example.COM. ").unwrap(), "example.com");
        assert!(normalize_quic_fake_host("127.0.0.1").is_err());
        assert!(normalize_quic_fake_host("::1").is_err());
        assert!(normalize_quic_fake_host("bad..host").is_err());
    }

    #[test]
    fn parse_quic_fake_profile_accepts_known_values() {
        assert_eq!(parse_quic_fake_profile("disabled").unwrap(), QuicFakeProfile::Disabled);
        assert_eq!(parse_quic_fake_profile("compat_default").unwrap(), QuicFakeProfile::CompatDefault);
        assert_eq!(parse_quic_fake_profile("realistic_initial").unwrap(), QuicFakeProfile::RealisticInitial);
        assert!(parse_quic_fake_profile("bogus").is_err());
    }

    #[test]
    fn parse_fake_payload_profiles_accepts_known_values() {
        assert_eq!(parse_http_fake_profile("compat_default").unwrap(), HttpFakeProfile::CompatDefault);
        assert_eq!(parse_http_fake_profile("iana_get").unwrap(), HttpFakeProfile::IanaGet);
        assert_eq!(parse_http_fake_profile("cloudflare_get").unwrap(), HttpFakeProfile::CloudflareGet);
        assert!(parse_http_fake_profile("bogus").is_err());

        assert_eq!(parse_tls_fake_profile("compat_default").unwrap(), TlsFakeProfile::CompatDefault);
        assert_eq!(parse_tls_fake_profile("google_chrome").unwrap(), TlsFakeProfile::GoogleChrome);
        assert_eq!(parse_tls_fake_profile("rutracker_kyber").unwrap(), TlsFakeProfile::RutrackerKyber);
        assert!(parse_tls_fake_profile("bogus").is_err());

        assert_eq!(parse_udp_fake_profile("compat_default").unwrap(), UdpFakeProfile::CompatDefault);
        assert_eq!(parse_udp_fake_profile("dns_query").unwrap(), UdpFakeProfile::DnsQuery);
        assert_eq!(parse_udp_fake_profile("wireguard_initiation").unwrap(), UdpFakeProfile::WireGuardInitiation);
        assert!(parse_udp_fake_profile("bogus").is_err());
    }

    #[test]
    fn parse_cli_reads_quic_fake_profile_and_host() {
        let args = vec![
            "--udp-fake".to_string(),
            "2".to_string(),
            "--fake-quic-profile".to_string(),
            "realistic_initial".to_string(),
            "--fake-quic-host".to_string(),
            "Video.Example.TEST.".to_string(),
        ];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let group = &config.groups[0];

        assert_eq!(group.actions.quic_fake_profile, QuicFakeProfile::RealisticInitial);
        assert_eq!(group.actions.quic_fake_host.as_deref(), Some("video.example.test"));
        assert_eq!(
            group.actions.udp_chain,
            vec![UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 2, split_bytes: 0, activation_filter: None }]
        );
    }

    #[test]
    fn parse_cli_reads_fake_payload_profiles() {
        let args = vec![
            "--fake-http-profile".to_string(),
            "cloudflare_get".to_string(),
            "--fake-tls-profile".to_string(),
            "google_chrome".to_string(),
            "--udp-fake".to_string(),
            "1".to_string(),
            "--fake-udp-profile".to_string(),
            "dns_query".to_string(),
        ];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let group = &config.groups[0];

        assert_eq!(group.actions.http_fake_profile, HttpFakeProfile::CloudflareGet);
        assert_eq!(group.actions.tls_fake_profile, TlsFakeProfile::GoogleChrome);
        assert_eq!(group.actions.udp_fake_profile, UdpFakeProfile::DnsQuery);
    }

    #[test]
    fn parse_cli_reads_extended_http_parser_evasions() {
        let args = vec!["--mod-http".to_string(), "h,d,r,m,u".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let group = &config.groups[0];

        assert_eq!(group.actions.mod_http, MH_HMIX | MH_DMIX | MH_SPACE | MH_METHODEOL | MH_UNIXEOL);
    }

    #[test]
    fn parse_cli_rejects_unknown_extended_http_parser_evasion_letter() {
        let args = vec!["--mod-http".to_string(), "h,u,x".to_string()];

        let err = parse_cli(&args, &StartupEnv::default()).expect_err("unknown modifier should fail");

        assert!(err.to_string().contains("--mod-http"));
    }

    #[test]
    fn parse_cli_uses_shadowsocks_startup_port_and_protect_path() {
        let startup = StartupEnv {
            ss_local_port: Some("15432".to_string()),
            ss_plugin_options: None,
            protect_path_present: true,
        };

        let ParseResult::Run(config) = parse_cli(&[], &startup).expect("parse cli") else {
            panic!("expected runnable config");
        };

        assert_eq!(config.network.listen.listen_port, 15432);
        assert!(config.network.shadowsocks);
        assert_eq!(config.process.protect_path.as_deref(), Some("protect_path"));
    }

    #[test]
    fn parse_cli_prefers_ss_plugin_options_over_explicit_args() {
        let startup = StartupEnv {
            ss_local_port: None,
            ss_plugin_options: Some("--port 2442 --debug 3".to_string()),
            protect_path_present: false,
        };
        let args = vec!["--port".to_string(), "1080".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &startup).expect("parse cli") else {
            panic!("expected runnable config");
        };

        assert_eq!(config.network.listen.listen_port, 2442);
        assert_eq!(config.process.debug, 3);
    }

    #[test]
    fn parse_numeric_addr_ipv6_bracket_forms() {
        let (ip, port) = parse_numeric_addr("[::1]:8080").unwrap();
        assert_eq!(ip, IpAddr::from_str("::1").unwrap());
        assert_eq!(port, Some(8080));

        let (ip, port) = parse_numeric_addr("[::1]").unwrap();
        assert_eq!(ip, IpAddr::from_str("::1").unwrap());
        assert_eq!(port, None);

        let (ip, port) = parse_numeric_addr("192.168.1.1:80").unwrap();
        assert_eq!(ip, IpAddr::from_str("192.168.1.1").unwrap());
        assert_eq!(port, Some(80));
    }

    #[test]
    fn parse_hosts_spec_trims_whitespace() {
        let hosts = parse_hosts_spec("  example.com  ").unwrap();
        assert_eq!(hosts, vec!["example.com"]);
    }

    #[test]
    fn lower_host_char_range_boundaries() {
        // '-' is the low bound of the range
        assert_eq!(lower_host_char('-'), Some('-'));
        // '9' is the high bound
        assert_eq!(lower_host_char('9'), Some('9'));
        // Just below range: ','
        assert_eq!(lower_host_char(','), None);
        // Uppercase converted to lowercase
        assert_eq!(lower_host_char('A'), Some('a'));
    }

    #[test]
    fn cache_entries_round_trip_through_text_format() {
        let entries = vec![
            CacheEntry {
                addr: IpAddr::from_str("192.0.2.10").expect("ipv4 addr"),
                bits: 24,
                port: 443,
                time: 123,
                host: Some("example.com".to_string()),
            },
            CacheEntry {
                addr: IpAddr::from_str("2001:db8::10").expect("ipv6 addr"),
                bits: 128,
                port: 80,
                time: 456,
                host: None,
            },
        ];

        let dumped = dump_cache_entries(&entries);
        let loaded = load_cache_entries(&dumped);

        assert_eq!(loaded, entries);
    }

    #[test]
    fn runtime_config_adapter_views_round_trip() {
        let mut config = RuntimeConfig::default();
        let network = RuntimeNetworkSettings {
            listen: ListenConfig {
                listen_ip: IpAddr::from_str("127.0.0.1").expect("listen ip"),
                listen_port: 2442,
                bind_ip: IpAddr::from_str("::1").expect("bind ip"),
            },
            resolve: false,
            ipv6: true,
            udp: false,
            transparent: true,
            http_connect: true,
            shadowsocks: true,
            delay_conn: true,
            tfo: true,
            max_open: 128,
            buffer_size: 32_768,
            default_ttl: 9,
            custom_ttl: true,
        };
        let timeouts = RuntimeTimeoutSettings {
            timeout_ms: 800,
            partial_timeout_ms: 120,
            timeout_count_limit: 4,
            timeout_bytes_limit: 2048,
            wait_send: true,
            await_interval: 15,
            connect_timeout_ms: 10_000,
            freeze_window_ms: 5_000,
            freeze_min_bytes: 512,
            freeze_max_stalls: 0,
        };
        let process = RuntimeProcessSettings {
            debug: 3,
            protect_path: Some("protect.sock".to_string()),
            daemonize: true,
            pid_file: Some("ripdpi.pid".to_string()),
        };
        let quic = RuntimeQuicSettings { initial_mode: QuicInitialMode::Route, support_v1: false, support_v2: true };
        let adaptive = RuntimeAdaptiveSettings {
            auto_level: AUTO_RECONN | AUTO_SORT,
            cache_ttl: 90,
            cache_prefix: 24,
            network_scope_key: Some("wifi:test".to_string()),
            ws_tunnel_mode: WsTunnelMode::Fallback,
            strategy_evolution: false,
            evolution_epsilon_permil: 100,
        };
        let autolearn = HostAutolearnSettings {
            enabled: true,
            penalty_ttl_secs: 7200,
            max_hosts: 1024,
            store_path: Some("hosts.json".to_string()),
        };

        config.network = network.clone();
        config.timeouts = timeouts;
        config.process = process.clone();
        config.quic = quic;
        config.adaptive = adaptive.clone();
        config.host_autolearn = autolearn.clone();

        assert_eq!(config.network, network);
        assert_eq!(config.timeouts, timeouts);
        assert_eq!(config.process, process);
        assert_eq!(config.quic, quic);
        assert_eq!(config.adaptive, adaptive);
        assert_eq!(config.host_autolearn, autolearn);
    }

    #[test]
    fn cli_parses_quic_sni_split() {
        let args = vec!["--quic-sni-split".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let group = &config.groups[0];

        assert_eq!(group.actions.udp_chain.len(), 1);
        assert_eq!(group.actions.udp_chain[0].kind, UdpChainStepKind::QuicSniSplit);
        assert_eq!(group.actions.udp_chain[0].count, 1);
    }

    #[test]
    fn cli_parses_quic_low_port() {
        let args = vec!["--quic-low-port".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };

        assert!(config.groups[0].actions.quic_bind_low_port);
    }

    #[test]
    fn cli_parses_quic_dummy_prepend() {
        let args = vec!["--quic-dummy-prepend".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let group = &config.groups[0];

        assert_eq!(group.actions.udp_chain.len(), 1);
        assert_eq!(group.actions.udp_chain[0].kind, UdpChainStepKind::DummyPrepend);
        assert_eq!(group.actions.udp_chain[0].count, 1);
    }

    #[test]
    fn cli_parses_quic_fake_version() {
        let args = vec!["--quic-fake-version".to_string(), "0x1a2a3a4a".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };
        let group = &config.groups[0];

        assert_eq!(group.actions.quic_fake_version, 0x1a2a_3a4a);
        assert_eq!(group.actions.udp_chain.len(), 1);
        assert_eq!(group.actions.udp_chain[0].kind, UdpChainStepKind::QuicFakeVersion);
    }

    #[test]
    fn cli_parses_quic_migrate() {
        let args = vec!["--quic-migrate".to_string()];

        let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
            panic!("expected runnable config");
        };

        assert!(config.groups[0].actions.quic_migrate_after_handshake);
    }

    #[test]
    fn desync_group_nested_buckets_round_trip() {
        let mut group = DesyncGroup::new(2);
        let match_settings = DesyncGroupMatchSettings {
            detect: DETECT_CONNECT | DETECT_HTTP_LOCAT,
            proto: 0x22,
            filters: FilterSet {
                hosts: vec!["video.example.test".to_string()],
                ipset: vec![Cidr { addr: IpAddr::from_str("203.0.113.10").expect("ip"), bits: 24 }],
            },
            port_filter: Some((443, 8443)),
            activation_filter: Some(ActivationFilter {
                round: Some(NumericRange::new(2, 4)),
                payload_size: Some(NumericRange::new(64, 512)),
                stream_bytes: Some(NumericRange::new(0, 2048)),
            }),
        };
        let split_offset = OffsetExpr::marker(OffsetBase::Host, 1);
        let tls_record = OffsetExpr::absolute(5);
        let action_settings = DesyncGroupActionSettings {
            ttl: Some(7),
            auto_ttl: Some(AutoTtlConfig { delta: -1, min_ttl: 3, max_ttl: 12 }),
            md5sig: true,
            fake_data: Some(vec![0x16, 0x03, 0x01]),
            fake_offset: Some(OffsetExpr::absolute(3)),
            fake_sni_list: vec!["cdn.example.test".to_string()],
            fake_mod: 3,
            fake_tls_size: 128,
            http_fake_profile: HttpFakeProfile::CloudflareGet,
            tls_fake_profile: TlsFakeProfile::GoogleChrome,
            udp_fake_profile: UdpFakeProfile::DnsQuery,
            quic_fake_profile: QuicFakeProfile::RealisticInitial,
            quic_fake_host: Some("quic.example.test".to_string()),
            drop_sack: true,
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
            quic_fake_version: 0x1a2a_3a4a,
            oob_data: Some(0x42),
            tcp_chain: vec![
                TcpChainStep::new(TcpChainStepKind::TlsRec, tls_record),
                TcpChainStep::new(TcpChainStepKind::Split, split_offset),
            ],
            udp_chain: vec![UdpChainStep {
                kind: UdpChainStepKind::FakeBurst,
                count: 2,
                split_bytes: 0,
                activation_filter: None,
            }],
            mod_http: MH_HMIX | MH_SPACE,
            tlsminor: Some(1),
            window_clamp: Some(2),
            strip_timestamps: false,
            entropy_padding_target_permil: None,
            entropy_padding_max: 256,
            entropy_mode: EntropyMode::Disabled,
            shannon_entropy_target_permil: None,
        };
        let policy_settings = DesyncGroupPolicySettings {
            ext_socks: Some(UpstreamSocksConfig {
                addr: SocketAddr::new(IpAddr::from_str("127.0.0.1").expect("proxy ip"), 1081),
            }),
            label: "primary".to_string(),
            pri: 7,
            fail_count: 2,
            cache_ttl: 60,
            cache_file: Some("cache.txt".to_string()),
        };

        group.matches = match_settings.clone();
        group.actions = action_settings.clone();
        group.policy = policy_settings.clone();

        assert_eq!(group.matches, match_settings);
        assert_eq!(group.actions, action_settings);
        assert_eq!(group.policy, policy_settings);
        assert_eq!(
            group.activation_filter(),
            Some(ActivationFilter {
                round: Some(NumericRange::new(2, 4)),
                payload_size: Some(NumericRange::new(64, 512)),
                stream_bytes: Some(NumericRange::new(0, 2048)),
            })
        );
    }

    #[test]
    fn cli_parses_window_clamp_flag() {
        let args: Vec<String> = ["--window-clamp", "2"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.window_clamp, Some(2));
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_parses_strip_timestamps_flag() {
        let args: Vec<String> = ["--strip-timestamps"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert!(config.groups[0].actions.strip_timestamps);
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_parses_strategy_evolution() {
        let args: Vec<String> =
            ["--strategy-evolution", "--evolution-epsilon", "0.2"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert!(config.adaptive.strategy_evolution);
                assert_eq!(config.adaptive.evolution_epsilon_permil, 200);
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_parses_entropy_target() {
        let args: Vec<String> =
            ["--entropy-target", "3.4", "--entropy-max-pad", "128"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.entropy_padding_target_permil, Some(3400));
                assert_eq!(config.groups[0].actions.entropy_padding_max, 128);
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_parses_entropy_mode_shannon() {
        let args: Vec<String> =
            ["--entropy-mode", "shannon", "--shannon-target", "7.92"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.entropy_mode, EntropyMode::Shannon);
                assert_eq!(config.groups[0].actions.shannon_entropy_target_permil, Some(7920));
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_parses_entropy_mode_combined() {
        let args: Vec<String> = ["--entropy-mode", "combined"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.entropy_mode, EntropyMode::Combined);
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_parses_entropy_mode_auto_as_combined() {
        let args: Vec<String> = ["--entropy-mode", "auto"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.entropy_mode, EntropyMode::Combined);
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_parses_entropy_mode_popcount() {
        let args: Vec<String> = ["--entropy-mode", "popcount"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.entropy_mode, EntropyMode::Popcount);
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_rejects_invalid_entropy_mode() {
        let args: Vec<String> = ["--entropy-mode", "invalid"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        assert!(parse_cli(&args, &startup).is_err());
    }

    #[test]
    fn cli_rejects_shannon_target_out_of_range() {
        // Above 8.0
        let args: Vec<String> = ["--shannon-target", "9.0"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        assert!(parse_cli(&args, &startup).is_err());

        // Negative
        let args: Vec<String> = ["--shannon-target", "-1.0"].iter().map(ToString::to_string).collect();
        assert!(parse_cli(&args, &startup).is_err());
    }

    #[test]
    fn cli_accepts_shannon_target_boundary_values() {
        // 0.0 is valid (extreme but allowed)
        let args: Vec<String> = ["--shannon-target", "0.0"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.shannon_entropy_target_permil, Some(0));
            }
            _ => panic!("expected Run"),
        }

        // 8.0 is valid
        let args: Vec<String> = ["--shannon-target", "8.0"].iter().map(ToString::to_string).collect();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.shannon_entropy_target_permil, Some(8000));
            }
            _ => panic!("expected Run"),
        }
    }

    #[test]
    fn cli_rejects_non_numeric_shannon_target() {
        let args: Vec<String> = ["--shannon-target", "abc"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        assert!(parse_cli(&args, &startup).is_err());
    }

    #[test]
    fn cli_entropy_mode_default_is_disabled() {
        // No entropy flags: mode should remain Disabled
        let args: Vec<String> = ["-p", "1080"].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        let result = parse_cli(&args, &startup).expect("parse");
        match result {
            ParseResult::Run(config) => {
                assert_eq!(config.groups[0].actions.entropy_mode, EntropyMode::Disabled);
                assert_eq!(config.groups[0].actions.shannon_entropy_target_permil, None);
            }
            _ => panic!("expected Run"),
        }
    }

    // --- ActivationFilter ---

    #[test]
    fn activation_filter_is_unbounded_when_all_none() {
        let filter = ActivationFilter::default();
        assert!(filter.is_unbounded());
    }

    #[test]
    fn activation_filter_is_bounded_with_any_field_set() {
        let with_round = ActivationFilter { round: Some(NumericRange::new(1, 3)), ..Default::default() };
        assert!(!with_round.is_unbounded());

        let with_payload = ActivationFilter { payload_size: Some(NumericRange::new(0, 100)), ..Default::default() };
        assert!(!with_payload.is_unbounded());

        let with_stream = ActivationFilter { stream_bytes: Some(NumericRange::new(0, 512)), ..Default::default() };
        assert!(!with_stream.is_unbounded());
    }

    // --- DesyncGroup ---

    #[test]
    fn desync_group_new_has_correct_id_and_bit() {
        let g0 = DesyncGroup::new(0);
        assert_eq!(g0.id, 0);
        assert_eq!(g0.bit, 1);

        let g3 = DesyncGroup::new(3);
        assert_eq!(g3.id, 3);
        assert_eq!(g3.bit, 8);
    }

    #[test]
    fn desync_group_is_not_actionable_when_empty() {
        let group = DesyncGroup::new(0);
        assert!(!group.is_actionable());
    }

    #[test]
    fn desync_group_is_actionable_with_tcp_chain() {
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(5)));
        assert!(group.is_actionable());
    }

    #[test]
    fn desync_group_is_actionable_with_udp_chain() {
        let mut group = DesyncGroup::new(0);
        group.actions.udp_chain.push(UdpChainStep {
            kind: UdpChainStepKind::FakeBurst,
            count: 1,
            split_bytes: 0,
            activation_filter: None,
        });
        assert!(group.is_actionable());
    }

    #[test]
    fn desync_group_is_actionable_with_mod_http() {
        let mut group = DesyncGroup::new(0);
        group.actions.mod_http = MH_HMIX;
        assert!(group.is_actionable());
    }

    #[test]
    fn desync_group_is_actionable_with_detect() {
        let mut group = DesyncGroup::new(0);
        group.matches.detect = DETECT_CONNECT;
        assert!(group.is_actionable());
    }

    #[test]
    fn desync_group_is_actionable_with_hosts() {
        let mut group = DesyncGroup::new(0);
        group.matches.filters.hosts.push("example.com".to_string());
        assert!(group.is_actionable());
    }

    #[test]
    fn desync_group_is_actionable_with_port_filter() {
        let mut group = DesyncGroup::new(0);
        group.matches.port_filter = Some((443, 443));
        assert!(group.is_actionable());
    }

    #[test]
    fn desync_group_is_actionable_with_ext_socks() {
        let mut group = DesyncGroup::new(0);
        group.policy.ext_socks =
            Some(UpstreamSocksConfig { addr: SocketAddr::new(IpAddr::from_str("127.0.0.1").unwrap(), 1081) });
        assert!(group.is_actionable());
    }

    #[test]
    fn desync_group_is_actionable_with_tlsminor() {
        let mut group = DesyncGroup::new(0);
        group.actions.tlsminor = Some(3);
        assert!(group.is_actionable());
    }

    #[test]
    fn desync_group_is_actionable_with_fake_data() {
        let mut group = DesyncGroup::new(0);
        group.actions.fake_data = Some(vec![0x00]);
        assert!(group.is_actionable());
    }

    #[test]
    fn desync_group_is_actionable_with_fake_sni_list() {
        let mut group = DesyncGroup::new(0);
        group.actions.fake_sni_list.push("cdn.test".to_string());
        assert!(group.is_actionable());
    }

    #[test]
    fn desync_group_is_actionable_with_fake_offset() {
        let mut group = DesyncGroup::new(0);
        group.actions.fake_offset = Some(OffsetExpr::absolute(3));
        assert!(group.is_actionable());
    }

    #[test]
    fn desync_group_is_actionable_with_ipset() {
        let mut group = DesyncGroup::new(0);
        group.matches.filters.ipset.push(Cidr { addr: IpAddr::from_str("10.0.0.0").unwrap(), bits: 8 });
        assert!(group.is_actionable());
    }

    #[test]
    fn desync_group_effective_chains_clone_actions() {
        let mut group = DesyncGroup::new(0);
        let step = TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::host(1));
        group.actions.tcp_chain.push(step.clone());
        assert_eq!(group.effective_tcp_chain(), vec![step]);

        let udp_step =
            UdpChainStep { kind: UdpChainStepKind::QuicSniSplit, count: 1, split_bytes: 0, activation_filter: None };
        group.actions.udp_chain.push(udp_step);
        assert_eq!(group.effective_udp_chain(), group.actions.udp_chain);
    }

    #[test]
    fn desync_group_set_activation_filter_drops_unbounded() {
        let mut group = DesyncGroup::new(0);
        group.set_activation_filter(ActivationFilter::default());
        assert_eq!(group.matches.activation_filter, None);
        assert_eq!(group.activation_filter(), None);
    }

    #[test]
    fn desync_group_set_activation_filter_keeps_bounded() {
        let mut group = DesyncGroup::new(0);
        let filter = ActivationFilter { round: Some(NumericRange::new(1, 5)), ..Default::default() };
        group.set_activation_filter(filter);
        assert!(group.matches.activation_filter.is_some());
        assert_eq!(group.activation_filter(), Some(filter));
    }

    #[test]
    fn desync_group_set_round_activation() {
        let mut group = DesyncGroup::new(0);
        group.set_round_activation(Some(NumericRange::new(2, 4)));
        let act = group.activation_filter().expect("activation filter");
        assert_eq!(act.round, Some(NumericRange::new(2, 4)));
        assert_eq!(act.payload_size, None);

        // Setting None round on otherwise-empty filter drops entire filter
        group.set_round_activation(None);
        assert_eq!(group.activation_filter(), None);
    }

    #[test]
    fn runtime_config_actionable_group_returns_first_actionable() {
        let mut config = RuntimeConfig::default();
        // Default group 0 is not actionable
        assert_eq!(config.actionable_group(), 0);

        // Add a second group that is actionable
        let mut g1 = DesyncGroup::new(1);
        g1.actions.mod_http = MH_SPACE;
        config.groups.push(g1);
        assert_eq!(config.actionable_group(), 1);
    }

    // --- TcpChainStepKind ---

    #[test]
    fn tcp_chain_step_kind_from_mode_round_trip() {
        assert_eq!(TcpChainStepKind::from_mode(DesyncMode::None), Some(TcpChainStepKind::Split));
        assert_eq!(TcpChainStepKind::from_mode(DesyncMode::Split), Some(TcpChainStepKind::Split));
        assert_eq!(TcpChainStepKind::from_mode(DesyncMode::Disorder), Some(TcpChainStepKind::Disorder));
        assert_eq!(TcpChainStepKind::from_mode(DesyncMode::Oob), Some(TcpChainStepKind::Oob));
        assert_eq!(TcpChainStepKind::from_mode(DesyncMode::Disoob), Some(TcpChainStepKind::Disoob));
        assert_eq!(TcpChainStepKind::from_mode(DesyncMode::Fake), Some(TcpChainStepKind::Fake));
    }

    #[test]
    fn tcp_chain_step_kind_as_mode() {
        assert_eq!(TcpChainStepKind::Split.as_mode(), Some(DesyncMode::Split));
        assert_eq!(TcpChainStepKind::Disorder.as_mode(), Some(DesyncMode::Disorder));
        assert_eq!(TcpChainStepKind::Fake.as_mode(), Some(DesyncMode::Fake));
        assert_eq!(TcpChainStepKind::FakeSplit.as_mode(), Some(DesyncMode::Fake));
        assert_eq!(TcpChainStepKind::FakeDisorder.as_mode(), Some(DesyncMode::Disorder));
        assert_eq!(TcpChainStepKind::HostFake.as_mode(), None);
        assert_eq!(TcpChainStepKind::Oob.as_mode(), Some(DesyncMode::Oob));
        assert_eq!(TcpChainStepKind::Disoob.as_mode(), Some(DesyncMode::Disoob));
        assert_eq!(TcpChainStepKind::TlsRec.as_mode(), None);
        assert_eq!(TcpChainStepKind::TlsRandRec.as_mode(), None);
    }

    #[test]
    fn tcp_chain_step_kind_is_tls_prelude() {
        assert!(TcpChainStepKind::TlsRec.is_tls_prelude());
        assert!(TcpChainStepKind::TlsRandRec.is_tls_prelude());
        assert!(!TcpChainStepKind::Split.is_tls_prelude());
        assert!(!TcpChainStepKind::Fake.is_tls_prelude());
    }

    // --- OffsetExpr ---

    #[test]
    fn offset_expr_absolute_positive() {
        assert_eq!(OffsetExpr::absolute(10).absolute_positive(), Some(10));
        assert_eq!(OffsetExpr::absolute(0).absolute_positive(), Some(0));
        assert_eq!(OffsetExpr::absolute(-1).absolute_positive(), None);
        assert_eq!(OffsetExpr::host(5).absolute_positive(), None);
    }

    #[test]
    fn offset_expr_needs_tls_record_adjustment() {
        // Non-Abs base always needs adjustment
        assert!(OffsetExpr::host(0).needs_tls_record_adjustment());
        // Abs with negative delta needs adjustment
        assert!(OffsetExpr::absolute(-1).needs_tls_record_adjustment());
        // Abs with non-negative delta does not need adjustment
        assert!(!OffsetExpr::absolute(0).needs_tls_record_adjustment());
        assert!(!OffsetExpr::absolute(5).needs_tls_record_adjustment());
    }

    #[test]
    fn offset_expr_tls_marker_sets_proto() {
        let expr = OffsetExpr::tls_marker(OffsetBase::Host, 3);
        assert_eq!(expr.proto, OffsetProto::TlsOnly);
        assert_eq!(expr.base, OffsetBase::Host);
        assert_eq!(expr.delta, 3);
    }

    #[test]
    fn offset_expr_tls_host_convenience() {
        let expr = OffsetExpr::tls_host(2);
        assert_eq!(expr.base, OffsetBase::Host);
        assert_eq!(expr.proto, OffsetProto::TlsOnly);
        assert_eq!(expr.delta, 2);
    }

    #[test]
    fn offset_expr_with_repeat_skip_preserves_base() {
        let base = OffsetExpr::host(5);
        let modified = base.with_repeat_skip(3, 1);
        assert_eq!(modified.base, OffsetBase::Host);
        assert_eq!(modified.delta, 5);
        assert_eq!(modified.repeats, 3);
        assert_eq!(modified.skip, 1);
    }

    // --- OffsetBase::is_adaptive ---

    #[test]
    fn offset_base_is_adaptive() {
        assert!(OffsetBase::AutoBalanced.is_adaptive());
        assert!(OffsetBase::AutoHost.is_adaptive());
        assert!(OffsetBase::AutoMidSld.is_adaptive());
        assert!(OffsetBase::AutoEndHost.is_adaptive());
        assert!(OffsetBase::AutoMethod.is_adaptive());
        assert!(OffsetBase::AutoSniExt.is_adaptive());
        assert!(OffsetBase::AutoExtLen.is_adaptive());

        assert!(!OffsetBase::Abs.is_adaptive());
        assert!(!OffsetBase::Host.is_adaptive());
        assert!(!OffsetBase::Sld.is_adaptive());
        assert!(!OffsetBase::Method.is_adaptive());
    }

    // --- WsTunnelMode ---

    #[test]
    fn ws_tunnel_mode_is_enabled() {
        assert!(!WsTunnelMode::Off.is_enabled());
        assert!(WsTunnelMode::Always.is_enabled());
        assert!(WsTunnelMode::Fallback.is_enabled());
    }

    // --- Cidr::matches ---

    #[test]
    fn cidr_matches_ipv4() {
        let cidr = Cidr { addr: IpAddr::from_str("192.168.1.0").unwrap(), bits: 24 };
        assert!(cidr.matches(IpAddr::from_str("192.168.1.100").unwrap()));
        assert!(cidr.matches(IpAddr::from_str("192.168.1.0").unwrap()));
        assert!(!cidr.matches(IpAddr::from_str("192.168.2.1").unwrap()));
    }

    #[test]
    fn cidr_matches_ipv6() {
        let cidr = Cidr { addr: IpAddr::from_str("2001:db8::").unwrap(), bits: 32 };
        assert!(cidr.matches(IpAddr::from_str("2001:db8::1").unwrap()));
        assert!(cidr.matches(IpAddr::from_str("2001:db8:ffff::1").unwrap()));
        assert!(!cidr.matches(IpAddr::from_str("2001:db9::1").unwrap()));
    }

    #[test]
    fn cidr_rejects_cross_family() {
        let v4_cidr = Cidr { addr: IpAddr::from_str("10.0.0.0").unwrap(), bits: 8 };
        assert!(!v4_cidr.matches(IpAddr::from_str("::ffff:10.0.0.1").unwrap()));

        let v6_cidr = Cidr { addr: IpAddr::from_str("::1").unwrap(), bits: 128 };
        assert!(!v6_cidr.matches(IpAddr::from_str("0.0.0.1").unwrap()));
    }

    // --- FilterSet ---

    #[test]
    fn filter_set_hosts_match_suffix_and_exact() {
        let fs = FilterSet { hosts: vec!["example.com".to_string()], ipset: vec![] };
        assert!(fs.hosts_match("example.com"));
        assert!(fs.hosts_match("sub.example.com"));
        assert!(!fs.hosts_match("notexample.com"));
        assert!(!fs.hosts_match("other.net"));
    }

    #[test]
    fn filter_set_ipset_match() {
        let fs =
            FilterSet { hosts: vec![], ipset: vec![Cidr { addr: IpAddr::from_str("10.0.0.0").unwrap(), bits: 8 }] };
        assert!(fs.ipset_match(IpAddr::from_str("10.255.255.255").unwrap()));
        assert!(!fs.ipset_match(IpAddr::from_str("11.0.0.1").unwrap()));
    }

    // --- ConfigError ---

    #[test]
    fn config_error_display_with_value() {
        let err = ConfigError::invalid("--ttl", Some("abc"));
        assert_eq!(err.to_string(), "invalid value for --ttl: abc");
    }

    #[test]
    fn config_error_display_without_value() {
        let err = ConfigError::invalid("--unknown", None::<String>);
        assert_eq!(err.to_string(), "invalid option: --unknown");
    }

    #[test]
    fn config_error_implements_std_error() {
        let err = ConfigError::invalid("test", Some("val"));
        let _: &dyn std::error::Error = &err;
    }

    // --- Cache entries ---

    #[test]
    fn load_cache_entries_skips_malformed_lines() {
        let input = "0 192.0.2.1 32 443 100 example.com\n\
                      bad line\n\
                      1 192.0.2.2 32 443 100 -\n\
                      0 not_an_ip 32 443 100 -\n\
                      0 192.0.2.3 xxx 443 100 -\n\
                      0 192.0.2.4 32 xxx 100 -\n\
                      0 192.0.2.5 32 443 xxx -\n\
                      0 192.0.2.6 32 443 200 -\n";

        let entries = load_cache_entries(input);
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].host, Some("example.com".to_string()));
        assert_eq!(entries[1].host, None);
        assert_eq!(entries[1].time, 200);
    }

    #[test]
    fn load_cache_entries_empty_string() {
        assert!(load_cache_entries("").is_empty());
    }

    #[test]
    fn dump_cache_entries_empty_list() {
        assert_eq!(dump_cache_entries(&[]), "");
    }

    // --- Default values verification ---

    #[test]
    fn default_runtime_config_has_one_group() {
        let config = RuntimeConfig::default();
        assert_eq!(config.groups.len(), 1);
        assert_eq!(config.groups[0].id, 0);
        assert_eq!(config.max_route_retries, 8);
    }

    #[test]
    fn default_timeout_settings() {
        let ts = RuntimeTimeoutSettings::default();
        assert_eq!(ts.connect_timeout_ms, 10_000);
        assert_eq!(ts.freeze_window_ms, 5_000);
        assert_eq!(ts.freeze_min_bytes, 512);
        assert_eq!(ts.await_interval, 10);
    }

    #[test]
    fn default_quic_settings() {
        let qs = RuntimeQuicSettings::default();
        assert_eq!(qs.initial_mode, QuicInitialMode::RouteAndCache);
        assert!(qs.support_v1);
        assert!(qs.support_v2);
    }

    #[test]
    fn default_adaptive_settings() {
        let adaptive = RuntimeAdaptiveSettings::default();
        assert_eq!(adaptive.auto_level, 0);
        assert_eq!(adaptive.ws_tunnel_mode, WsTunnelMode::Off);
        assert!(!adaptive.strategy_evolution);
        assert_eq!(adaptive.evolution_epsilon_permil, 100);
    }

    #[test]
    fn default_host_autolearn_settings() {
        let hl = HostAutolearnSettings::default();
        assert!(!hl.enabled);
        assert_eq!(hl.penalty_ttl_secs, HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS);
        assert_eq!(hl.max_hosts, HOST_AUTOLEARN_DEFAULT_MAX_HOSTS);
        assert_eq!(hl.store_path, None);
    }

    #[test]
    fn default_desync_group_action_settings() {
        let actions = DesyncGroupActionSettings::default();
        assert_eq!(actions.http_fake_profile, HttpFakeProfile::CompatDefault);
        assert_eq!(actions.tls_fake_profile, TlsFakeProfile::CompatDefault);
        assert_eq!(actions.udp_fake_profile, UdpFakeProfile::CompatDefault);
        assert_eq!(actions.quic_fake_profile, QuicFakeProfile::Disabled);
        assert_eq!(actions.quic_fake_version, 0x1a2a_3a4a);
        assert_eq!(actions.entropy_padding_max, 256);
        assert_eq!(actions.entropy_mode, EntropyMode::Disabled);
        assert!(!actions.md5sig);
        assert!(!actions.drop_sack);
        assert!(!actions.strip_timestamps);
    }

    #[test]
    fn default_network_settings_listen_port() {
        let net = RuntimeNetworkSettings::default();
        assert_eq!(net.listen.listen_port, 1080);
        assert_eq!(net.listen.listen_ip, IpAddr::V4(std::net::Ipv4Addr::LOCALHOST));
        assert!(net.resolve);
        assert!(net.udp);
        assert!(!net.transparent);
        assert_eq!(net.max_open, 512);
        assert_eq!(net.buffer_size, 16_384);
    }

    // --- data_from_str edge cases ---

    #[test]
    fn data_from_str_empty_string_rejected() {
        assert!(data_from_str("").is_err());
    }

    #[test]
    fn data_from_str_plain_ascii() {
        assert_eq!(data_from_str("hello").unwrap(), b"hello".to_vec());
    }

    #[test]
    fn data_from_str_mixed_escapes_and_text() {
        let result = data_from_str("A\\x42C").unwrap();
        assert_eq!(result, vec![b'A', 0x42, b'C']);
    }

    // --- file_or_inline_bytes ---

    #[test]
    fn file_or_inline_bytes_inline_data() {
        let result = file_or_inline_bytes(":hello").unwrap();
        assert_eq!(result, b"hello".to_vec());
    }

    #[test]
    fn file_or_inline_bytes_missing_file() {
        assert!(file_or_inline_bytes("/nonexistent/path/to/file").is_err());
    }

    // --- normalize_fake_host_template ---

    #[test]
    fn normalize_fake_host_template_valid() {
        assert_eq!(normalize_fake_host_template("Example.COM.").unwrap(), "example.com");
        assert_eq!(normalize_fake_host_template("sub.HOST.test").unwrap(), "sub.host.test");
    }

    #[test]
    fn normalize_fake_host_template_rejects_invalid() {
        assert!(normalize_fake_host_template("").is_err());
        assert!(normalize_fake_host_template("bad..host").is_err());
        assert!(normalize_fake_host_template("-start.com").is_err());
        assert!(normalize_fake_host_template("end-.com").is_err());
        assert!(normalize_fake_host_template("127.0.0.1").is_err());
    }

    // --- seconds_to_millis ---

    #[test]
    fn seconds_to_millis_valid_values() {
        assert_eq!(seconds_to_millis("1").unwrap(), 1000);
        assert_eq!(seconds_to_millis("0.5").unwrap(), 500);
        assert_eq!(seconds_to_millis("0").unwrap(), 0);
    }

    #[test]
    fn seconds_to_millis_non_numeric_rejected() {
        assert!(seconds_to_millis("abc").is_err());
    }

    // --- parse_offset_expr: adaptive rejects repeat/skip ---

    #[test]
    fn parse_offset_expr_adaptive_rejects_repeat_skip() {
        assert!(parse_offset_expr("auto(balanced):2").is_err());
        assert!(parse_offset_expr("auto(host):1:1").is_err());
    }

    // --- NumericRange ---

    #[test]
    fn numeric_range_new_stores_start_and_end() {
        let range = NumericRange::new(10, 20);
        assert_eq!(range.start, 10);
        assert_eq!(range.end, 20);
    }

    // --- parse_round_range_spec edge cases ---

    #[test]
    fn parse_round_range_spec_single_value_is_point_range() {
        let r = parse_round_range_spec("5").unwrap();
        assert_eq!(r, NumericRange::new(5, 5));
    }

    #[test]
    fn parse_round_range_spec_empty_rejected() {
        assert!(parse_round_range_spec("").is_err());
        assert!(parse_round_range_spec("  ").is_err());
    }

    #[test]
    fn parse_round_range_spec_inverted_range_rejected() {
        assert!(parse_round_range_spec("5-2").is_err());
    }

    // --- TcpChainStep::new default fields ---

    #[test]
    fn tcp_chain_step_new_defaults() {
        let step = TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::absolute(5));
        assert_eq!(step.kind, TcpChainStepKind::Fake);
        assert_eq!(step.offset, OffsetExpr::absolute(5));
        assert_eq!(step.activation_filter, None);
        assert_eq!(step.midhost_offset, None);
        assert_eq!(step.fake_host_template, None);
        assert_eq!(step.fragment_count, 0);
        assert_eq!(step.min_fragment_size, 0);
        assert_eq!(step.max_fragment_size, 0);
    }

    // --- EntropyMode default ---

    #[test]
    fn entropy_mode_default_is_disabled() {
        assert_eq!(EntropyMode::default(), EntropyMode::Disabled);
    }

    // --- QuicInitialMode / QuicFakeProfile defaults ---

    #[test]
    fn quic_initial_mode_default() {
        assert_eq!(QuicInitialMode::default(), QuicInitialMode::RouteAndCache);
    }

    #[test]
    fn quic_fake_profile_default() {
        assert_eq!(QuicFakeProfile::default(), QuicFakeProfile::Disabled);
    }

    // --- ParseResult variants ---

    #[test]
    fn parse_cli_help_flag() {
        let args = vec!["--help".to_string()];
        let result = parse_cli(&args, &StartupEnv::default()).expect("parse cli");
        assert_eq!(result, ParseResult::Help);
    }

    #[test]
    fn parse_cli_version_flag() {
        let args = vec!["--version".to_string()];
        let result = parse_cli(&args, &StartupEnv::default()).expect("parse cli");
        assert_eq!(result, ParseResult::Version);
    }

    // --- Constants sanity ---

    #[test]
    fn detect_constants_are_distinct_powers_of_two() {
        let flags = [
            DETECT_HTTP_LOCAT,
            DETECT_TLS_HANDSHAKE_FAILURE,
            DETECT_RECONN,
            DETECT_CONNECT,
            DETECT_TCP_RESET,
            DETECT_SILENT_DROP,
            DETECT_TLS_ALERT,
            DETECT_HTTP_BLOCKPAGE,
            DETECT_DNS_TAMPER,
            DETECT_QUIC_BREAKAGE,
            DETECT_CONNECTION_FREEZE,
        ];
        for &flag in &flags {
            assert!(flag.is_power_of_two(), "flag {flag} is not a power of two");
        }
        // All flags are unique
        for i in 0..flags.len() {
            for j in (i + 1)..flags.len() {
                assert_ne!(flags[i], flags[j], "flags at {i} and {j} collide");
            }
        }
    }

    #[test]
    fn detect_composite_constants() {
        assert_eq!(DETECT_TLS_ERR, DETECT_TLS_HANDSHAKE_FAILURE | DETECT_TLS_ALERT);
        assert_eq!(DETECT_TORST, DETECT_TCP_RESET | DETECT_SILENT_DROP | DETECT_CONNECTION_FREEZE);
    }
}
