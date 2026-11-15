use super::tcp_attention_confidence;
use crate::connectivity::adapters::fat_header::FatHeaderStatus;

#[test]
fn tcp_attention_confidence_does_not_infer_an_origin() {
    assert_eq!(tcp_attention_confidence(&FatHeaderStatus::Reset), "low");
    assert_eq!(tcp_attention_confidence(&FatHeaderStatus::Inconclusive), "none");
    assert_eq!(tcp_attention_confidence(&FatHeaderStatus::Success), "none");
}

fn tcp_target(port: u16, sni: Option<&str>) -> crate::types::TcpTarget {
    crate::types::TcpTarget {
        id: "local-transfer".into(),
        provider: "Local transfer".into(),
        ip: "127.0.0.1".into(),
        port,
        sni: sni.map(str::to_string),
        host_header: None,
        asn: None,
        fat_header_requests: Some(3),
        alt_port: None,
    }
}

#[test]
fn evidence_whitelist_rejects_http_errors_and_short_success() {
    use crate::test_fixtures::{FatServerMode, TlsHttpServer, TlsMode};
    for response in [
        &b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"[..],
        &b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"[..],
    ] {
        let server =
            TlsHttpServer::start(TlsMode::Single("original.example".into()), FatServerMode::HttpResponse(response));
        let result = super::run_tcp_probe(
            &tcp_target(server.port(), Some("original.example")),
            &["allowed.example".into()],
            &super::TransportConfig::Direct { route_experiment: None },
            None,
        );
        assert_eq!(result.outcome, "whitelist_sni_failed", "{result:?}");
        assert!(result.details.iter().any(|d| d.key == "responsesSeen" && d.value == "1"), "{result:?}");
    }
}

#[test]
fn evidence_whitelist_accepts_completed_transfer() {
    use crate::test_fixtures::{FatServerMode, TlsHttpServer, TlsMode};
    let server = TlsHttpServer::start(
        TlsMode::Single("original.example".into()),
        FatServerMode::AllowHost("allowed.example".into()),
    );
    let result = super::run_tcp_probe(
        &tcp_target(server.port(), Some("original.example")),
        &["allowed.example".into()],
        &super::TransportConfig::Direct { route_experiment: None },
        None,
    );
    assert_eq!(result.outcome, "whitelist_sni_ok", "{result:?}");
    assert!(result.details.iter().any(|d| d.key == "selectedSni" && d.value == "allowed.example"));
    assert!(result.details.iter().any(|d| d.key == "responsesSeen" && d.value == "3"));
}

#[test]
fn evidence_clean_close_keeps_bytes_without_a_cutoff_claim() {
    use crate::test_fixtures::{FatServerMode, PlainFatHeaderServer};
    let server = PlainFatHeaderServer::start(FatServerMode::CutoffAtThreshold);
    let result = super::run_tcp_probe(
        &tcp_target(server.port(), None),
        &[],
        &super::TransportConfig::Direct { route_experiment: None },
        None,
    );
    let tcp = ripdpi_diagnostics_classification::observations::observation_for_probe(&result).unwrap().tcp.unwrap();
    assert!(tcp.bytes_sent.unwrap() >= 16 * 1024);
    assert_eq!(tcp.responses_seen, Some(1));
    assert_eq!(tcp.observed_window_size, None);
    assert_eq!(tcp.rst_origin, None);
    assert_eq!(tcp.tcp_block_method, None);
    assert_eq!(tcp.rst_timing_ms, None);
    assert_eq!(result.outcome, "inconclusive");
    assert!(result.details.iter().any(|d| d.key == "failureStage" && d.value == "response_read"));
}

#[test]
fn connection_refusal_with_tls_selected_retries_before_handshake() {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);
    let result = super::run_tcp_probe(
        &tcp_target(port, Some("control.test")),
        &[],
        &super::TransportConfig::Direct { route_experiment: None },
        None,
    );

    assert_eq!(result.outcome, "tcp_connect_failed", "{result:?}");
    assert!(result.details.iter().any(|detail| detail.key == "failureStage" && detail.value == "tcp_connect"));
    assert!(result.details.iter().any(|detail| detail.key == "probeRetryCount" && detail.value == "1"));
}

#[test]
fn http_refusal_does_not_retry_connect() {
    use crate::test_fixtures::{FatServerMode, PlainFatHeaderServer};
    let server = PlainFatHeaderServer::start(FatServerMode::HttpResponse(
        b"HTTP/1.1 431 Request Header Fields Too Large\r\nConnection: close\r\n\r\n",
    ));
    let result = super::run_tcp_probe(
        &tcp_target(server.port(), None),
        &[],
        &super::TransportConfig::Direct { route_experiment: None },
        None,
    );

    assert_eq!(result.outcome, "inconclusive");
    assert!(result.details.iter().any(|detail| detail.key == "failureStage" && detail.value == "http_response"));
    assert!(result.details.iter().any(|detail| detail.key == "probeRetryCount" && detail.value == "0"));
    assert!(result.details.iter().any(|detail| detail.key == "responsesSeen" && detail.value == "1"));
}
