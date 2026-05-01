use std::net::{Ipv4Addr, Ipv6Addr};

#[derive(Debug)]
pub(crate) struct Ipv4Cidr {
    network: u32,
    prefix_len: u8,
}

#[derive(Debug)]
pub(crate) struct Ipv6Cidr {
    network: u128,
    prefix_len: u8,
}

impl Ipv4Cidr {
    pub(crate) const fn new(a: u8, b: u8, c: u8, d: u8, prefix_len: u8) -> Self {
        let network = ((a as u32) << 24) | ((b as u32) << 16) | ((c as u32) << 8) | (d as u32);
        Self { network, prefix_len }
    }

    pub(crate) fn contains(&self, addr: Ipv4Addr) -> bool {
        let bits: u32 = addr.into();
        let mask = if self.prefix_len == 0 { 0 } else { !0u32 << (32 - self.prefix_len) };
        (bits & mask) == (self.network & mask)
    }
}

impl Ipv6Cidr {
    pub(crate) const fn new(segments: [u16; 8], prefix_len: u8) -> Self {
        let mut network: u128 = 0;
        let mut i = 0;
        while i < 8 {
            network |= (segments[i] as u128) << (112 - 16 * i);
            i += 1;
        }
        Self { network, prefix_len }
    }

    pub(crate) fn contains(&self, addr: Ipv6Addr) -> bool {
        let bits: u128 = addr.into();
        let mask = if self.prefix_len == 0 { 0 } else { !0u128 << (128 - self.prefix_len) };
        (bits & mask) == (self.network & mask)
    }
}
