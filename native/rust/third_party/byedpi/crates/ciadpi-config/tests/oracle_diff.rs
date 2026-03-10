use std::fs;
use std::path::PathBuf;
use std::sync::OnceLock;

use ciadpi_config::{
    dump_cache_entries, load_cache_entries, parse_cli, parse_hosts_spec, parse_ipset_spec, FilterSet, ParseResult,
    StartupEnv,
};
use serde_json::Value;
#[cfg(target_os = "linux")]
use tempfile::tempdir;

fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).ancestors().nth(2).expect("workspace root").to_path_buf()
}

fn config_corpus(name: &str) -> PathBuf {
    repo_root().join("tests").join("corpus").join("config").join(name)
}

fn fixtures() -> &'static Value {
    static FIXTURES: OnceLock<Value> = OnceLock::new();
    FIXTURES.get_or_init(|| {
        serde_json::from_str(include_str!("../../../tests/corpus/rust-fixtures/config_oracle.json"))
            .expect("config fixtures")
    })
}

fn fixture(case: &str) -> &'static Value {
    &fixtures()[case]
}

fn parse_runtime(args: &[&str], startup: StartupEnv) -> ciadpi_config::RuntimeConfig {
    let args: Vec<String> = args.iter().map(|value| (*value).to_owned()).collect();
    match parse_cli(&args, &startup).expect("rust parse") {
        ParseResult::Run(config) => config,
        other => panic!("expected runtime config, got {other:?}"),
    }
}

#[test]
fn parse_args_matches_env_contract() {
    let expected = fixture("parse_args_env_contract");
    let plugin = "--no-domain --no-udp --auto torst --split 1+s --to-socks5 127.0.0.1:1081";
    let rust = parse_runtime(
        &[],
        StartupEnv {
            ss_local_port: Some("1443".to_owned()),
            ss_plugin_options: Some(plugin.to_owned()),
            protect_path_present: false,
        },
    );

    assert_eq!(rust.listen.listen_port as u64, expected["listen_port"].as_u64().unwrap());
    assert_eq!(rust.shadowsocks, expected["shadowsocks"].as_bool().unwrap());
    assert_eq!(rust.resolve, expected["resolve"].as_bool().unwrap());
    assert_eq!(rust.udp, expected["udp"].as_bool().unwrap());
    assert_eq!(rust.delay_conn, expected["delay_conn"].as_bool().unwrap());
    assert_eq!(rust.groups.len() as u64, expected["group_count"].as_u64().unwrap());
    assert_eq!(rust.actionable_group() as u64, expected["actionable_group"].as_u64().unwrap());
    let group = &rust.groups[1];
    assert_eq!(group.detect as u64, expected["group_detect"].as_u64().unwrap());
    assert_eq!(group.parts[0].mode as u64, expected["group_part_mode"].as_u64().unwrap());
    assert_eq!(group.ext_socks.expect("ext socks").addr.port() as u64, expected["ext_socks_port"].as_u64().unwrap());
}

#[cfg(target_os = "linux")]
#[test]
fn shadowsocks_protect_path_auto_detection_matches_fixture() {
    let expected = fixture("protect_path_auto_detection");
    let temp = tempdir().expect("tempdir");
    fs::write(temp.path().join("protect_path"), "").expect("protect_path");
    let rust = parse_runtime(
        &[],
        StartupEnv { ss_local_port: Some("2443".to_owned()), ss_plugin_options: None, protect_path_present: true },
    );
    assert_eq!(rust.listen.listen_port as u64, expected["listen_port"].as_u64().unwrap());
    assert_eq!(rust.protect_path.as_deref(), expected["protect_path"].as_str());
}

#[test]
fn hosts_and_ipset_matching_match_fixture() {
    let expected = fixture("hosts_and_ipset_matching");
    let hosts_spec = fs::read_to_string(config_corpus("hosts.txt")).expect("hosts");
    let ipset_spec = fs::read_to_string(config_corpus("ipset.txt")).expect("ipset");
    let filter = FilterSet {
        hosts: parse_hosts_spec(&hosts_spec).expect("parse hosts"),
        ipset: parse_ipset_spec(&ipset_spec).expect("parse ipset"),
    };

    assert_eq!(filter.hosts_match("www.example.com"), expected["host_hit"].as_bool().unwrap());
    assert_eq!(filter.hosts_match("not-example.net"), expected["host_miss"].as_bool().unwrap());
    assert_eq!(filter.ipset_match("10.1.2.3".parse().unwrap()), expected["ip_hit"].as_bool().unwrap());
    assert_eq!(filter.ipset_match("192.168.1.1".parse().unwrap()), expected["ip_miss"].as_bool().unwrap());
}

#[test]
fn cache_roundtrip_is_stable() {
    let sample = fs::read_to_string(config_corpus("cache_sample.txt")).expect("cache sample");
    let entries = load_cache_entries(&sample);
    assert_eq!(dump_cache_entries(&entries), sample);
}

#[test]
fn parse_args_with_hosts_ipset_and_cache_matches_fixture() {
    let expected = fixture("parse_args_with_hosts_ipset_and_cache");
    let hosts = config_corpus("hosts.txt");
    let ipset = config_corpus("ipset.txt");
    let args = [
        "--hosts",
        hosts.to_str().unwrap(),
        "--ipset",
        ipset.to_str().unwrap(),
        "--cache-ttl",
        "60",
        "--cache-file",
        "-",
        "--auto",
        "torst",
        "--split",
        "1+s",
    ];
    let rust = parse_runtime(&args, StartupEnv::default());

    assert_eq!(rust.groups.len() as u64, expected["group_count"].as_u64().unwrap());
    let group = &rust.groups[0];
    assert_eq!(group.filters.hosts.len() as u64, expected["hosts_count"].as_u64().unwrap());
    assert_eq!(group.filters.ipset.len() as u64, expected["ipset_count"].as_u64().unwrap());
    assert_eq!(group.cache_ttl as u64, expected["cache_ttl"].as_u64().unwrap());
    assert_eq!(group.cache_file.as_deref(), expected["cache_file"].as_str());
}

#[test]
fn parse_args_with_extended_desync_flags_matches_fixture() {
    let expected = fixture("parse_args_with_extended_desync_flags");
    let args = [
        "--http-connect",
        "--ip",
        "127.0.0.1",
        "--port",
        "2080",
        "--conn-ip",
        "127.0.0.1",
        "--max-conn",
        "33",
        "--buf-size",
        "8192",
        "--proto",
        "t,h,u,i",
        "--pf",
        "80-90",
        "--round",
        "2-4",
        "--ttl",
        "3",
        "--fake-offset",
        "1+s",
        "--fake-tls-mod",
        "rand,orig,m=128",
        "--fake-data",
        ":GET / HTTP/1.1\r\nHost: fake.example.test\r\n\r\n",
        "--oob-data",
        "Z",
        "--mod-http",
        "h,d,r",
        "--tlsminor",
        "5",
        "--udp-fake",
        "2",
    ];
    let rust = parse_runtime(&args, StartupEnv::default());
    let group = &rust.groups[0];

    assert_eq!(rust.listen.listen_ip.to_string(), expected["listen_ip"].as_str().unwrap());
    assert_eq!(rust.listen.listen_port as u64, expected["listen_port"].as_u64().unwrap());
    assert_eq!(rust.listen.bind_ip.to_string(), expected["bind_ip"].as_str().unwrap());
    assert_eq!(rust.max_open as u64, expected["max_open"].as_u64().unwrap());
    assert_eq!(rust.buffer_size as u64, expected["buffer_size"].as_u64().unwrap());
    assert_eq!(rust.http_connect, expected["http_connect"].as_bool().unwrap());
    assert_eq!(group.proto as u64, expected["proto"].as_u64().unwrap());
    assert_eq!(
        group.port_filter,
        Some((
            expected["port_filter"][0].as_u64().unwrap() as u16,
            expected["port_filter"][1].as_u64().unwrap() as u16,
        ))
    );
    assert_eq!(
        group.rounds,
        [expected["rounds"][0].as_i64().unwrap() as i32, expected["rounds"][1].as_i64().unwrap() as i32,]
    );
    assert_eq!(group.ttl, Some(expected["ttl"].as_u64().unwrap() as u8));
    assert_eq!(group.fake_mod as u64, expected["fake_mod"].as_u64().unwrap());
    assert_eq!(group.fake_tls_size as u64, expected["fake_tls_size"].as_u64().unwrap());
    assert_eq!(
        group.fake_data.as_ref().is_some_and(|value| !value.is_empty()),
        expected["has_fake_data"].as_bool().unwrap()
    );
    let fake_offset = group.fake_offset.expect("fake offset");
    assert_eq!(fake_offset.pos as i64, expected["fake_offset_pos"].as_i64().unwrap());
    assert_eq!(fake_offset.flag as u64, expected["fake_offset_flag"].as_u64().unwrap());
    assert_eq!(
        group.fake_sni_list,
        expected["fake_sni_list"]
            .as_array()
            .unwrap()
            .iter()
            .map(|value| value.as_str().unwrap().to_owned())
            .collect::<Vec<_>>()
    );
    assert_eq!(group.oob_data, Some(expected["oob_data"].as_str().unwrap().as_bytes()[0]));
    assert_eq!(group.mod_http as u64, expected["mod_http"].as_u64().unwrap());
    assert_eq!(group.tlsminor, Some(expected["tlsminor"].as_u64().unwrap() as u8));
    assert_eq!(group.udp_fake_count as u64, expected["udp_fake_count"].as_u64().unwrap());
}

#[cfg(target_os = "linux")]
#[test]
fn parse_args_with_linux_fake_flags_matches_fixture() {
    let expected = fixture("parse_args_with_linux_fake_flags");
    let args = ["--md5sig", "--fake-sni", "docs.example.test", "--fake-sni", "static.example.test", "--drop-sack"];
    let rust = parse_runtime(&args, StartupEnv::default());
    let group = &rust.groups[0];

    assert_eq!(group.md5sig, expected["md5sig"].as_bool().unwrap());
    assert_eq!(group.drop_sack, expected["drop_sack"].as_bool().unwrap());
    assert_eq!(
        group.fake_sni_list,
        expected["fake_sni_list"]
            .as_array()
            .unwrap()
            .iter()
            .map(|item| item.as_str().unwrap().to_owned())
            .collect::<Vec<_>>()
    );
}

#[test]
fn invalid_value_matches_fixture_failure() {
    let expected = fixture("invalid_value");
    let rust = parse_cli(&["--ttl".to_owned(), "999".to_owned()], &StartupEnv::default());
    assert!(!expected["ok"].as_bool().unwrap());
    assert!(rust.is_err());
}
