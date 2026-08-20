use std::io::{ErrorKind, Read, Write};

use ripdpi_packets::{
    DEFAULT_FAKE_TLS, change_tls_sni_seeded_like_c, is_tls_server_hello, padencap_tls_like_c,
    remove_tls_key_share_group_like_c,
};

use super::summary::{TriggerFuzzOutcome, append_trigger_fuzzing_summary};
use crate::connectivity::adapters::transport::{
    TargetAddress, TransportConfig, connect_transport_observed, domain_connect_targets,
};
use crate::connectivity::adapters::util::IO_TIMEOUT;
use crate::types::{DomainTarget, ProbeDetail};

const MAX_TLS_FUZZ_VARIANTS: usize = 3;

pub(crate) fn append_trigger_fuzzing_details(
    details: &mut Vec<ProbeDetail>,
    target: &DomainTarget,
    transport: &TransportConfig,
    baseline_status: &str,
) {
    let Some(base_client_hello) = build_fake_client_hello(&target.host) else {
        return;
    };
    let connect_targets = domain_connect_targets(target);
    let variants = [
        (
            "uppercase_sni",
            "sni_name",
            change_tls_sni_seeded_like_c(
                &base_client_hello,
                target.host.to_ascii_uppercase().as_bytes(),
                base_client_hello.len() + 32,
                11,
            ),
        ),
        ("drop_x25519", "key_share", remove_tls_key_share_group_like_c(&base_client_hello, 0x001d)),
        ("expand_padding", "padding_extension", padencap_tls_like_c(&base_client_hello, 24)),
    ];

    let mut outcomes = Vec::new();
    for (id, field, mutation) in variants.into_iter().take(MAX_TLS_FUZZ_VARIANTS) {
        if mutation.rc != 0 {
            continue;
        }

        let (outcome, detail) =
            execute_variant(&connect_targets, target.https_port.unwrap_or(443), transport, &mutation.bytes);
        outcomes.push(TriggerFuzzOutcome { id, field, outcome, detail });
    }

    append_trigger_fuzzing_summary(details, "tlsFuzz", baseline_status, &outcomes);
}

fn build_fake_client_hello(host: &str) -> Option<Vec<u8>> {
    let capacity = DEFAULT_FAKE_TLS.len() + host.len() + 32;
    let mutation = change_tls_sni_seeded_like_c(DEFAULT_FAKE_TLS, host.as_bytes(), capacity, 7);
    if mutation.rc == 0 { Some(mutation.bytes) } else { Some(DEFAULT_FAKE_TLS.to_vec()) }
}

fn execute_variant(
    connect_targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    client_hello: &[u8],
) -> (String, String) {
    match connect_transport_observed(connect_targets, port, transport) {
        Ok(result) => {
            let mut stream = result.stream;
            let _ = stream.set_read_timeout(Some(IO_TIMEOUT));
            let _ = stream.set_write_timeout(Some(IO_TIMEOUT));
            if let Err(err) = stream.write_all(client_hello).and_then(|_| stream.flush()) {
                let _ = stream.shutdown(std::net::Shutdown::Both);
                return ("tls_write_failed".to_string(), err.to_string());
            }

            let mut buf = [0u8; 2048];
            let outcome = match stream.read(&mut buf) {
                Ok(0) => ("tls_close".to_string(), "eof".to_string()),
                Ok(size) => classify_tls_first_response(&buf[..size]),
                Err(err) if matches!(err.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                    ("tls_timeout".to_string(), err.to_string())
                }
                Err(err) => ("tls_error".to_string(), err.to_string()),
            };
            let _ = stream.shutdown(std::net::Shutdown::Both);
            outcome
        }
        Err(err) => ("tcp_connect_failed".to_string(), err.to_string()),
    }
}

fn classify_tls_first_response(response: &[u8]) -> (String, String) {
    if is_tls_server_hello(response) {
        return ("tls_server_hello".to_string(), "server_hello".to_string());
    }
    if response.len() >= 7 && response[0] == 0x15 {
        let alert = tls_alert_description(response[6]);
        return (format!("tls_alert_{alert}"), format!("alert={alert}"));
    }

    ("tls_response_other".to_string(), format!("bytes={}", response.len()))
}

fn tls_alert_description(code: u8) -> &'static str {
    match code {
        0 => "close_notify",
        10 => "unexpected_message",
        20 => "bad_record_mac",
        40 => "handshake_failure",
        42 => "bad_certificate",
        47 => "illegal_parameter",
        48 => "unknown_ca",
        70 => "protocol_version",
        80 => "internal_error",
        112 => "unrecognized_name",
        _ => "other",
    }
}
