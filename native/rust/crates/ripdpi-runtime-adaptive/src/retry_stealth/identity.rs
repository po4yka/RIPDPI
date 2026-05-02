use crate::retry_stealth::hash::{stable_hash_update, FNV_OFFSET};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum RetryLane {
    TcpTls,
    TcpOther,
    UdpQuic,
    UdpOther,
}

impl RetryLane {
    fn as_str(self) -> &'static str {
        match self {
            Self::TcpTls => "tcp_tls",
            Self::TcpOther => "tcp_other",
            Self::UdpQuic => "udp_quic",
            Self::UdpOther => "udp_other",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct RetrySignature {
    network_scope_key: String,
    lane: RetryLane,
    target_key: String,
    group_index: usize,
    adaptive_hash: u64,
}

impl RetrySignature {
    pub fn new(
        network_scope_key: impl Into<String>,
        lane: RetryLane,
        target_key: impl Into<String>,
        group_index: usize,
        adaptive_hash: u64,
    ) -> Self {
        Self {
            network_scope_key: network_scope_key.into(),
            lane,
            target_key: target_key.into(),
            group_index,
            adaptive_hash,
        }
    }

    pub fn hash(&self) -> u64 {
        let mut hash = FNV_OFFSET;
        stable_hash_update(&mut hash, self.network_scope_key.as_bytes());
        stable_hash_update(&mut hash, b"|");
        stable_hash_update(&mut hash, self.lane.as_str().as_bytes());
        stable_hash_update(&mut hash, b"|");
        stable_hash_update(&mut hash, self.target_key.as_bytes());
        stable_hash_update(&mut hash, b"|");
        stable_hash_update(&mut hash, self.group_index.to_string().as_bytes());
        stable_hash_update(&mut hash, b"|");
        stable_hash_update(&mut hash, self.adaptive_hash.to_string().as_bytes());
        hash
    }

    pub fn family_hash(&self) -> u64 {
        let mut hash = FNV_OFFSET;
        stable_hash_update(&mut hash, self.network_scope_key.as_bytes());
        stable_hash_update(&mut hash, b"|");
        stable_hash_update(&mut hash, self.lane.as_str().as_bytes());
        stable_hash_update(&mut hash, b"|");
        stable_hash_update(&mut hash, self.target_key.as_bytes());
        stable_hash_update(&mut hash, b"|");
        stable_hash_update(&mut hash, self.group_index.to_string().as_bytes());
        hash
    }
}
