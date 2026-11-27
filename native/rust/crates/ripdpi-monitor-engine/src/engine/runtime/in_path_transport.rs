use crate::transport::TransportConfig;
use crate::types::InPathRoute;

pub(in crate::engine) fn authenticated_in_path_transport_matches(
    route: &InPathRoute,
    transport: &TransportConfig,
) -> bool {
    let (endpoint_matches, credentials) = match transport {
        TransportConfig::Socks5 { host, port, credentials: Some(credentials) } => {
            (route.unix_path.is_none() && host == &route.host && port == &route.port, credentials)
        }
        TransportConfig::Socks5Unix { path, credentials: Some(credentials) } => {
            (route.unix_path.as_ref() == Some(path), credentials)
        }
        _ => return false,
    };
    endpoint_matches
        && credentials.username() == route.credentials.username.as_str()
        && credentials.password() == route.credentials.password.as_str()
}
