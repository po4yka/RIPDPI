use std::collections::HashMap;
use std::io;

use super::{RuntimeConfig, RuntimeState};

impl RuntimeState {
    pub(super) fn validate_upstream_socks_auth(config: &RuntimeConfig) -> io::Result<()> {
        let mut policies = HashMap::new();
        for upstream in config.groups.iter().filter_map(|group| group.policy.ext_socks.as_ref()) {
            let auth = upstream.auth.as_ref();
            if policies.insert(upstream.addr, auth).is_some_and(|previous| previous != auth) {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "conflicting upstream SOCKS authentication policies",
                ));
            }
        }
        Ok(())
    }
}

#[cfg(all(test, not(feature = "loom")))]
mod tests {
    use crate::runtime::state::RuntimeState;
    use crate::runtime::{create_listener, validate_proxy_config};
    use ripdpi_proxy_runtime_adapter::model::config::{RuntimeConfig, UpstreamSocksAuth, UpstreamSocksConfig};
    use std::io;
    use std::net::{SocketAddr, TcpListener};
    use std::sync::Arc;

    fn auth(password: &str) -> Option<UpstreamSocksAuth> {
        Some(UpstreamSocksAuth { username: "session-user".into(), password: password.into() })
    }

    fn config(
        first: Option<UpstreamSocksAuth>,
        second: Option<UpstreamSocksAuth>,
        same_endpoint: bool,
    ) -> RuntimeConfig {
        let mut config = RuntimeConfig::default();
        config.network.listen.listen_port = 0;
        let first_addr = SocketAddr::from(([127, 0, 0, 1], 1081));
        let second_addr = if same_endpoint { first_addr } else { SocketAddr::from(([127, 0, 0, 1], 1082)) };
        let mut group = config.groups[0].clone();
        config.groups[0].policy.ext_socks = Some(UpstreamSocksConfig { addr: first_addr, auth: first });
        group.policy.ext_socks = Some(UpstreamSocksConfig { addr: second_addr, auth: second });
        config.groups.push(group);
        config
    }

    fn conflicting_configs() -> Vec<RuntimeConfig> {
        vec![
            config(auth("first-secret"), auth("other-secret"), true),
            config(
                auth("first-secret"),
                Some(UpstreamSocksAuth { username: "other-user".into(), password: "first-secret".into() }),
                true,
            ),
            config(None, auth("other-secret"), true),
            config(auth("first-secret"), None, true),
        ]
    }

    #[test]
    fn upstream_auth_conflicts_fail_before_listener_bind() {
        let occupied = TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0)).expect("occupy listener port");
        for mut config in conflicting_configs() {
            config.network.listen.listen_port = occupied.local_addr().expect("occupied port").port();
            let error = create_listener(&config).expect_err("conflicting listener credentials must fail");
            assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
            assert!(!error.to_string().contains("secret"));
            assert!(!error.to_string().contains("session-user"));
        }
    }

    #[test]
    fn upstream_auth_conflicts_fail_direct_state_creation() {
        for config in conflicting_configs() {
            let result = RuntimeState::new(config, None, Arc::new(ripdpi_ws_tunnel::TelegramWsTransport));
            let error = result.err().expect("conflicting state credentials must fail");
            assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        }
    }

    #[test]
    fn upstream_auth_allows_consistent_pairs_and_distinct_listeners() {
        for config in [
            config(auth("same-secret"), auth("same-secret"), true),
            config(None, None, true),
            config(auth("first-secret"), auth("other-secret"), false),
            config(None, auth("other-secret"), false),
        ] {
            validate_proxy_config(&config).expect("independent or consistent listener credentials");
        }
    }
}
