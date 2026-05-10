use super::super::config::{runtime_buffer_size, should_cache_udp_host, RuntimeConfig};

pub struct OutboundPayloadInfo {
    pub host: Option<String>,
    pub is_tls: bool,
}

#[derive(Clone)]
pub struct FirstOutboundPayloadPolicy {
    pub buffer_size: usize,
    config: RuntimeConfig,
}

pub struct UdpPayloadInfo {
    pub host: Option<String>,
    pub cache_host: bool,
}

#[derive(Clone)]
pub struct PayloadHostExtractor {
    config: RuntimeConfig,
}

pub fn payload_host_extractor(config: &RuntimeConfig) -> PayloadHostExtractor {
    PayloadHostExtractor { config: config.clone() }
}

#[derive(Clone)]
pub struct UdpPayloadClassifier {
    config: RuntimeConfig,
}

pub fn udp_payload_classifier(config: &RuntimeConfig) -> UdpPayloadClassifier {
    UdpPayloadClassifier { config: config.clone() }
}

pub fn first_outbound_payload_policy(config: &RuntimeConfig) -> FirstOutboundPayloadPolicy {
    FirstOutboundPayloadPolicy { buffer_size: runtime_buffer_size(config), config: config.clone() }
}

pub fn classify_first_outbound_payload(policy: &FirstOutboundPayloadPolicy, payload: &[u8]) -> OutboundPayloadInfo {
    classify_outbound_payload(&policy.config, payload)
}

pub fn classify_outbound_payload(config: &RuntimeConfig, payload: &[u8]) -> OutboundPayloadInfo {
    OutboundPayloadInfo {
        host: extract_payload_host(config, payload),
        is_tls: ripdpi_runtime_decision_ports::is_tls_client_hello_payload(payload),
    }
}

pub fn extract_payload_host(config: &RuntimeConfig, payload: &[u8]) -> Option<String> {
    ripdpi_runtime_decision_ports::extract_host(config, payload)
}

pub fn extract_payload_host_with(extractor: &PayloadHostExtractor, payload: &[u8]) -> Option<String> {
    extract_payload_host(&extractor.config, payload)
}

pub fn is_tls_client_hello_payload(payload: &[u8]) -> bool {
    ripdpi_runtime_decision_ports::is_tls_client_hello_payload(payload)
}

pub fn classify_udp_payload(config: &RuntimeConfig, payload: &[u8]) -> UdpPayloadInfo {
    let host_info = ripdpi_runtime_decision_ports::extract_host_info(config, payload);
    UdpPayloadInfo {
        host: host_info.as_ref().map(|value| value.host.clone()),
        cache_host: should_cache_udp_host(config, host_info.as_ref()),
    }
}

pub fn classify_udp_payload_with(classifier: &UdpPayloadClassifier, payload: &[u8]) -> UdpPayloadInfo {
    classify_udp_payload(&classifier.config, payload)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_outbound_payload_policy_applies_runtime_buffer_floor() {
        let mut config = RuntimeConfig::default();
        config.network.buffer_size = 512;
        let policy = first_outbound_payload_policy(&config);

        assert_eq!(policy.buffer_size, 16_384);
        let info = classify_first_outbound_payload(&policy, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n");
        assert_eq!(info.host.as_deref(), Some("example.com"));
        assert!(!info.is_tls);
    }

    #[test]
    fn payload_host_extractor_preserves_host_parsing() {
        let config = RuntimeConfig::default();
        let extractor = payload_host_extractor(&config);

        let host = extract_payload_host_with(&extractor, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n");

        assert_eq!(host.as_deref(), Some("example.com"));
    }

    #[test]
    fn udp_payload_classifier_preserves_host_cache_policy() {
        let mut config = RuntimeConfig::default();
        config.quic.initial_mode = super::super::super::config::QuicInitialMode::RouteAndCache;
        let classifier = udp_payload_classifier(&config);

        let info = classify_udp_payload_with(&classifier, b"\xc3\x00\x00\x01\x08\x00\x00\x00\x00\x00");

        assert!(info.host.is_none());
        assert!(!info.cache_host);
    }
}
