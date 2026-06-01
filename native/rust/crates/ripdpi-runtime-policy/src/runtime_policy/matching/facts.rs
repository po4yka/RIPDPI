use ripdpi_config::{QuicInitialMode, RuntimeConfig};
use ripdpi_desync::{ProtoInfo, init_proto_info};
use ripdpi_packets::classify::{ProtocolId, default_registry};
use ripdpi_packets::{is_http, parse_quic_initial};

use crate::runtime_policy::{ExtractedHost, HostSource};

#[derive(Debug, Clone)]
pub(super) struct MatchFacts {
    pub(super) host: Option<ExtractedHost>,
    pub(super) is_http: bool,
    pub(super) is_tls_client_hello: bool,
}

impl MatchFacts {
    pub(super) fn from_payload(config: &RuntimeConfig, payload: &[u8]) -> Self {
        Self {
            host: extract_host_info(config, payload),
            is_http: is_http(payload),
            is_tls_client_hello: is_tls_client_hello_payload(payload),
        }
    }
}

pub(super) fn extract_host_info(config: &RuntimeConfig, payload: &[u8]) -> Option<ExtractedHost> {
    if let Some(result) = default_registry().classify(payload) {
        let source = match result.protocol {
            ProtocolId::Http => HostSource::Http,
            _ => HostSource::Tls,
        };
        if let Some(host) = result.host {
            return Some(ExtractedHost { host, source });
        }
    }
    if let Some(host) = extract_tls_host(payload) {
        return Some(ExtractedHost { host, source: HostSource::Tls });
    }
    extract_quic_host(config, payload)
}

pub(super) fn extract_host(config: &RuntimeConfig, payload: &[u8]) -> Option<String> {
    extract_host_info(config, payload).map(|host| host.host)
}

pub(super) fn is_tls_client_hello_payload(payload: &[u8]) -> bool {
    let mut info = ProtoInfo::default();
    init_proto_info(payload, &mut info);
    info.is_tls_client_hello()
}

fn extract_quic_host(config: &RuntimeConfig, payload: &[u8]) -> Option<ExtractedHost> {
    if matches!(config.quic.initial_mode, QuicInitialMode::Disabled)
        || (!config.quic.support_v1 && !config.quic.support_v2)
    {
        return None;
    }
    let info = parse_quic_initial(payload)?;
    let allowed = (info.version == 0x0000_0001 && config.quic.support_v1)
        || (info.version == 0x6b33_43cf && config.quic.support_v2);
    allowed.then(|| ExtractedHost { host: String::from_utf8_lossy(info.host()).into_owned(), source: HostSource::Quic })
}

fn extract_tls_host(payload: &[u8]) -> Option<String> {
    let mut info = ProtoInfo::default();
    init_proto_info(payload, &mut info);
    let host = info.tls_host_bytes()?;
    Some(String::from_utf8_lossy(host).into_owned())
}
