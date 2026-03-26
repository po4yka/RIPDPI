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
    fn parse_offset_expr_flag_combos() {
        let se = parse_offset_expr("5+se").unwrap();
        assert_eq!(se, OffsetExpr::tls_marker(OffsetBase::EndHost, 5));

        let hm = parse_offset_expr("3+hm").unwrap();
        assert_eq!(hm, OffsetExpr::marker(OffsetBase::HostMid, 3));

        let nr = parse_offset_expr("0+nr").unwrap();
        assert_eq!(nr, OffsetExpr::marker(OffsetBase::PayloadRand, 0));

        let ss = parse_offset_expr("1+ss").unwrap();
        assert_eq!(ss, OffsetExpr::tls_host(1));
    }

    #[test]
    fn parse_offset_expr_named_markers() {
        assert_eq!(parse_offset_expr("method+2").unwrap(), OffsetExpr::marker(OffsetBase::Method, 2));
        assert_eq!(parse_offset_expr("midsld").unwrap(), OffsetExpr::marker(OffsetBase::MidSld, 0));
        assert_eq!(parse_offset_expr("midsld-1").unwrap(), OffsetExpr::marker(OffsetBase::MidSld, -1));
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
        for spec in ["host+", "midsld-", "unknown", "host+nope", "5+zz", "method++1", "auto()", "auto(foo)"] {
            assert!(parse_offset_expr(spec).is_err(), "{spec} should be rejected");
        }
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
            vec![UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 2, activation_filter: None }]
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
            udp_chain: vec![UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 2, activation_filter: None }],
            mod_http: MH_HMIX | MH_SPACE,
            tlsminor: Some(1),
            window_clamp: Some(2),
            strip_timestamps: false,
            entropy_padding_target_permil: None,
            entropy_padding_max: 256,
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
        let args: Vec<String> = vec!["--window-clamp", "2"].iter().map(|s| s.to_string()).collect();
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
        let args: Vec<String> = vec!["--strip-timestamps"].iter().map(|s| s.to_string()).collect();
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
            vec!["--strategy-evolution", "--evolution-epsilon", "0.2"].iter().map(|s| s.to_string()).collect();
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
            vec!["--entropy-target", "3.4", "--entropy-max-pad", "128"].iter().map(|s| s.to_string()).collect();
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
}
