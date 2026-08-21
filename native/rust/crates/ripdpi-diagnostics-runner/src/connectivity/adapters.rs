pub(crate) mod dns {
    pub use ripdpi_diagnostics_dns::dns::{
        build_dns_query_with_type, build_fallback_encrypted_dns_endpoints, encrypted_dns_endpoint_for_target,
        parse_dns_response, resolve_https_service_bindings_via_encrypted_dns_with_endpoint,
        resolve_via_encrypted_dns_with_raw, resolve_via_udp_with_observations,
    };
}

pub(crate) mod dns_analysis {
    pub use ripdpi_diagnostics_dns::dns_analysis::{analyze_dns_response, compare_dns_responses, parse_record_set};
}

pub(crate) mod dns_oracle {
    pub use ripdpi_diagnostics_dns::dns_oracle::{
        DnsOracleAssessment, DnsOracleConfig, DnsOracleResponse, DnsOracleTrust, evaluate_dns_oracles,
    };
}

pub(crate) mod fat_header {
    pub use ripdpi_diagnostics_fat_header::fat_header::{
        FatHeaderStatus, classify_fat_header_outcome, classify_rst_origin, classify_tcp_block_method, fat_status_label,
        run_fat_header_attempt_with_key_log,
    };
}

pub(crate) mod http {
    pub use ripdpi_diagnostics_http::http::{
        HttpObservation, classify_http_response, describe_http_observation, is_blockpage, parse_http_response,
        read_http_headers, read_http_response, try_http_request, try_http_request_targets_with_key_log,
    };
}

pub(crate) mod tls {
    pub use ripdpi_diagnostics_tls::tls::{
        ApplicationProtocolPolicy, ProbeStreamError, ProbeStreamOptions, TlsClientProfile, TlsKeyLogCallback,
        TlsObservation, classify_tls_signal, is_server_tls_version_rejection, open_probe_stream_targets_with_options,
        preferred_tls_observation, tls_key_log_callback_for_path, try_tls_handshake, try_tls_handshake_with_key_log,
    };
}

pub(crate) mod transport {
    #[cfg(test)]
    pub use ripdpi_diagnostics_transport::transport::direct_transport;
    pub use ripdpi_diagnostics_transport::transport::{
        RouteExperimentReport, TargetAddress, TransportConfig, connect_transport_observed, domain_connect_target,
        domain_connect_targets, quic_connect_target, relay_udp_direct, relay_udp_payload_observed,
        relay_udp_via_socks5, resolve_addresses, resolve_first_socket_addr, throughput_connect_targets,
    };
}

pub(crate) mod util {
    pub use ripdpi_diagnostics_contracts::util::{
        DEFAULT_DNS_SERVER, DnsAnswerOverlap, IO_TIMEOUT, MAX_HTTP_BYTES, classify_dns_answer_overlap,
        find_headers_end, format_result_set, format_socket_result, ip_set, is_suspected_dns_tampering_outcome, now_ms,
    };
}
