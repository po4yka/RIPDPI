use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

use smoltcp::wire::{IpVersion, Ipv4Packet, Ipv6Packet};

#[derive(Clone)]
struct Network {
    address: IpAddr,
    mask: IpAddr,
}

/// Peer permissions, independent of Android's traffic selection routes.
pub(crate) struct AllowedIps(Vec<Network>);

impl Default for AllowedIps {
    fn default() -> Self {
        Self(vec![
            Network { address: Ipv4Addr::UNSPECIFIED.into(), mask: Ipv4Addr::UNSPECIFIED.into() },
            Network { address: Ipv6Addr::UNSPECIFIED.into(), mask: Ipv6Addr::UNSPECIFIED.into() },
        ])
    }
}

impl AllowedIps {
    pub(crate) fn parse(values: Option<&[String]>) -> io::Result<Self> {
        // Older native callers did not supply peer policy. Explicit empty policy fails closed.
        let Some(values) = values else { return Ok(Self::default()) };
        if values.is_empty() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "WireGuard AllowedIPs must not be empty"));
        }
        values
            .iter()
            .map(|value| {
                let cidr = ripdpi_packets::ip::parse_cidr(value, None)
                    .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid WireGuard AllowedIPs CIDR"))?;
                Ok(Network { address: cidr.network(), mask: cidr.netmask() })
            })
            .collect::<io::Result<Vec<_>>>()
            .map(Self)
    }

    pub(crate) fn allows(&self, address: IpAddr) -> bool {
        self.0.iter().any(|network| match (address, network.address, network.mask) {
            (IpAddr::V4(address), IpAddr::V4(base), IpAddr::V4(mask)) => {
                u32::from(address) & u32::from(mask) == u32::from(base)
            }
            (IpAddr::V6(address), IpAddr::V6(base), IpAddr::V6(mask)) => {
                u128::from(address) & u128::from(mask) == u128::from(base)
            }
            _ => false,
        })
    }

    pub(crate) fn allows_source(&self, packet: &[u8]) -> bool {
        packet_addresses(packet).is_some_and(|(source, _)| self.allows(source))
    }

    pub(crate) fn allows_destination(&self, packet: &[u8]) -> bool {
        packet_addresses(packet).is_some_and(|(_, destination)| self.allows(destination))
    }
}

fn packet_addresses(packet: &[u8]) -> Option<(IpAddr, IpAddr)> {
    match IpVersion::of_packet(packet).ok()? {
        IpVersion::Ipv4 => {
            let packet = Ipv4Packet::new_checked(packet).ok()?;
            Some((packet.src_addr().into(), packet.dst_addr().into()))
        }
        IpVersion::Ipv6 => {
            let packet = Ipv6Packet::new_checked(packet).ok()?;
            Some((packet.src_addr().into(), packet.dst_addr().into()))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn policy(values: &[&str]) -> AllowedIps {
        AllowedIps::parse(Some(&values.iter().map(|value| (*value).to_owned()).collect::<Vec<_>>())).unwrap()
    }

    #[test]
    fn subnet_membership_checks_boundaries_and_families() {
        let allowed = policy(&["192.0.2.129/25", "2001:db8:1234:5678::abcd/64"]);
        for value in ["192.0.2.128", "192.0.2.255", "2001:db8:1234:5678::", "2001:db8:1234:5678:ffff:ffff:ffff:ffff"] {
            assert!(allowed.allows(value.parse().unwrap()), "{value}");
        }
        for value in ["192.0.2.127", "192.0.3.0", "2001:db8:1234:5677::1", "::ffff:192.0.2.129"] {
            assert!(!allowed.allows(value.parse().unwrap()), "{value}");
        }
    }

    #[test]
    fn explicit_empty_or_malformed_policy_is_not_a_default_route() {
        assert!(AllowedIps::parse(Some(&[])).is_err());
        for value in ["example.invalid/24", "192.0.2.0/33", "::/129", "192.0.2.1", "192.0.2.1/024"] {
            assert!(AllowedIps::parse(Some(&[value.into()])).is_err(), "{value}");
        }
        let legacy = AllowedIps::parse(None).unwrap();
        assert!(legacy.allows("192.0.2.1".parse().unwrap()));
        assert!(legacy.allows("2001:db8::1".parse().unwrap()));
    }

    #[test]
    fn single_address_and_default_routes_keep_their_exact_scope() {
        let host = policy(&["192.0.2.1/32", "2001:db8::1/128"]);
        assert!(host.allows("192.0.2.1".parse().unwrap()));
        assert!(!host.allows("192.0.2.2".parse().unwrap()));
        assert!(host.allows("2001:db8::1".parse().unwrap()));
        assert!(!host.allows("2001:db8::2".parse().unwrap()));
        let ipv4 = policy(&["0.0.0.0/0"]);
        assert!(ipv4.allows("255.255.255.255".parse().unwrap()));
        assert!(!ipv4.allows("::1".parse().unwrap()));
    }

    #[test]
    fn packet_source_and_destination_have_separate_permissions() {
        let allowed = policy(&["192.0.2.0/24"]);
        let mut packet = [0x45, 0, 0, 20, 0, 0, 0, 0, 64, 17, 0, 0, 198, 51, 100, 1, 192, 0, 2, 1];
        assert!(!allowed.allows_source(&packet));
        assert!(allowed.allows_destination(&packet));
        packet[12..16].copy_from_slice(&[192, 0, 2, 1]);
        packet[16..20].copy_from_slice(&[198, 51, 100, 1]);
        assert!(allowed.allows_source(&packet));
        assert!(!allowed.allows_destination(&packet));
        assert!(!allowed.allows_source(&packet[..10]));
    }
}
