pub(crate) use super::*;
impl From<FlatResolvedRelayRuntimeConfig> for ResolvedRelayRuntimeConfig {
    fn from(flat: FlatResolvedRelayRuntimeConfig) -> Self {
        let vless_flow = normalize_vless_flow(flat.vless_flow);
        let common = CommonRelayConfig {
            enabled: flat.enabled,
            profile_id: flat.profile_id,
            outbound_bind_ip: flat.outbound_bind_ip,
            socket_protection: flat.socket_protection,
            server: flat.server,
            server_port: flat.server_port,
            server_name: flat.server_name,
            local_socks_host: flat.local_socks_host,
            local_socks_port: flat.local_socks_port,
            udp_enabled: flat.udp_enabled,
            tcp_fallback_enabled: flat.tcp_fallback_enabled,
            quic_bind_low_port: flat.quic_bind_low_port,
            quic_migrate_after_handshake: flat.quic_migrate_after_handshake,
            tls_fingerprint_profile: flat.tls_fingerprint_profile,
            finalmask: flat.finalmask,
        };
        let backend = match flat.kind.as_str() {
            "hysteria2" => RelayBackendConfig::Hysteria2(Hysteria2RelayConfig {
                password: flat.hysteria_password,
                salamander_key: flat.hysteria_salamander_key,
                insecure: flat.hysteria_insecure,
            }),
            "tuic_v5" => RelayBackendConfig::TuicV5(TuicRelayConfig {
                uuid: flat.tuic_uuid,
                password: flat.tuic_password,
                zero_rtt: flat.tuic_zero_rtt,
                congestion_control: flat.tuic_congestion_control,
            }),
            "vless" => RelayBackendConfig::Vless(VlessRelayConfig {
                vless_flow: vless_flow.clone(),
                vless_transport: flat.vless_transport,
                xhttp_path: flat.xhttp_path,
                xhttp_host: flat.xhttp_host,
                xhttp_mode: flat.xhttp_mode,
                uuid: flat.vless_uuid,
            }),
            "vless_reality" => RelayBackendConfig::VlessReality(VlessRealityRelayConfig {
                reality_public_key: flat.reality_public_key,
                reality_short_id: flat.reality_short_id,
                vless_flow,
                vless_transport: flat.vless_transport,
                xhttp_path: flat.xhttp_path,
                xhttp_host: flat.xhttp_host,
                xhttp_mode: flat.xhttp_mode,
                vless_mux_protocol: flat.vless_mux_protocol,
                vless_mux_max_concurrent_streams: flat.vless_mux_max_concurrent_streams,
                vless_mux_per_connection_kbps: flat.vless_mux_per_connection_kbps,
                vless_mux_padding_max: flat.vless_mux_padding_max,
                uuid: flat.vless_uuid,
            }),
            "mieru" => RelayBackendConfig::Mieru(MieruRelayConfig {
                server: flat.mieru_server,
                port: flat.mieru_port,
                username: flat.mieru_username,
                password: flat.mieru_password,
                protocol: flat.mieru_protocol,
                multiplexing: flat.mieru_multiplexing,
                mtu: flat.mieru_mtu,
            }),
            "ssh" => RelayBackendConfig::Ssh(SshRelayConfig {
                host: flat.ssh_host,
                port: flat.ssh_port,
                username: flat.ssh_username,
                auth_type: flat.ssh_auth_type,
                password: flat.ssh_password,
                private_key: flat.ssh_private_key,
                private_key_passphrase: flat.ssh_private_key_passphrase,
                host_key_fingerprint: flat.ssh_host_key_fingerprint,
                strict_host_key: flat.ssh_strict_host_key,
            }),
            "cloudflare_tunnel" => RelayBackendConfig::CloudflareTunnel(CloudflareTunnelRelayConfig {
                uuid: flat.vless_uuid,
                xhttp_path: flat.xhttp_path,
                xhttp_host: flat.xhttp_host,
                tunnel_mode: flat.cloudflare_tunnel_mode,
                publish_local_origin_url: flat.cloudflare_publish_local_origin_url,
                credentials_ref: flat.cloudflare_credentials_ref,
                tunnel_token: flat.cloudflare_tunnel_token,
                tunnel_credentials_json: flat.cloudflare_tunnel_credentials_json,
            }),
            "chain_relay" => RelayBackendConfig::ChainRelay(ChainRelayConfig {
                // A populated `chain_hops` list is the N-hop (3- or 4-hop)
                // source of truth and flows straight into
                // `ChainRelayConfig::ordered_hops`. A plain 2-hop chain carries
                // required resolved entry/exit configs; scalar-only legacy
                // payloads are rejected before this conversion. The 2..=4 bound is enforced
                // downstream by `ChainRelayConfig::validate_hop_count` at build
                // time (see backend/builder/builders/chain_relay.rs).
                hops: flat.chain_hops,
                entry: flat.chain_entry.map(Box::new),
                entry_server: flat.chain_entry_server,
                entry_port: flat.chain_entry_port,
                entry_server_name: flat.chain_entry_server_name,
                entry_public_key: flat.chain_entry_public_key,
                entry_short_id: flat.chain_entry_short_id,
                entry_profile_id: flat.chain_entry_profile_id,
                entry_uuid: flat.chain_entry_uuid,
                exit: flat.chain_exit.map(Box::new),
                exit_server: flat.chain_exit_server,
                exit_port: flat.chain_exit_port,
                exit_server_name: flat.chain_exit_server_name,
                exit_public_key: flat.chain_exit_public_key,
                exit_short_id: flat.chain_exit_short_id,
                exit_profile_id: flat.chain_exit_profile_id,
                exit_uuid: flat.chain_exit_uuid,
            }),
            "masque" => RelayBackendConfig::Masque(MasqueRelayConfig {
                url: flat.masque_url,
                proxy_socket_addr: flat.masque_proxy_socket_addr.and_then(|value| value.parse().ok()),
                tcp_protocol: flat.masque_tcp_protocol,
                use_http2_fallback: flat.masque_use_http2_fallback,
                cloudflare_geohash_enabled: flat.masque_cloudflare_geohash_enabled,
                auth_mode: flat.masque_auth_mode,
                auth_token: flat.masque_auth_token,
                client_certificate_chain_pem: flat.masque_client_certificate_chain_pem,
                client_private_key_pem: flat.masque_client_private_key_pem,
                cloudflare_geohash_header: flat.masque_cloudflare_geohash_header,
                privacy_pass_provider_url: flat.masque_privacy_pass_provider_url,
                privacy_pass_provider_auth_token: flat.masque_privacy_pass_provider_auth_token,
            }),
            "shadowtls_v3" => RelayBackendConfig::ShadowTlsV3(ShadowTlsRelayConfig {
                password: flat.shadow_tls_password,
                inner_profile_id: flat.shadow_tls_inner_profile_id,
                inner: flat.shadow_tls_inner,
            }),
            "trojan" => RelayBackendConfig::Trojan(TrojanRelayConfig {
                password: flat.trojan_password,
                root_certificate_pem: flat.trojan_root_certificate_pem,
            }),
            "anytls" => RelayBackendConfig::AnyTls(AnyTlsRelayConfig {
                password: flat.anytls_password,
                root_certificate_pem: flat.anytls_root_certificate_pem,
            }),
            "shadowsocks" => RelayBackendConfig::Shadowsocks(ShadowsocksRelayConfig {
                method: flat.shadowsocks_method,
                password: flat.shadowsocks_password,
            }),
            "tor" => RelayBackendConfig::Tor(TorRelayConfig {
                state_dir: flat.tor_state_dir,
                cache_dir: flat.tor_cache_dir,
                bridge_lines: flat.tor_bridge_lines,
                transports: flat.tor_transports,
            }),
            "naiveproxy" => RelayBackendConfig::NaiveProxy(NaiveProxyRelayConfig {
                path: flat.naive_path,
                username: flat.naive_username,
                password: flat.naive_password,
            }),
            other => RelayBackendConfig::Unsupported(UnsupportedRelayConfig { kind: other.to_string() }),
        };
        Self { common, backend }
    }
}

fn normalize_vless_flow(flow: String) -> String {
    flow.trim().to_string()
}

impl From<&ResolvedRelayRuntimeConfig> for FlatResolvedRelayRuntimeConfig {
    fn from(config: &ResolvedRelayRuntimeConfig) -> Self {
        let mut flat = Self {
            schema_version: SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION,
            enabled: config.common.enabled,
            kind: config.kind_id().to_string(),
            profile_id: config.common.profile_id.clone(),
            outbound_bind_ip: config.common.outbound_bind_ip.clone(),
            socket_protection: config.common.socket_protection,
            server: config.common.server.clone(),
            server_port: config.common.server_port,
            server_name: config.common.server_name.clone(),
            reality_public_key: String::new(),
            reality_short_id: String::new(),
            vless_flow: String::new(),
            vless_transport: String::new(),
            xhttp_path: String::new(),
            xhttp_host: String::new(),
            xhttp_mode: "auto".to_string(),
            vless_mux_protocol: String::new(),
            vless_mux_max_concurrent_streams: 0,
            vless_mux_per_connection_kbps: 0,
            vless_mux_padding_max: 0,
            cloudflare_tunnel_mode: String::new(),
            cloudflare_publish_local_origin_url: String::new(),
            cloudflare_credentials_ref: String::new(),
            chain_entry_server: String::new(),
            chain_entry_port: 0,
            chain_entry_server_name: String::new(),
            chain_entry_public_key: String::new(),
            chain_entry_short_id: String::new(),
            chain_entry_profile_id: String::new(),
            chain_entry: None,
            chain_exit_server: String::new(),
            chain_exit_port: 0,
            chain_exit_server_name: String::new(),
            chain_exit_public_key: String::new(),
            chain_exit_short_id: String::new(),
            chain_exit_profile_id: String::new(),
            chain_exit: None,
            chain_hops: Vec::new(),
            masque_url: String::new(),
            masque_proxy_socket_addr: None,
            masque_tcp_protocol: "http2".to_string(),
            masque_use_http2_fallback: false,
            masque_cloudflare_geohash_enabled: false,
            tuic_zero_rtt: false,
            tuic_congestion_control: String::new(),
            shadow_tls_inner_profile_id: String::new(),
            shadow_tls_inner: None,
            trojan_root_certificate_pem: None,
            anytls_root_certificate_pem: None,
            naive_path: String::new(),
            tor_state_dir: String::new(),
            tor_cache_dir: String::new(),
            tor_bridge_lines: Vec::new(),
            tor_transports: Vec::new(),
            local_socks_host: config.common.local_socks_host.clone(),
            local_socks_port: config.common.local_socks_port,
            udp_enabled: config.common.udp_enabled,
            tcp_fallback_enabled: config.common.tcp_fallback_enabled,
            quic_bind_low_port: config.common.quic_bind_low_port,
            quic_migrate_after_handshake: config.common.quic_migrate_after_handshake,
            vless_uuid: None,
            chain_entry_uuid: None,
            chain_exit_uuid: None,
            hysteria_password: None,
            hysteria_salamander_key: None,
            hysteria_insecure: false,
            tuic_uuid: None,
            tuic_password: None,
            shadow_tls_password: None,
            trojan_password: None,
            anytls_password: None,
            shadowsocks_method: String::new(),
            shadowsocks_password: None,
            naive_username: None,
            naive_password: None,
            tls_fingerprint_profile: config.common.tls_fingerprint_profile.clone(),
            masque_auth_mode: None,
            masque_auth_token: None,
            masque_client_certificate_chain_pem: None,
            masque_client_private_key_pem: None,
            masque_cloudflare_geohash_header: None,
            masque_privacy_pass_provider_url: None,
            masque_privacy_pass_provider_auth_token: None,
            cloudflare_tunnel_token: None,
            cloudflare_tunnel_credentials_json: None,
            finalmask: config.common.finalmask.clone(),
            mieru_server: String::new(),
            mieru_port: 0,
            mieru_username: None,
            mieru_password: None,
            mieru_protocol: String::new(),
            mieru_multiplexing: String::new(),
            mieru_mtu: 0,
            ssh_host: String::new(),
            ssh_port: 0,
            ssh_username: None,
            ssh_auth_type: String::new(),
            ssh_password: None,
            ssh_private_key: None,
            ssh_private_key_passphrase: None,
            ssh_host_key_fingerprint: None,
            ssh_strict_host_key: false,
        };
        match &config.backend {
            RelayBackendConfig::Hysteria2(config) => {
                flat.hysteria_password = config.password.clone();
                flat.hysteria_salamander_key = config.salamander_key.clone();
                flat.hysteria_insecure = config.insecure;
            }
            RelayBackendConfig::TuicV5(config) => {
                flat.tuic_uuid = config.uuid.clone();
                flat.tuic_password = config.password.clone();
                flat.tuic_zero_rtt = config.zero_rtt;
                flat.tuic_congestion_control = config.congestion_control.clone();
            }
            RelayBackendConfig::Vless(config) => {
                flat.vless_flow = config.vless_flow.clone();
                flat.vless_transport = config.vless_transport.clone();
                flat.xhttp_path = config.xhttp_path.clone();
                flat.xhttp_host = config.xhttp_host.clone();
                flat.xhttp_mode = config.xhttp_mode.clone();
                flat.vless_uuid = config.uuid.clone();
            }
            RelayBackendConfig::VlessReality(config) => {
                flat.reality_public_key = config.reality_public_key.clone();
                flat.reality_short_id = config.reality_short_id.clone();
                flat.vless_flow = config.vless_flow.clone();
                flat.vless_transport = config.vless_transport.clone();
                flat.xhttp_path = config.xhttp_path.clone();
                flat.xhttp_host = config.xhttp_host.clone();
                flat.xhttp_mode = config.xhttp_mode.clone();
                flat.vless_mux_protocol = config.vless_mux_protocol.clone();
                flat.vless_mux_max_concurrent_streams = config.vless_mux_max_concurrent_streams;
                flat.vless_mux_per_connection_kbps = config.vless_mux_per_connection_kbps;
                flat.vless_mux_padding_max = config.vless_mux_padding_max;
                flat.vless_uuid = config.uuid.clone();
            }
            RelayBackendConfig::Mieru(config) => {
                flat.mieru_server = config.server.clone();
                flat.mieru_port = config.port;
                flat.mieru_username = config.username.clone();
                flat.mieru_password = config.password.clone();
                flat.mieru_protocol = config.protocol.clone();
                flat.mieru_multiplexing = config.multiplexing.clone();
                flat.mieru_mtu = config.mtu;
            }
            RelayBackendConfig::Ssh(config) => {
                flat.ssh_host = config.host.clone();
                flat.ssh_port = config.port;
                flat.ssh_username = config.username.clone();
                flat.ssh_auth_type = config.auth_type.clone();
                flat.ssh_password = config.password.clone();
                flat.ssh_private_key = config.private_key.clone();
                flat.ssh_private_key_passphrase = config.private_key_passphrase.clone();
                flat.ssh_host_key_fingerprint = config.host_key_fingerprint.clone();
                flat.ssh_strict_host_key = config.strict_host_key;
            }
            RelayBackendConfig::CloudflareTunnel(config) => {
                flat.vless_uuid = config.uuid.clone();
                flat.xhttp_path = config.xhttp_path.clone();
                flat.xhttp_host = config.xhttp_host.clone();
                flat.cloudflare_tunnel_mode = config.tunnel_mode.clone();
                flat.cloudflare_publish_local_origin_url = config.publish_local_origin_url.clone();
                flat.cloudflare_credentials_ref = config.credentials_ref.clone();
                flat.cloudflare_tunnel_token = config.tunnel_token.clone();
                flat.cloudflare_tunnel_credentials_json = config.tunnel_credentials_json.clone();
            }
            RelayBackendConfig::ChainRelay(config) => {
                // Emit the ordered N-hop list back to the wire so a re-serialized
                // 3-/4-hop chain stays N-hop instead of being lossily folded
                // into the two-hop mirrors. Resolved entry/exit configs carry
                // 2-hop identity; scalar fields remain derived diagnostics.
                flat.chain_hops = config.hops.clone();
                flat.chain_entry = config.entry.as_deref().cloned();
                flat.chain_entry_server = config.entry_server.clone();
                flat.chain_entry_port = config.entry_port;
                flat.chain_entry_server_name = config.entry_server_name.clone();
                flat.chain_entry_public_key = config.entry_public_key.clone();
                flat.chain_entry_short_id = config.entry_short_id.clone();
                flat.chain_entry_profile_id = config.entry_profile_id.clone();
                flat.chain_entry_uuid = config.entry_uuid.clone();
                flat.chain_exit = config.exit.as_deref().cloned();
                flat.chain_exit_server = config.exit_server.clone();
                flat.chain_exit_port = config.exit_port;
                flat.chain_exit_server_name = config.exit_server_name.clone();
                flat.chain_exit_public_key = config.exit_public_key.clone();
                flat.chain_exit_short_id = config.exit_short_id.clone();
                flat.chain_exit_profile_id = config.exit_profile_id.clone();
                flat.chain_exit_uuid = config.exit_uuid.clone();
            }
            RelayBackendConfig::Masque(config) => {
                flat.masque_url = config.url.clone();
                flat.masque_proxy_socket_addr = config.proxy_socket_addr.map(|addr| addr.to_string());
                flat.masque_tcp_protocol = config.tcp_protocol.clone();
                flat.masque_use_http2_fallback = config.use_http2_fallback;
                flat.masque_cloudflare_geohash_enabled = config.cloudflare_geohash_enabled;
                flat.masque_auth_mode = config.auth_mode.clone();
                flat.masque_auth_token = config.auth_token.clone();
                flat.masque_client_certificate_chain_pem = config.client_certificate_chain_pem.clone();
                flat.masque_client_private_key_pem = config.client_private_key_pem.clone();
                flat.masque_cloudflare_geohash_header = config.cloudflare_geohash_header.clone();
                flat.masque_privacy_pass_provider_url = config.privacy_pass_provider_url.clone();
                flat.masque_privacy_pass_provider_auth_token = config.privacy_pass_provider_auth_token.clone();
            }
            RelayBackendConfig::ShadowTlsV3(config) => {
                flat.shadow_tls_password = config.password.clone();
                flat.shadow_tls_inner_profile_id = config.inner_profile_id.clone();
                flat.shadow_tls_inner = config.inner.clone();
            }
            RelayBackendConfig::Trojan(config) => {
                flat.trojan_password = config.password.clone();
                flat.trojan_root_certificate_pem = config.root_certificate_pem.clone();
            }
            RelayBackendConfig::AnyTls(config) => {
                flat.anytls_password = config.password.clone();
                flat.anytls_root_certificate_pem = config.root_certificate_pem.clone();
            }
            RelayBackendConfig::Shadowsocks(config) => {
                flat.shadowsocks_method = config.method.clone();
                flat.shadowsocks_password = config.password.clone();
            }
            RelayBackendConfig::Tor(config) => {
                flat.tor_state_dir = config.state_dir.clone();
                flat.tor_cache_dir = config.cache_dir.clone();
                flat.tor_bridge_lines = config.bridge_lines.clone();
                flat.tor_transports = config.transports.clone();
            }
            RelayBackendConfig::NaiveProxy(config) => {
                flat.naive_path = config.path.clone();
                flat.naive_username = config.username.clone();
                flat.naive_password = config.password.clone();
            }
            RelayBackendConfig::Unsupported(_) => {}
        }
        flat
    }
}
