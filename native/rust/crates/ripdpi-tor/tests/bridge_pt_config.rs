use std::path::PathBuf;

use ripdpi_tor::{TorBridgePtConfig, TorPluggableTransport, build_bridge_pt_config};

const OBFS4_BRIDGE_LINE: &str = "Bridge obfs4 192.0.2.55:38114 316E643333645F6D79216558614D3931657A5F5F cert=YXJlIGZyZXF1ZW50bHkgZnVsbCBvZiBsaXR0bGUgbWVzc2FnZXMgeW91IGNhbiBmaW5kLg iat-mode=0";
const DIRECT_BRIDGE_LINE: &str = "Bridge 192.0.2.83:80 7DD62766BF2052432051D7B7E08A22F7E34A4543";

#[test]
fn bridge_pt_config_builds_arti_config_for_managed_obfs4_transport() {
    let config = build_bridge_pt_config(TorBridgePtConfig {
        state_dir: PathBuf::from("/tmp/ripdpi-tor-test/state"),
        cache_dir: PathBuf::from("/tmp/ripdpi-tor-test/cache"),
        bridge_lines: vec![OBFS4_BRIDGE_LINE.to_owned()],
        transports: vec![TorPluggableTransport {
            protocols: vec!["obfs4".to_owned()],
            binary_path: PathBuf::from("/usr/local/bin/ripdpi-obfs4"),
            arguments: vec!["-enableLogging".to_owned()],
            run_on_startup: true,
        }],
    })
    .expect("bridge PT config builds");

    config.dir_mgr_config().expect("bridge PT config has valid storage directories");
}

#[test]
fn bridge_pt_config_rejects_direct_bridge_lines() {
    let error = build_bridge_pt_config(TorBridgePtConfig {
        state_dir: PathBuf::from("/tmp/ripdpi-tor-test/state"),
        cache_dir: PathBuf::from("/tmp/ripdpi-tor-test/cache"),
        bridge_lines: vec![DIRECT_BRIDGE_LINE.to_owned()],
        transports: vec![TorPluggableTransport {
            protocols: vec!["obfs4".to_owned()],
            binary_path: PathBuf::from("/usr/local/bin/ripdpi-obfs4"),
            arguments: vec![],
            run_on_startup: false,
        }],
    })
    .expect_err("direct bridge lines must not bootstrap in censored profile");

    assert!(error.to_string().contains("direct Tor bridge line"));
}

#[test]
fn bridge_pt_config_rejects_bridge_without_matching_transport_binary() {
    let error = build_bridge_pt_config(TorBridgePtConfig {
        state_dir: PathBuf::from("/tmp/ripdpi-tor-test/state"),
        cache_dir: PathBuf::from("/tmp/ripdpi-tor-test/cache"),
        bridge_lines: vec![OBFS4_BRIDGE_LINE.to_owned()],
        transports: vec![TorPluggableTransport {
            protocols: vec!["webtunnel".to_owned()],
            binary_path: PathBuf::from("/usr/local/bin/ripdpi-webtunnel"),
            arguments: vec![],
            run_on_startup: false,
        }],
    })
    .expect_err("bridge transport must have a configured PT binary");

    assert!(error.to_string().contains("no PT binary configured for bridge transport obfs4"));
}
