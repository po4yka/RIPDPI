use std::future::Future;
use std::io;
use std::net::IpAddr;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_tor::{prepare_arti_state_dirs, TorRelayClient, TorTarget};

fn _resolve_hostname_uses_tor_client<'a>(
    client: &'a TorRelayClient,
    hostname: &'a str,
) -> impl Future<Output = io::Result<Vec<IpAddr>>> + Send + 'a {
    client.resolve_hostname(hostname)
}

#[test]
fn state_directories_are_created_and_reused_without_clearing_files() {
    let root = unique_temp_root("state-directories");
    let state_dir = root.join("state");
    let cache_dir = root.join("cache");

    let dirs = prepare_arti_state_dirs(&state_dir, &cache_dir).expect("create writable Arti directories");
    std::fs::write(dirs.state_dir().join("guard-state-marker"), b"persist").expect("write marker");

    let reused = prepare_arti_state_dirs(&state_dir, &cache_dir).expect("reuse writable Arti directories");
    assert_eq!(std::fs::read(reused.state_dir().join("guard-state-marker")).expect("read marker"), b"persist");
    assert!(reused.cache_dir().is_dir());

    std::fs::remove_dir_all(root).expect("remove temp dirs");
}

#[test]
fn onion_targets_remain_tor_authorities_not_local_dns_inputs() {
    let onion = TorTarget::new("2gzyxa5ihm7ru6h2k6vzuzul4qoftkornhmcq4w4y7f2pla55atbdpqd.onion", 443)
        .expect("valid onion target");

    assert_eq!(onion.as_arti_target().0, onion.host());
}

fn unique_temp_root(name: &str) -> PathBuf {
    let now = SystemTime::now().duration_since(UNIX_EPOCH).expect("system clock after epoch").as_millis();
    std::env::temp_dir().join(format!("ripdpi-tor-{name}-{}-{now}", std::process::id()))
}
