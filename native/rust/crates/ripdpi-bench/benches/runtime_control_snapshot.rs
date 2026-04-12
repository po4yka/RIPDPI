use std::time::Duration;

use criterion::{criterion_group, criterion_main, BatchSize, Criterion};
use ripdpi_proxy_config::{NetworkSnapshot, WifiSnapshot};

fn sample_wifi_snapshot(ssid: &str, dns_servers: &[&str], captured_at_ms: u64) -> NetworkSnapshot {
    NetworkSnapshot {
        transport: "wifi".to_string(),
        validated: true,
        metered: false,
        private_dns_mode: "system".to_string(),
        dns_servers: dns_servers.iter().map(|server| (*server).to_string()).collect(),
        wifi: Some(WifiSnapshot {
            ssid_hash: format!("ssid-{ssid}"),
            frequency_band: "5ghz".to_string(),
            frequency_mhz: Some(5180),
            link_speed_mbps: Some(866),
            rx_link_speed_mbps: Some(866),
            tx_link_speed_mbps: Some(866),
            rssi_dbm: Some(-52),
            channel_width: "80 MHz".to_string(),
            wifi_standard: "802.11ax".to_string(),
        }),
        mtu: Some(1500),
        traffic_tx_bytes: 1024,
        traffic_rx_bytes: 2048,
        captured_at_ms,
        ..NetworkSnapshot::default()
    }
}

fn bench_runtime_control_snapshot(c: &mut Criterion) {
    let control = ripdpi_runtime::EmbeddedProxyControl::default();
    control.update_network_snapshot(sample_wifi_snapshot("ripdpi-bench", &["1.1.1.1", "8.8.8.8"], 1));

    let mut group = c.benchmark_group("runtime-control/network-snapshot");

    group.bench_function("read-only/current_network_snapshot", |b| {
        b.iter(|| {
            std::hint::black_box(control.current_network_snapshot());
        });
    });

    group.bench_function("write-then-read/update_network_snapshot", |b| {
        let snapshots = [
            sample_wifi_snapshot("ripdpi-bench-a", &["1.1.1.1", "8.8.8.8"], 2),
            sample_wifi_snapshot("ripdpi-bench-b", &["9.9.9.9", "94.140.14.14"], 3),
        ];
        let mut next = 0usize;

        b.iter_batched(
            || {
                let snapshot = snapshots[next].clone();
                next = (next + 1) % snapshots.len();
                snapshot
            },
            |snapshot| {
                control.update_network_snapshot(snapshot);
                std::hint::black_box(control.current_network_snapshot());
            },
            BatchSize::SmallInput,
        );
    });

    group.finish();
}

criterion_group! {
    name = runtime_control_snapshot;
    config = Criterion::default()
        .sample_size(100)
        .measurement_time(Duration::from_secs(10));
    targets = bench_runtime_control_snapshot
}

criterion_main!(runtime_control_snapshot);
