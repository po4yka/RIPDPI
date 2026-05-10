use ripdpi_config::RuntimeConfig;

use super::protect_path_owned;

pub fn transparent_proxy_enabled(config: &RuntimeConfig) -> bool {
    config.network.transparent
}

pub fn http_connect_enabled(config: &RuntimeConfig) -> bool {
    config.network.http_connect
}

pub fn shadowsocks_enabled(config: &RuntimeConfig) -> bool {
    config.network.shadowsocks
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ProxyProtocolMode {
    Transparent,
    HttpConnect,
    BytePrefixed { shadowsocks_enabled: bool },
}

pub fn proxy_protocol_mode(config: &RuntimeConfig) -> ProxyProtocolMode {
    if transparent_proxy_enabled(config) {
        ProxyProtocolMode::Transparent
    } else if http_connect_enabled(config) {
        ProxyProtocolMode::HttpConnect
    } else {
        ProxyProtocolMode::BytePrefixed { shadowsocks_enabled: shadowsocks_enabled(config) }
    }
}

pub fn udp_associate_enabled(config: &RuntimeConfig) -> bool {
    config.network.udp
}

pub fn ipv6_enabled(config: &RuntimeConfig) -> bool {
    config.network.ipv6
}

pub fn name_resolution_enabled(config: &RuntimeConfig) -> bool {
    config.network.resolve
}

pub fn proxy_auth_token(config: &RuntimeConfig) -> Option<&str> {
    config.network.listen.auth_token.as_deref()
}

pub fn proxy_session_config(config: &RuntimeConfig) -> ripdpi_session::SessionConfig {
    ripdpi_session::SessionConfig { resolve: config.network.resolve, ipv6: config.network.ipv6 }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ShadowsocksTargetPolicy {
    pub ipv6_enabled: bool,
    pub resolve_enabled: bool,
}

pub fn shadowsocks_target_policy(config: &RuntimeConfig) -> ShadowsocksTargetPolicy {
    ShadowsocksTargetPolicy { ipv6_enabled: config.network.ipv6, resolve_enabled: config.network.resolve }
}

#[derive(Clone)]
pub struct ProxyHandshakeSettings {
    pub protocol_mode: ProxyProtocolMode,
    pub auth_token: Option<String>,
    pub session_config: ripdpi_session::SessionConfig,
    pub shadowsocks_target_policy: ShadowsocksTargetPolicy,
    pub udp_associate_enabled: bool,
    pub protect_path: Option<String>,
}

pub fn proxy_handshake_settings(config: &RuntimeConfig) -> ProxyHandshakeSettings {
    ProxyHandshakeSettings {
        protocol_mode: proxy_protocol_mode(config),
        auth_token: proxy_auth_token(config).map(ToOwned::to_owned),
        session_config: proxy_session_config(config),
        shadowsocks_target_policy: shadowsocks_target_policy(config),
        udp_associate_enabled: udp_associate_enabled(config),
        protect_path: protect_path_owned(config),
    }
}

#[cfg(test)]
mod tests {
    use ripdpi_config::RuntimeConfig;

    use super::*;

    #[test]
    fn proxy_protocol_mode_prefers_listener_level_modes_before_byte_prefixed_protocols() {
        let mut config = RuntimeConfig::default();
        config.network.shadowsocks = true;
        assert_eq!(proxy_protocol_mode(&config), ProxyProtocolMode::BytePrefixed { shadowsocks_enabled: true });

        config.network.http_connect = true;
        assert_eq!(proxy_protocol_mode(&config), ProxyProtocolMode::HttpConnect);

        config.network.transparent = true;
        assert_eq!(proxy_protocol_mode(&config), ProxyProtocolMode::Transparent);
    }

    #[test]
    fn proxy_handshake_settings_project_protocol_session_udp_and_protect_policy() {
        let mut config = RuntimeConfig::default();
        config.network.shadowsocks = true;
        config.network.udp = true;
        config.network.resolve = false;
        config.network.ipv6 = true;
        config.network.listen.auth_token = Some("secret".to_string());
        config.process.protect_path = Some("/tmp/protect.sock".to_string());

        let settings = proxy_handshake_settings(&config);

        assert_eq!(settings.protocol_mode, ProxyProtocolMode::BytePrefixed { shadowsocks_enabled: true },);
        assert_eq!(settings.auth_token.as_deref(), Some("secret"));
        assert!(!settings.session_config.resolve);
        assert!(settings.session_config.ipv6);
        assert_eq!(
            settings.shadowsocks_target_policy,
            ShadowsocksTargetPolicy { ipv6_enabled: true, resolve_enabled: false },
        );
        assert!(settings.udp_associate_enabled);
        assert_eq!(settings.protect_path.as_deref(), Some("/tmp/protect.sock"));
    }
}
