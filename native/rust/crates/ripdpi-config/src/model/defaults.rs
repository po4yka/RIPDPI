use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, TcpListener};

use ripdpi_packets::{HttpFakeProfile, TlsFakeProfile, UdpFakeProfile};

use crate::{HOST_AUTOLEARN_DEFAULT_MAX_HOSTS, HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS};

use super::{
    DesyncGroup, DesyncGroupActionSettings, EntropyMode, FakePacketSource, HostAutolearnSettings, ListenConfig,
    QuicFakeProfile, QuicInitialMode, RuntimeAdaptiveSettings, RuntimeConfig, RuntimeNetworkSettings,
    RuntimeProcessSettings, RuntimeQuicSettings, RuntimeTimeoutSettings, WsTunnelMode,
};

impl Default for DesyncGroupActionSettings {
    fn default() -> Self {
        Self {
            http_fake_profile: HttpFakeProfile::CompatDefault,
            tls_fake_profile: TlsFakeProfile::CompatDefault,
            udp_fake_profile: UdpFakeProfile::CompatDefault,
            quic_fake_version: 0x1a2a_3a4a,
            entropy_padding_max: 256,
            ttl: None,
            auto_ttl: None,
            fake_data: None,
            fake_tls_source: FakePacketSource::Profile,
            fake_tls_secondary_profile: None,
            fake_tcp_timestamp_enabled: false,
            fake_tcp_timestamp_delta_ticks: 0,
            fake_offset: None,
            quic_fake_host: None,
            oob_data: None,
            tlsminor: None,
            window_clamp: None,
            wsize: None,
            entropy_padding_target_permil: None,
            shannon_entropy_target_permil: None,
            fake_sni_list: Vec::new(),
            tcp_chain: Vec::new(),
            rotation_policy: None,
            udp_chain: Vec::new(),
            fake_mod: 0,
            fake_tls_size: 0,
            mod_http: 0,
            md5sig: false,
            drop_sack: false,
            quic_bind_low_port: false,
            quic_migrate_after_handshake: false,
            strip_timestamps: false,
            quic_fake_profile: QuicFakeProfile::Disabled,
            entropy_mode: EntropyMode::Disabled,
        }
    }
}

impl Default for RuntimeNetworkSettings {
    fn default() -> Self {
        let ipv6 = TcpListener::bind((Ipv6Addr::LOCALHOST, 0)).is_ok();
        let bind_ip = if ipv6 { IpAddr::V6(Ipv6Addr::UNSPECIFIED) } else { IpAddr::V4(Ipv4Addr::UNSPECIFIED) };
        Self {
            listen: ListenConfig {
                listen_ip: IpAddr::V4(Ipv4Addr::LOCALHOST),
                listen_port: 1080,
                bind_ip,
                auth_token: None,
            },
            resolve: true,
            ipv6,
            udp: true,
            max_open: 512,
            buffer_size: 16_384,
            transparent: false,
            http_connect: false,
            shadowsocks: false,
            delay_conn: false,
            tfo: false,
            default_ttl: 0,
            custom_ttl: false,
        }
    }
}

impl Default for RuntimeTimeoutSettings {
    fn default() -> Self {
        Self {
            await_interval: 10,
            connect_timeout_ms: 10_000,
            freeze_window_ms: 5_000,
            freeze_min_bytes: 512,
            timeout_ms: 0,
            partial_timeout_ms: 0,
            timeout_count_limit: 0,
            timeout_bytes_limit: 0,
            wait_send: false,
            freeze_max_stalls: 0,
        }
    }
}

impl Default for RuntimeQuicSettings {
    fn default() -> Self {
        Self { initial_mode: QuicInitialMode::RouteAndCache, support_v1: true, support_v2: true }
    }
}

impl Default for RuntimeAdaptiveSettings {
    fn default() -> Self {
        Self {
            evolution_epsilon_permil: 100,
            auto_level: 0,
            cache_ttl: 0,
            cache_prefix: 0,
            network_scope_key: None,
            ws_tunnel_mode: WsTunnelMode::Off,
            ws_tunnel_fake_sni: None,
            strategy_evolution: false,
        }
    }
}

impl Default for HostAutolearnSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            penalty_ttl_secs: HOST_AUTOLEARN_DEFAULT_PENALTY_TTL_SECS,
            max_hosts: HOST_AUTOLEARN_DEFAULT_MAX_HOSTS,
            store_path: None,
            warmup_probe_enabled: true,
            network_reprobe_enabled: true,
        }
    }
}

impl Default for RuntimeConfig {
    fn default() -> Self {
        Self {
            network: RuntimeNetworkSettings::default(),
            timeouts: RuntimeTimeoutSettings::default(),
            process: RuntimeProcessSettings::default(),
            quic: RuntimeQuicSettings::default(),
            adaptive: RuntimeAdaptiveSettings::default(),
            host_autolearn: HostAutolearnSettings::default(),
            groups: vec![DesyncGroup::new(0)],
            max_route_retries: 8,
        }
    }
}
