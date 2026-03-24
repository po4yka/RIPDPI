use std::net::SocketAddr;

use ripdpi_config::{DesyncGroup, RuntimeConfig};

#[allow(dead_code)]
pub(super) mod rust_packet_seeds {
    include!(concat!(env!("CARGO_MANIFEST_DIR"), "/../ripdpi-packets/tests/rust_packet_seeds.rs"));
}

pub(super) fn sample_dest(port: u16) -> SocketAddr {
    SocketAddr::from(([203, 0, 113, 10], port))
}

pub(super) fn config_with_groups(groups: Vec<DesyncGroup>) -> RuntimeConfig {
    RuntimeConfig { groups, ..RuntimeConfig::default() }
}

pub(super) fn autolearn_config(group_count: usize, max_hosts: usize) -> RuntimeConfig {
    let groups = (0..group_count).map(DesyncGroup::new).collect();
    let mut config = config_with_groups(groups);
    config.host_autolearn.enabled = true;
    config.host_autolearn.penalty_ttl_secs = 3_600;
    config.host_autolearn.max_hosts = max_hosts;
    let mut path = std::env::temp_dir();
    path.push(format!("ripdpi-host-autolearn-{}-{group_count}-{max_hosts}.json", super::next_temp_file_nonce()));
    config.host_autolearn.store_path = Some(path.to_string_lossy().into_owned());
    config
}
