use std::net::SocketAddr;

use ripdpi_dns_resolver::{EncryptedDnsErrorKind, EncryptedDnsExchangeSuccess};

#[derive(Debug)]
pub(in crate::io_loop) struct DnsRequest {
    pub(in crate::io_loop) src: SocketAddr,
    pub(in crate::io_loop) query: Vec<u8>,
    pub(in crate::io_loop) host: Option<String>,
    pub(in crate::io_loop) direct: Option<DirectDnsRequest>,
    pub(in crate::io_loop) tcp_reply: Option<tokio::sync::oneshot::Sender<Vec<u8>>>,
}

#[derive(Debug, Clone)]
pub(in crate::io_loop) struct DirectDnsRequest {
    pub(in crate::io_loop) generation: u64,
    pub(in crate::io_loop) candidates: Box<[std::net::IpAddr]>,
}

#[derive(Debug)]
pub(in crate::io_loop) struct DnsResponse {
    pub(in crate::io_loop) src: SocketAddr,
    pub(in crate::io_loop) query: Vec<u8>,
    pub(in crate::io_loop) host: Option<String>,
    pub(in crate::io_loop) upstream: Result<EncryptedDnsExchangeSuccess, String>,
    pub(in crate::io_loop) resolver_error_kind: Option<EncryptedDnsErrorKind>,
    pub(in crate::io_loop) resolver_endpoint_label: Option<String>,
    pub(in crate::io_loop) direct_generation: Option<u64>,
    pub(in crate::io_loop) direct_fallback: bool,
    pub(in crate::io_loop) tcp_reply: Option<tokio::sync::oneshot::Sender<Vec<u8>>>,
}
