use super::helpers::{parse_host_filter_spec, parse_numeric_addr, seconds_to_millis};
use super::*;
use crate::{
    AutoTtlConfig, Cidr, EntropyMode, NumericRange, OffsetBase, OffsetExpr, ParseResult, QuicFakeProfile,
    SeqOverlapFakeMode, TcpChainStepKind, UdpChainStep, UdpChainStepKind, WsizeConfig,
};
use ripdpi_packets::{
    HttpFakeProfile, TlsFakeProfile, UdpFakeProfile, MH_DMIX, MH_HMIX, MH_METHODEOL, MH_SPACE, MH_UNIXEOL,
};
use std::net::IpAddr;
use std::str::FromStr;

#[test]
fn parse_hosts_spec_normalizes_and_skips_invalid_tokens() {
    let hosts = parse_hosts_spec("Example.COM bad^host api-1.test").expect("parse hosts spec");
    assert_eq!(hosts, vec!["example.com", "api-1.test"]);
}

#[test]
fn parse_hosts_spec_trims_whitespace() {
    let hosts = parse_hosts_spec("  example.com  ").unwrap();
    assert_eq!(hosts, vec!["example.com"]);
}

#[test]
fn parse_host_filter_spec_extracts_geo_rules() {
    let filters = parse_host_filter_spec("Example.COM geoip:RU geosite:YouTube bad^host").expect("parse host filters");

    assert_eq!(filters.hosts, vec!["example.com"]);
    assert_eq!(filters.geoip_countries, vec!["ru"]);
    assert_eq!(filters.geosite_categories, vec!["youtube"]);
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
fn seconds_to_millis_negative_rejected() {
    assert!(seconds_to_millis("-1").is_err());
}

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
    assert_eq!(config.groups[0].actions.tcp_chain[0].kind(), TcpChainStepKind::Split);
    assert_eq!(config.groups[0].actions.tcp_chain[0].offset(), OffsetExpr::marker(OffsetBase::EchExt, 0));
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
    assert_eq!(tcp_chain[0].kind(), TcpChainStepKind::TlsRec);
    assert_eq!(tcp_chain[1].kind(), TcpChainStepKind::SeqOverlap);
    assert_eq!(tcp_chain[1].offset(), OffsetExpr::adaptive(OffsetBase::AutoMidSld));
    let payload = tcp_chain[1].seq_overlap_payload().expect("seqovl payload");
    assert_eq!(payload.overlap_size, 14);
    assert_eq!(payload.fake_mode, SeqOverlapFakeMode::Rand);
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
        vec![UdpChainStep {
            kind: UdpChainStepKind::FakeBurst,
            count: 2,
            split_bytes: 0,
            activation_filter: None,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_frag_next_override: None
        }]
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
    let startup =
        StartupEnv { ss_local_port: Some("15432".to_string()), ss_plugin_options: None, protect_path_present: true };

    let ParseResult::Run(config) = parse_cli(&[], &startup).expect("parse cli") else {
        panic!("expected runnable config");
    };

    assert_eq!(config.network.listen.listen_port, 15432);
    assert!(config.network.shadowsocks);
    assert_eq!(config.process.protect_path.as_deref(), Some("protect_path"));
}

#[test]
fn parse_cli_reads_geo_database_paths() {
    let args = vec![
        "--geoip-db".to_string(),
        "/tmp/geoip.db".to_string(),
        "--geosite-db".to_string(),
        "/tmp/geosite.db".to_string(),
    ];

    let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
        panic!("expected run config");
    };

    assert_eq!(config.process.geoip_db_path.as_deref(), Some("/tmp/geoip.db"));
    assert_eq!(config.process.geosite_db_path.as_deref(), Some("/tmp/geosite.db"));
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
fn cli_parses_wsize_window_only() {
    let args: Vec<String> = ["--wsize", "4096"].iter().map(ToString::to_string).collect();
    let startup = StartupEnv::default();
    let result = parse_cli(&args, &startup).expect("parse");
    match result {
        ParseResult::Run(config) => {
            assert_eq!(config.groups[0].actions.wsize, Some(WsizeConfig { window: 4096, scale: None }));
        }
        _ => panic!("expected Run"),
    }
}

#[test]
fn cli_parses_wsize_with_scale() {
    let args: Vec<String> = ["--wsize", "4096:2"].iter().map(ToString::to_string).collect();
    let startup = StartupEnv::default();
    let result = parse_cli(&args, &startup).expect("parse");
    match result {
        ParseResult::Run(config) => {
            assert_eq!(config.groups[0].actions.wsize, Some(WsizeConfig { window: 4096, scale: Some(2) }));
        }
        _ => panic!("expected Run"),
    }
}

#[test]
fn cli_rejects_wsize_invalid_scale() {
    let args: Vec<String> = ["--wsize", "4096:15"].iter().map(ToString::to_string).collect();
    let startup = StartupEnv::default();
    assert!(parse_cli(&args, &startup).is_err());
}

#[test]
fn cli_rejects_wsize_invalid_value() {
    let args: Vec<String> = ["--wsize", "abc"].iter().map(ToString::to_string).collect();
    let startup = StartupEnv::default();
    assert!(parse_cli(&args, &startup).is_err());
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
            // Defaults survive a partial-flag run.
            assert_eq!(config.adaptive.evolution_experiment_ttl_ms, 30_000);
            assert_eq!(config.adaptive.evolution_decay_half_life_ms, 3_600_000);
            assert_eq!(config.adaptive.evolution_cooldown_after_failures, 3);
            assert_eq!(config.adaptive.evolution_cooldown_ms, 300_000);
        }
        _ => panic!("expected Run"),
    }
}

#[test]
fn cli_parses_evolution_time_knobs() {
    let args: Vec<String> = [
        "--strategy-evolution",
        "--evolution-experiment-ttl-ms",
        "45000",
        "--evolution-decay-half-life-ms",
        "1800000",
        "--evolution-cooldown-after-failures",
        "5",
        "--evolution-cooldown-ms",
        "120000",
    ]
    .iter()
    .map(ToString::to_string)
    .collect();
    let startup = StartupEnv::default();
    let result = parse_cli(&args, &startup).expect("parse");
    match result {
        ParseResult::Run(config) => {
            assert!(config.adaptive.strategy_evolution);
            assert_eq!(config.adaptive.evolution_experiment_ttl_ms, 45_000);
            assert_eq!(config.adaptive.evolution_decay_half_life_ms, 1_800_000);
            assert_eq!(config.adaptive.evolution_cooldown_after_failures, 5);
            assert_eq!(config.adaptive.evolution_cooldown_ms, 120_000);
        }
        _ => panic!("expected Run"),
    }
}

#[test]
fn cli_rejects_invalid_evolution_time_knob_values() {
    for bad in ["abc", "-1", "1.5"] {
        let args: Vec<String> = ["--evolution-experiment-ttl-ms", bad].iter().map(ToString::to_string).collect();
        let startup = StartupEnv::default();
        assert!(parse_cli(&args, &startup).is_err(), "should reject {bad}");
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

// --- New coverage gap tests ---

#[test]
fn parse_cli_empty_args_defaults() {
    let ParseResult::Run(config) = parse_cli(&[], &StartupEnv::default()).expect("parse cli") else {
        panic!("expected runnable config");
    };
    assert_eq!(config.network.listen.listen_port, 1080);
    // Empty args: parser adds a second group when all are "limited"
    assert_eq!(config.groups.len(), 2);
    assert!(!config.groups[0].is_actionable());
}

#[test]
fn parse_cli_port_flag() {
    let args = vec!["-p".to_string(), "9090".to_string()];
    let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
        panic!("expected runnable config");
    };
    assert_eq!(config.network.listen.listen_port, 9090);
}

#[test]
fn parse_cli_debug_flag() {
    let args = vec!["--debug".to_string(), "2".to_string()];
    let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
        panic!("expected runnable config");
    };
    assert_eq!(config.process.debug, 2);
}

#[test]
fn parse_cli_multiple_desync_modes() {
    let args = vec!["--split".to_string(), "host+1".to_string(), "--fake".to_string(), "midsld".to_string()];
    let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
        panic!("expected runnable config");
    };
    assert_eq!(config.groups[0].actions.tcp_chain.len(), 2);
    assert_eq!(config.groups[0].actions.tcp_chain[0].kind(), TcpChainStepKind::Split);
    assert_eq!(config.groups[0].actions.tcp_chain[1].kind(), TcpChainStepKind::Fake);
}

#[test]
fn parse_cli_host_autolearn_flags() {
    let args = vec![
        "--host-autolearn".to_string(),
        "--host-autolearn-penalty-ttl".to_string(),
        "3600".to_string(),
        "--host-autolearn-max-hosts".to_string(),
        "256".to_string(),
    ];
    let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
        panic!("expected runnable config");
    };
    assert!(config.host_autolearn.enabled);
    assert_eq!(config.host_autolearn.penalty_ttl_secs, 3600);
    assert_eq!(config.host_autolearn.max_hosts, 256);
    // store_path should have default when enabled
    assert!(config.host_autolearn.store_path.is_some());
}

#[test]
fn parse_cli_oob_data_flag() {
    // oob-data uses data_from_str, so hex escape syntax is \x42
    let args = vec!["--oob".to_string(), "host+1".to_string(), "--oob-data".to_string(), "\\x42".to_string()];
    let ParseResult::Run(config) = parse_cli(&args, &StartupEnv::default()).expect("parse cli") else {
        panic!("expected runnable config");
    };
    assert_eq!(config.groups[0].actions.oob_data, Some(0x42));
}
