use crate::transport::TransportConfig;
use crate::types::InPathRoute;

pub(in crate::engine) fn authenticated_in_path_transport_matches(
    route: &InPathRoute,
    transport: &TransportConfig,
) -> bool {
    let TransportConfig::Socks5 { host, port, credentials: Some(credentials) } = transport else {
        return false;
    };
    host == &route.host
        && port == &route.port
        && credentials.username() == route.credentials.username.as_str()
        && credentials.password() == route.credentials.password.as_str()
}
