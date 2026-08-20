use std::io::{ErrorKind, Read, Write};
use std::net::IpAddr;
use std::sync::Arc;
use std::time::Duration;

use crate::http::{extract_host_from_url, extract_path_from_url, read_http_headers};
use crate::tls::{
    ApplicationProtocolPolicy, NoCertificateVerification, ProbeStreamOptions, TlsClientProfile, TlsKeyLogCallback,
    open_probe_stream_targets_with_options,
};
use crate::transport::{TargetAddress, TransportConfig};
use crate::types::TelegramTarget;
use crate::util::{
    MAX_HTTP_BYTES, TELEGRAM_CHUNK_SIZE, TELEGRAM_DOWNLOAD_EXPECTED_BYTES, TELEGRAM_SPEED_SAMPLE_INTERVAL,
    find_headers_end,
};

const TELEGRAM_UPLOAD_HOST: &str = "telegram.org";

pub(crate) struct TelegramTransferResult {
    pub(crate) status: String,
    pub(crate) avg_bps: u64,
    pub(crate) peak_bps: u64,
    pub(crate) bytes_total: usize,
    pub(crate) duration_ms: u64,
    pub(crate) error: Option<String>,
}

impl TelegramTransferResult {
    pub(crate) fn blocked(error: String) -> Self {
        Self {
            status: "blocked".to_string(),
            avg_bps: 0,
            peak_bps: 0,
            bytes_total: 0,
            duration_ms: 0,
            error: Some(error),
        }
    }

    fn from_transfer(
        status: &str,
        bytes_total: usize,
        peak_bps: u64,
        start: std::time::Instant,
        error: Option<String>,
    ) -> Self {
        let duration_ms = start.elapsed().as_millis().max(1) as u64;
        let avg_bps = (bytes_total as u64).saturating_mul(1000) / duration_ms;
        Self { status: status.to_string(), avg_bps, peak_bps, bytes_total, duration_ms, error }
    }
}

fn update_peak_bps(peak_bps: &mut u64, sample_bytes: usize, sample_ms: u64) {
    if let Some(sample_bps) = (sample_bytes as u64).saturating_mul(1000).checked_div(sample_ms) {
        *peak_bps = (*peak_bps).max(sample_bps);
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum TelegramTlsVerifierMode {
    DefaultRoots,
    DomainFrontedNoVerify,
}

impl TelegramTlsVerifierMode {
    fn verify_certificates(self) -> bool {
        matches!(self, Self::DefaultRoots)
    }

    fn custom_verifier(self) -> Option<Arc<dyn rustls::client::danger::ServerCertVerifier>> {
        match self {
            Self::DefaultRoots => None,
            Self::DomainFrontedNoVerify => Some(Arc::new(NoCertificateVerification)),
        }
    }
}

fn verifier_mode_for_target(target: &TargetAddress, tls_name: &str) -> TelegramTlsVerifierMode {
    match target {
        TargetAddress::Ip(_) if !tls_name.is_empty() => TelegramTlsVerifierMode::DomainFrontedNoVerify,
        TargetAddress::Ip(_) | TargetAddress::Host(_) => TelegramTlsVerifierMode::DefaultRoots,
    }
}

fn upload_target_address(upload_target: &str) -> TargetAddress {
    upload_target.parse::<IpAddr>().map_or_else(|_| TargetAddress::Host(upload_target.to_string()), TargetAddress::Ip)
}

fn upload_tls_host(target: &TargetAddress) -> String {
    match target {
        TargetAddress::Host(host) => host.clone(),
        TargetAddress::Ip(_) => TELEGRAM_UPLOAD_HOST.to_string(),
    }
}

pub(crate) fn telegram_download_probe(
    target: &TelegramTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
) -> TelegramTransferResult {
    let Some(host) = extract_host_from_url(&target.media_url) else {
        return TelegramTransferResult::blocked("invalid media_url".to_string());
    };
    let path = extract_path_from_url(&target.media_url);

    let target_address = TargetAddress::Host(host.clone());
    let verifier_mode = verifier_mode_for_target(&target_address, &host);
    let tls_verifier = verifier_mode.custom_verifier();
    let options = ProbeStreamOptions {
        verify_certificates: verifier_mode.verify_certificates(),
        profile: TlsClientProfile::Auto,
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        tls_verifier: tls_verifier.as_ref(),
        key_log,
    };
    let stream_result = open_probe_stream_targets_with_options(
        std::slice::from_ref(&target_address),
        443,
        transport,
        Some(&host),
        &options,
    );
    let mut stream = match stream_result {
        Ok(result) => result.stream,
        Err(err) => return TelegramTransferResult::blocked(err.to_string()),
    };

    let request = format!("GET {path} HTTP/1.1\r\nHost: {host}\r\nAccept: */*\r\nConnection: close\r\n\r\n");
    if let Err(err) = stream.write_all(request.as_bytes()).and_then(|_| stream.flush()) {
        stream.shutdown();
        return TelegramTransferResult::blocked(err.to_string());
    }

    let header_buf = match read_http_headers(&mut stream, MAX_HTTP_BYTES) {
        Ok(h) => h,
        Err(err) => {
            stream.shutdown();
            return TelegramTransferResult::blocked(err);
        }
    };
    let Some(header_end) = find_headers_end(&header_buf) else {
        stream.shutdown();
        return TelegramTransferResult::blocked("response_missing_headers".to_string());
    };
    let body_prefix_len = header_buf.len() - (header_end + 4);

    let stall_timeout = Duration::from_millis(target.stall_timeout_ms);
    let total_timeout = Duration::from_millis(target.total_timeout_ms);
    let start = std::time::Instant::now();
    let mut last_data_at = start;
    let mut bytes_total = body_prefix_len;
    let mut peak_bps = 0u64;
    let mut sample_bytes = 0usize;
    let mut sample_start = start;
    let mut buf = [0u8; TELEGRAM_CHUNK_SIZE];

    loop {
        if start.elapsed() > total_timeout {
            break;
        }
        if last_data_at.elapsed() > stall_timeout {
            stream.shutdown();
            return TelegramTransferResult::from_transfer(
                "stalled",
                bytes_total,
                peak_bps,
                start,
                Some("stall detected".to_string()),
            );
        }

        match stream.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                bytes_total += n;
                sample_bytes += n;
                last_data_at = std::time::Instant::now();

                if sample_start.elapsed() >= TELEGRAM_SPEED_SAMPLE_INTERVAL {
                    let sample_ms = sample_start.elapsed().as_millis() as u64;
                    update_peak_bps(&mut peak_bps, sample_bytes, sample_ms);
                    sample_bytes = 0;
                    sample_start = std::time::Instant::now();
                }
            }
            Err(ref err) if err.kind() == ErrorKind::TimedOut || err.kind() == ErrorKind::WouldBlock => {
                continue;
            }
            Err(err) => {
                stream.shutdown();
                let status = if bytes_total == 0 { "blocked" } else { "stalled" };
                return TelegramTransferResult::from_transfer(
                    status,
                    bytes_total,
                    peak_bps,
                    start,
                    Some(err.to_string()),
                );
            }
        }
    }

    stream.shutdown();
    let status = if bytes_total >= TELEGRAM_DOWNLOAD_EXPECTED_BYTES * 98 / 100 {
        "ok"
    } else if bytes_total > 0 {
        "slow"
    } else {
        "blocked"
    };
    TelegramTransferResult::from_transfer(status, bytes_total, peak_bps, start, None)
}

pub(crate) fn telegram_upload_probe(
    target: &TelegramTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
) -> TelegramTransferResult {
    let target_address = upload_target_address(&target.upload_ip);
    let upload_host = upload_tls_host(&target_address);
    let verifier_mode = verifier_mode_for_target(&target_address, &upload_host);
    let tls_verifier = verifier_mode.custom_verifier();
    let options = ProbeStreamOptions {
        verify_certificates: verifier_mode.verify_certificates(),
        profile: TlsClientProfile::Auto,
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        tls_verifier: tls_verifier.as_ref(),
        key_log,
    };
    let stream_result = open_probe_stream_targets_with_options(
        std::slice::from_ref(&target_address),
        target.upload_port,
        transport,
        Some(&upload_host),
        &options,
    );
    let mut stream = match stream_result {
        Ok(result) => result.stream,
        Err(err) => return TelegramTransferResult::blocked(err.to_string()),
    };

    let content_length = target.upload_size_bytes;
    let header = format!(
        "POST /upload HTTP/1.1\r\nHost: {upload_host}\r\nContent-Length: {content_length}\r\n\
         Content-Type: application/octet-stream\r\nConnection: close\r\n\r\n"
    );
    if let Err(err) = stream.write_all(header.as_bytes()).and_then(|_| stream.flush()) {
        stream.shutdown();
        return TelegramTransferResult::blocked(err.to_string());
    }

    let stall_timeout = Duration::from_millis(target.stall_timeout_ms);
    let total_timeout = Duration::from_millis(target.total_timeout_ms);
    let start = std::time::Instant::now();
    let chunk = [0u8; TELEGRAM_CHUNK_SIZE];
    let mut bytes_total = 0usize;
    let mut peak_bps = 0u64;
    let mut sample_bytes = 0usize;
    let mut sample_start = start;

    while bytes_total < content_length {
        if start.elapsed() > total_timeout {
            break;
        }
        let remaining = content_length - bytes_total;
        let to_send = remaining.min(TELEGRAM_CHUNK_SIZE);
        match stream.write_all(&chunk[..to_send]).and_then(|_| stream.flush()) {
            Ok(()) => {
                bytes_total += to_send;
                sample_bytes += to_send;

                if sample_start.elapsed() >= TELEGRAM_SPEED_SAMPLE_INTERVAL {
                    let sample_ms = sample_start.elapsed().as_millis() as u64;
                    update_peak_bps(&mut peak_bps, sample_bytes, sample_ms);
                    sample_bytes = 0;
                    sample_start = std::time::Instant::now();
                }
            }
            Err(err) => {
                stream.shutdown();
                let status = if bytes_total == 0 { "blocked" } else { "stalled" };
                return TelegramTransferResult::from_transfer(
                    status,
                    bytes_total,
                    peak_bps,
                    start,
                    Some(err.to_string()),
                );
            }
        }

        if sample_start.elapsed() > stall_timeout {
            stream.shutdown();
            return TelegramTransferResult::from_transfer(
                "stalled",
                bytes_total,
                peak_bps,
                start,
                Some("upload stall detected".to_string()),
            );
        }
    }

    stream.shutdown();
    let status = if bytes_total >= content_length * 98 / 100 {
        "ok"
    } else if bytes_total > 0 {
        "slow"
    } else {
        "blocked"
    };
    TelegramTransferResult::from_transfer(status, bytes_total, peak_bps, start, None)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn upload_host_target_uses_default_tls_verification() {
        let target = upload_target_address("api.telegram.org");

        assert_eq!(target, TargetAddress::Host("api.telegram.org".to_string()));
        assert_eq!(upload_tls_host(&target), "api.telegram.org");
        let mode = verifier_mode_for_target(&target, &upload_tls_host(&target));
        assert_eq!(mode, TelegramTlsVerifierMode::DefaultRoots);
        assert!(mode.verify_certificates());
        assert!(mode.custom_verifier().is_none());
    }

    #[test]
    fn upload_ip_target_is_explicit_domain_fronting_no_verify() {
        let target = upload_target_address("149.154.167.99");

        assert_eq!(target, TargetAddress::Ip("149.154.167.99".parse().unwrap()));
        assert_eq!(upload_tls_host(&target), TELEGRAM_UPLOAD_HOST);
        let mode = verifier_mode_for_target(&target, &upload_tls_host(&target));
        assert_eq!(mode, TelegramTlsVerifierMode::DomainFrontedNoVerify);
        assert!(!mode.verify_certificates());
        assert!(mode.custom_verifier().is_some());
    }
}
