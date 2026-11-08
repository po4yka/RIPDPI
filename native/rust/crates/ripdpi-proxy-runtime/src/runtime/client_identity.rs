use ripdpi_proxy_runtime_adapter::model::runtime_api::AttemptCorrelationId;

const APP_SCOPE_PREFIX: &str = "vpn08:";

#[derive(Clone, Debug, Default)]
pub(super) struct Socks5ClientIdentity {
    pub(super) attempt_token: Option<AttemptCorrelationId>,
    pub(super) app_scope: Option<String>,
}

impl Socks5ClientIdentity {
    pub(super) fn from_username(username: &[u8]) -> Self {
        let Ok(username) = std::str::from_utf8(username) else {
            return Self::default();
        };
        if let Some(scope) = username.strip_prefix(APP_SCOPE_PREFIX) {
            let valid = scope.len() == 36
                && scope.bytes().enumerate().all(|(index, byte)| {
                    if matches!(index, 8 | 13 | 18 | 23) {
                        byte == b'-'
                    } else {
                        byte.is_ascii_digit() || matches!(byte, b'a'..=b'f')
                    }
                });
            return Self { attempt_token: None, app_scope: valid.then(|| scope.to_owned()) };
        }
        Self { attempt_token: AttemptCorrelationId::new(username), app_scope: None }
    }
}
