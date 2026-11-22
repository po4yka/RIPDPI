use std::io::{self, Write};
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use std::time::Instant;

use ring::digest::{Digest, SHA256, digest};
use ring::rand::{SecureRandom, SystemRandom};
use ripdpi_diagnostics_contracts::util::{IO_TIMEOUT, with_scan_io_deadline};
use ripdpi_diagnostics_tls::tls::open_probe_stream_targets_with_options_and_abort;
use ripdpi_diagnostics_transport::transport::{ConnectionStream, run_with_socket_abort_watchdog};
use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::adapters::tls::{
    ApplicationProtocolPolicy, ProbeStreamOptions, TlsClientProfile, TlsKeyLogCallback,
};
use crate::connectivity::adapters::transport::TransportConfig;
use crate::types::{ProbeDetail, ProbeResult, ThroughputTarget, TransferMeasurement, VerifiedUploadReceipt};

use super::target_parse::parse_http_target;
use super::throughput::{BoundedTransferStream, response_headers, transfer_deadline};
use super::transfer::{TransferReader, TransferTracker, interruption, matches_hex_digest};
use super::types::ParsedHttpTarget;

pub(in crate::connectivity) fn run_verified_upload(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
    cancel: &AtomicBool,
) -> ProbeResult {
    let deadline = transfer_deadline(Instant::now());
    let body_bytes = target.upload_body_bytes.unwrap_or_default();
    let mut details = vec![detail("expectedBodyBytes", body_bytes.to_string())];
    let exchanged = with_scan_io_deadline(Some(deadline), || {
        exchange(target, transport, key_log, verifier, cancel, deadline, &mut details)
    });
    let result = interruption(cancel, deadline).map_or(exchanged, Err);
    if let Err(reason) = result {
        details.push(detail("failureStageReadings", reason.into()));
    }
    let outcome = match result {
        Ok(()) => "verified_upload_complete",
        Err("idle_timeout" | "deadline") => "verified_upload_timeout",
        Err("early_eof") => "verified_upload_early_eof",
        Err("cancelled") => "verified_upload_inconclusive",
        Err("wrong_size") => "verified_upload_size_mismatch",
        Err("wrong_hash") => "verified_upload_hash_mismatch",
        Err(_) => "verified_upload_failed",
    };
    ProbeResult {
        probe_type: "throughput_window".into(),
        target: target.label.clone(),
        outcome: outcome.into(),
        details,
    }
}

fn exchange(
    target: &ThroughputTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
    cancel: &AtomicBool,
    deadline: Instant,
    details: &mut Vec<ProbeDetail>,
) -> Result<(), &'static str> {
    target.validate_upload()?;
    let body_bytes = target.upload_body_bytes.ok_or("missing_upload_size")?;
    if let Some(reason) = interruption(cancel, deadline) {
        return Err(reason);
    }
    let parsed = parse_http_target(&target.url, None, &[], None).map_err(|_| "invalid_target")?;
    let options = ProbeStreamOptions {
        profile: TlsClientProfile::Auto,
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        certificate_policy: verifier.into(),
        key_log,
    };
    let opened = open_probe_stream_targets_with_options_and_abort(
        &parsed.connect_targets,
        parsed.port,
        transport,
        Some(&parsed.host),
        &options,
        &|| interruption(cancel, deadline),
    )
    .map_err(|_| "stream_setup")?;
    details.extend([
        detail("transportOpenMsReadings", opened.transport_open_ms.to_string()),
        detail("tlsHandshakeMsReadings", opened.tls_handshake_ms.to_string()),
    ]);

    let mut stream = opened.stream;
    let verified = write_request(&mut stream, &parsed, body_bytes, cancel, deadline).and_then(|expected| {
        let receipt = read_receipt(&mut stream, cancel, deadline, details)?;
        details.push(detail("receivedBodyBytes", receipt.bytes.to_string()));
        if receipt.bytes != body_bytes as u64 {
            return Err("wrong_size");
        }
        if !matches_hex_digest(expected.as_ref(), &receipt.sha256) {
            return Err("wrong_hash");
        }
        Ok(())
    });
    stream.shutdown();
    verified
}

fn write_request(
    stream: &mut ConnectionStream,
    target: &ParsedHttpTarget,
    body_bytes: usize,
    cancel: &AtomicBool,
    deadline: Instant,
) -> Result<Digest, &'static str> {
    let mut body = vec![0; body_bytes];
    SystemRandom::new().fill(&mut body).map_err(|_| "payload_generation")?;
    if let Some(reason) = interruption(cancel, deadline) {
        return Err(reason);
    }
    let headers = format!(
        "POST {} HTTP/1.1\r\nHost: {}\r\nContent-Type: application/octet-stream\r\nContent-Length: {body_bytes}\r\n\
         Accept: application/json\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n",
        target.path,
        target.authority()
    );
    let socket = match stream {
        ConnectionStream::Tls(tls) => &tls.sock,
        ConnectionStream::Plain(_) => return Err("tls_required"),
    };
    socket.set_write_timeout(Some(IO_TIMEOUT)).map_err(|_| "write_setup")?;
    let socket = socket.try_clone().map_err(|_| "write_setup")?;
    run_with_socket_abort_watchdog(socket, &|| interruption(cancel, deadline).is_some(), || {
        stream.write_all(headers.as_bytes())?;
        stream.write_all(&body)?;
        stream.flush()
    })
    .map_err(|error| match error.kind() {
        io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut => "idle_timeout",
        _ => "request_write",
    })?;
    Ok(digest(&SHA256, &body))
}

fn read_receipt(
    stream: &mut ConnectionStream,
    cancel: &AtomicBool,
    deadline: Instant,
    details: &mut Vec<ProbeDetail>,
) -> Result<VerifiedUploadReceipt, &'static str> {
    let mut bounded = BoundedTransferStream::new(stream, deadline);
    let mut reader = TransferReader::new(&mut bounded, cancel, deadline, IO_TIMEOUT);
    let window = VerifiedUploadReceipt::MAX_BYTES + 1;
    reader.limit_read_ahead(window);
    let mut ignore_receipt_progress = |_: &TransferMeasurement| {};
    let mut tracker = TransferTracker::new(1, 1, &mut ignore_receipt_progress);
    let (head, response) = response_headers(&mut reader, &mut tracker)?;
    details.push(detail("httpStatusCodeReadings", response.status_code.to_string()));
    if response.status_code != 200
        || response.headers.contains_key("content-range")
        || response.headers.get("content-encoding").is_some_and(|encoding| !encoding.eq_ignore_ascii_case("identity"))
    {
        return Err("receipt_status");
    }

    let reason = reader.body(&mut tracker, &head, window)?;
    if !matches!(reason, "content_length_complete" | "chunked_complete" | "eof_complete") {
        return Err(reason);
    }
    VerifiedUploadReceipt::from_json(&tracker.body_prefix)
}

fn detail(key: &str, value: String) -> ProbeDetail {
    ProbeDetail { key: key.into(), value }
}
