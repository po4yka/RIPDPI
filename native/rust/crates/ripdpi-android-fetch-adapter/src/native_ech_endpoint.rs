use std::io;
use std::net::{IpAddr, SocketAddr};

use tokio::net::TcpStream;

use crate::dns_bootstrap::resolve_connect_targets;
use crate::socket_protection::connect_transport_with;

/// Connect to a public peer selected by an HTTPS binding.
///
/// # Cancel safety:
/// cancel-safe: dropping it closes active resolution and the connecting socket.
pub(super) async fn connect_public_transport(host: &str, port: u16, connect_timeout_ms: u64) -> io::Result<TcpStream> {
    connect_transport_with(host, port, connect_timeout_ms, public_targets(host, port)).await
}

/// # Cancel safety:
/// cancel-safe: dropping it closes the active resolution.
async fn public_targets(host: &str, port: u16) -> io::Result<Vec<SocketAddr>> {
    let mut targets = resolve_connect_targets(host, port).await?;
    targets.retain(|target| public_binding_destination(target.ip()));
    if targets.is_empty() {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "no permitted ECH binding address"));
    }
    Ok(targets)
}

fn public_binding_destination(address: IpAddr) -> bool {
    match address {
        IpAddr::V4(ip) => {
            let [a, b, c, _] = ip.octets();
            let reserved = matches!(a, 0 | 10 | 127 | 224..=255)
                || (a == 100 && (64..=127).contains(&b))
                || (a == 169 && b == 254)
                || (a == 172 && (16..=31).contains(&b))
                || (a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99)))
                || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                || (a == 203 && b == 0 && c == 113);
            !reserved
        }
        IpAddr::V6(ip) => {
            let s = ip.segments();
            // Admit translated public IPv4 peers, but never allow translation to bypass the IPv4 policy.
            let translated = ip.to_ipv4_mapped().or_else(|| match s {
                [0x64, 0xff9b, 0, 0, 0, 0, hi, lo] => Some(((u32::from(hi) << 16) | u32::from(lo)).into()),
                _ => None,
            });
            if let Some(v4) = translated {
                return public_binding_destination(IpAddr::V4(v4));
            }
            (s[0] & 0xe000) == 0x2000
                && !(s[0] == 0x2001 && (s[1] < 0x200 || s[1] == 0xdb8))
                && s[0] != 0x2002
                && !(s[0] == 0x3fff && s[1] < 0x1000)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// # Cancel safety:
    /// cancel-safe: the future owns its transport attempt and test listener.
    #[tokio::test]
    async fn alternate_tls_binding_cannot_connect_to_a_local_peer() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let address = listener.local_addr().expect("address");
        let error = connect_public_transport("127.0.0.1", address.port(), 1000)
            .await
            .expect_err("must refuse before connecting");
        assert_eq!(error.kind(), io::ErrorKind::PermissionDenied);
    }

    #[test]
    fn alternate_destination_policy_checks_embedded_ipv4() {
        for address in ["::ffff:127.0.0.1", "64:ff9b::a00:1", "100.64.0.1", "fc00::1", "2001:db8::1"] {
            assert!(!public_binding_destination(address.parse().unwrap()), "{address}");
        }
        for address in ["1.1.1.1", "2606:4700::1111", "64:ff9b::101:101"] {
            assert!(public_binding_destination(address.parse().unwrap()), "{address}");
        }
    }
}
