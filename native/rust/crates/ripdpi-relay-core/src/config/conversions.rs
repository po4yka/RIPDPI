impl From<FlatResolvedRelayRuntimeConfig> for ResolvedRelayRuntimeConfig {
    fn from(flat: FlatResolvedRelayRuntimeConfig) -> Self {
        let common = CommonRelayConfig {
            enabled: flat.enabled,
            profile_id: flat.profile_id,
            outbound_bind_ip: flat.outbound_bind_ip,
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
            }),
            "tuic_v5" => RelayBackendConfig::TuicV5(TuicRelayConfig {
                uuid: flat.tuic_uuid,
                password: flat.tuic_password,
                zero_rtt: flat.tuic_zero_rtt,
                congestion_control: flat.tuic_congestion_control,
            }),
            "vless_reality" => RelayBackendConfig::VlessReality(VlessRealityRelayConfig {
                reality_public_key: flat.reality_public_key,
                reality_short_id: flat.reality_short_id,
                vless_transport: flat.vless_transport,
                xhttp_path: flat.xhttp_path,
                xhttp_host: flat.xhttp_host,
                uuid: flat.vless_uuid,
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
                entry_server: flat.chain_entry_server,
                entry_port: flat.chain_entry_port,
                entry_server_name: flat.chain_entry_server_name,
                entry_public_key: flat.chain_entry_public_key,
                entry_short_id: flat.chain_entry_short_id,
                entry_profile_id: flat.chain_entry_profile_id,
                entry_uuid: flat.chain_entry_uuid,
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

impl From<&ResolvedRelayRuntimeConfig> for FlatResolvedRelayRuntimeConfig {
    fn from(config: &ResolvedRelayRuntimeConfig) -> Self {
        let mut flat = Self {
            enabled: config.common.enabled,
            kind: config.kind_id().to_string(),
            profile_id: config.common.profile_id.clone(),
            outbound_bind_ip: config.common.outbound_bind_ip.clone(),
            server: config.common.server.clone(),
            server_port: config.common.server_port,
            server_name: config.common.server_name.clone(),
            reality_public_key: String::new(),
            reality_short_id: String::new(),
            vless_transport: String::new(),
            xhttp_path: String::new(),
            xhttp_host: String::new(),
            cloudflare_tunnel_mode: String::new(),
            cloudflare_publish_local_origin_url: String::new(),
            cloudflare_credentials_ref: String::new(),
            chain_entry_server: String::new(),
            chain_entry_port: 0,
            chain_entry_server_name: String::new(),
            chain_entry_public_key: String::new(),
            chain_entry_short_id: String::new(),
            chain_entry_profile_id: String::new(),
            chain_exit_server: String::new(),
            chain_exit_port: 0,
            chain_exit_server_name: String::new(),
            chain_exit_public_key: String::new(),
            chain_exit_short_id: String::new(),
            chain_exit_profile_id: String::new(),
            masque_url: String::new(),
            masque_use_http2_fallback: false,
            masque_cloudflare_geohash_enabled: false,
            tuic_zero_rtt: false,
            tuic_congestion_control: String::new(),
            shadow_tls_inner_profile_id: String::new(),
            shadow_tls_inner: None,
            naive_path: String::new(),
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
            tuic_uuid: None,
            tuic_password: None,
            shadow_tls_password: None,
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
        };
        match &config.backend {
            RelayBackendConfig::Hysteria2(config) => {
                flat.hysteria_password = config.password.clone();
                flat.hysteria_salamander_key = config.salamander_key.clone();
            }
            RelayBackendConfig::TuicV5(config) => {
                flat.tuic_uuid = config.uuid.clone();
                flat.tuic_password = config.password.clone();
                flat.tuic_zero_rtt = config.zero_rtt;
                flat.tuic_congestion_control = config.congestion_control.clone();
            }
            RelayBackendConfig::VlessReality(config) => {
                flat.reality_public_key = config.reality_public_key.clone();
                flat.reality_short_id = config.reality_short_id.clone();
                flat.vless_transport = config.vless_transport.clone();
                flat.xhttp_path = config.xhttp_path.clone();
                flat.xhttp_host = config.xhttp_host.clone();
                flat.vless_uuid = config.uuid.clone();
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
                flat.chain_entry_server = config.entry_server.clone();
                flat.chain_entry_port = config.entry_port;
                flat.chain_entry_server_name = config.entry_server_name.clone();
                flat.chain_entry_public_key = config.entry_public_key.clone();
                flat.chain_entry_short_id = config.entry_short_id.clone();
                flat.chain_entry_profile_id = config.entry_profile_id.clone();
                flat.chain_entry_uuid = config.entry_uuid.clone();
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
