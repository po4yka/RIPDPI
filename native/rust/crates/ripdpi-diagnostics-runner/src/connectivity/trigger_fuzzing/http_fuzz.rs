use std::io::Write;

use ripdpi_packets::{MH_HMIX, MH_HOSTEXTRASPACE, MH_UNIXEOL, mod_http_like_c};

use super::summary::{TriggerFuzzOutcome, append_trigger_fuzzing_summary};
use crate::connectivity::adapters::http::{classify_http_response, read_http_response};
use crate::connectivity::adapters::tls::{
    ApplicationProtocolPolicy, ProbeStreamOptions, TlsClientProfile, open_probe_stream_targets_with_options,
};
use crate::connectivity::adapters::transport::{TargetAddress, TransportConfig, domain_connect_targets};
use crate::types::{DomainTarget, ProbeDetail};

const MAX_HTTP_FUZZ_VARIANTS: usize = 3;

pub(crate) fn append_trigger_fuzzing_details(
    details: &mut Vec<ProbeDetail>,
    target: &DomainTarget,
    transport: &TransportConfig,
    baseline_status: &str,
) {
    let base_request = format!(
        "GET {} HTTP/1.1\r\nHost: {}\r\nUser-Agent: ripdpi-monitor/1\r\nAccept: */*\r\nConnection: close\r\n\r\n",
        target.http_path, target.host
    )
    .into_bytes();
    let connect_targets = domain_connect_targets(target);
    let variants = [
        ("host_case_mix", "host_header_format", MH_HMIX),
        ("host_extra_space", "host_header_format", MH_HOSTEXTRASPACE),
        ("unix_eol", "line_endings", MH_UNIXEOL),
    ];

    let mut outcomes = Vec::new();
    for (id, field, flags) in variants.into_iter().take(MAX_HTTP_FUZZ_VARIANTS) {
        let mutation = mod_http_like_c(&base_request, flags);
        if mutation.rc != 0 {
            continue;
        }

        let (outcome, detail) =
            execute_variant(&connect_targets, target.http_port.unwrap_or(80), transport, &mutation.bytes);
        outcomes.push(TriggerFuzzOutcome { id, field, outcome, detail });
    }

    append_trigger_fuzzing_summary(details, "httpFuzz", baseline_status, &outcomes);
}

fn execute_variant(
    connect_targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    request: &[u8],
) -> (String, String) {
    let options = ProbeStreamOptions {
        verify_certificates: false,
        profile: TlsClientProfile::Auto,
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        tls_verifier: None,
        key_log: None,
    };
    match open_probe_stream_targets_with_options(connect_targets, port, transport, None, &options) {
        Ok(mut stream) => {
            if let Err(err) = stream.stream.write_all(request).and_then(|_| stream.stream.flush()) {
                stream.stream.shutdown();
                return ("http_unreachable".to_string(), err.to_string());
            }

            let response = read_http_response(&mut stream.stream, crate::connectivity::adapters::util::MAX_HTTP_BYTES);
            stream.stream.shutdown();
            match response {
                Ok(response) => (classify_http_response(&response), format!("status={}", response.status_code)),
                Err(err) => ("http_unreachable".to_string(), err),
            }
        }
        Err(err) => ("http_unreachable".to_string(), err.to_string()),
    }
}
