#![forbid(unsafe_code)]

mod catalog;
mod endpoint;
mod policy;
mod protect_hooks;
mod query;
mod resolver;

pub use endpoint::encrypted_dns_label;
pub use policy::runtime_encrypted_dns_context_for_host;
pub use resolver::{
    EncryptedDnsIpAnswers, build_encrypted_dns_resolver_for_host, encrypted_dns_ip_answers_for_host,
    resolve_host_via_encrypted_dns, resolve_ws_tunnel_addr,
};
pub use ripdpi_ws_transport_port::{
    CloudflareWorkerRoute, MtprotoSeedClassification, TelegramDc, WorkerBearer, WsTransport, WsTunnelConfig,
    WsTunnelDecision, classify_target, dc_from_ip,
};

#[cfg(test)]
mod tests;
