use std::io::{ErrorKind, Read, Write};
use std::net::IpAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use ripdpi_diagnostics_contracts::util::{active_scan_io_deadline, with_scan_io_deadline};

use crate::http::{HttpBodyFraming, HttpResponse, HttpResponseDecoder, HttpResponseEvent, parse_https_endpoint};
use crate::tls::{
    ApplicationProtocolPolicy, ProbeCertificatePolicy, ProbeStreamError, ProbeStreamOptions, TlsClientProfile,
    TlsKeyLogCallback, open_probe_stream_targets_with_options_and_abort,
};
use crate::transport::{ConnectionStream, TargetAddress, TransportConfig, run_with_socket_abort_watchdog};
use crate::types::TelegramTarget;
use crate::util::{IO_TIMEOUT, MAX_HTTP_BYTES, TELEGRAM_CHUNK_SIZE, TELEGRAM_SPEED_SAMPLE_INTERVAL};

use super::target::{MAX_TRANSFER_MS, MAX_UPLOAD_BYTES};

const TELEGRAM_UPLOAD_HOST: &str = "telegram.org";
const MAX_PAGE_BYTES: usize = 1024 * 1024;
const MAX_MEDIA_BYTES: usize = 64 * 1024 * 1024;
const PNG_START: &[u8; 16] = b"\x89PNG\r\n\x1a\n\0\0\0\x0dIHDR";
const PNG_END: &[u8; 12] = b"\0\0\0\0IEND\xaeB`\x82";
const PNG_MIN_SIZE: usize = 8 + 25 + 12 + 12;

#[derive(Clone, Copy, PartialEq, Eq)]
enum TransferResponse {
    Page,
    Png,
    UploadAck,
}

impl TransferResponse {
    fn download(path: &str) -> Self {
        let path = path.split_once('?').map_or(path, |(path, _)| path);
        if path.ends_with(".png") { Self::Png } else { Self::Page }
    }

    fn accept(self) -> &'static str {
        match self {
            Self::Png => "image/png",
            Self::Page | Self::UploadAck => "*/*",
        }
    }

    fn method(self) -> &'static str {
        match self {
            Self::Page | Self::Png => "GET",
            Self::UploadAck => "POST",
        }
    }

    fn max_body_bytes(self) -> usize {
        match self {
            Self::Page => MAX_PAGE_BYTES,
            Self::Png => MAX_MEDIA_BYTES,
            Self::UploadAck => MAX_HTTP_BYTES,
        }
    }

    fn check_head(self, head: &HttpResponse, framing: HttpBodyFraming) -> Result<(), String> {
        if head.status_code != 200 && !(self == Self::UploadAck && head.status_code == 204) {
            return Err(format!("http_status_{}", head.status_code));
        }
        if head.status_code == 204
            && (head.headers.contains_key("content-length") || head.headers.contains_key("transfer-encoding"))
        {
            return Err("invalid_framing".to_string());
        }
        if self == Self::Png
            && !head.headers.get("content-type").is_some_and(|ty| {
                ty.split(';').next().is_some_and(|mime| mime.trim().eq_ignore_ascii_case("image/png"))
            })
        {
            return Err("unexpected_content_type".to_string());
        }
        if matches!(framing, HttpBodyFraming::Length(length) if length > self.max_body_bytes() as u64) {
            return Err("response_too_large".to_string());
        }
        Ok(())
    }
}

trait TelegramIo: Read + Write {
    fn set_io_timeout(&mut self, timeout: Duration) -> std::io::Result<()>;
}

impl TelegramIo for ConnectionStream {
    fn set_io_timeout(&mut self, timeout: Duration) -> std::io::Result<()> {
        let socket = match self {
            ConnectionStream::Plain(stream) => stream,
            ConnectionStream::Tls(stream) => &stream.sock,
        };
        socket.set_read_timeout(Some(timeout))?;
        socket.set_write_timeout(Some(timeout))
    }
}

pub(crate) struct TelegramTransferResult {
    pub(crate) status: String,
    pub(crate) avg_bps: u64,
    pub(crate) peak_bps: u64,
    pub(crate) bytes_total: usize,
    pub(crate) duration_ms: u64,
    pub(crate) measures_throughput: bool,
    pub(crate) error: Option<String>,
}

impl TelegramTransferResult {
    pub(crate) fn failed(error: String) -> Self {
        Self {
            status: "error".to_string(),
            avg_bps: 0,
            peak_bps: 0,
            bytes_total: 0,
            duration_ms: 0,
            measures_throughput: false,
            error: Some(error),
        }
    }

    pub(crate) fn aborted(reason: &str) -> Self {
        Self {
            status: reason.to_string(),
            avg_bps: 0,
            peak_bps: 0,
            bytes_total: 0,
            duration_ms: 0,
            measures_throughput: false,
            error: Some(format!("probe_aborted:{reason}")),
        }
    }
}

type AbortCheck<'a> = &'a (dyn Fn() -> Option<&'static str> + Sync);

struct TransferControl<'a> {
    started: Instant,
    deadline: Instant,
    deadline_reason: &'static str,
    stall: Duration,
    last_progress_ms: AtomicU64,
    should_abort: AbortCheck<'a>,
}

impl<'a> TransferControl<'a> {
    fn new(stall_ms: u64, total_ms: u64, should_abort: AbortCheck<'a>) -> Option<Self> {
        if !(1..=MAX_TRANSFER_MS).contains(&total_ms) || !(1..=total_ms).contains(&stall_ms) {
            return None;
        }
        let started = Instant::now();
        let own_deadline = started + Duration::from_millis(total_ms);
        let (deadline, deadline_reason) = match active_scan_io_deadline() {
            Some(scan) if scan <= own_deadline => (scan, "deadline_exceeded"),
            _ => (own_deadline, "transfer_timeout"),
        };
        Some(Self {
            started,
            deadline,
            deadline_reason,
            stall: Duration::from_millis(stall_ms),
            last_progress_ms: AtomicU64::new(0),
            should_abort,
        })
    }

    fn transfer_abort(&self) -> Option<&'static str> {
        (self.should_abort)().or_else(|| (Instant::now() >= self.deadline).then_some(self.deadline_reason))
    }

    fn exchange_abort(&self) -> Option<&'static str> {
        self.transfer_abort().or_else(|| self.stall_remaining().is_zero().then_some("stalled"))
    }

    fn mark_progress(&self) {
        // The watchdog uses this timestamp directly. It does not read other transfer state.
        self.last_progress_ms.store(self.started.elapsed().as_millis() as u64, Ordering::Relaxed);
    }

    fn stall_remaining(&self) -> Duration {
        let idle =
            self.started.elapsed().saturating_sub(Duration::from_millis(self.last_progress_ms.load(Ordering::Relaxed)));
        self.stall.saturating_sub(idle)
    }

    fn set_io_timeout(&self, stream: &mut dyn TelegramIo) -> Result<(), String> {
        if let Some(reason) = self.exchange_abort() {
            return Err(reason.to_string());
        }
        let remaining = self.deadline.saturating_duration_since(Instant::now());
        let idle = self.stall_remaining();
        let timeout = IO_TIMEOUT.min(remaining).min(idle);
        if timeout.is_zero() {
            return Err(self.exchange_abort().unwrap_or("stalled").to_string());
        }
        stream.set_io_timeout(timeout).map_err(|_| self.io_error("socket_timeout_setup"))
    }

    fn io_error(&self, operation: &str) -> String {
        self.exchange_abort().unwrap_or(operation).to_string()
    }
}

#[derive(Default)]
struct TransferProgress {
    bytes: usize,
    body_elapsed: Duration,
    sample_started: Duration,
    sample_bytes: usize,
    peak_bps: u64,
}

impl TransferProgress {
    fn record(&mut self, count: usize, elapsed: Duration) {
        self.bytes += count;
        self.body_elapsed = elapsed;
        self.sample_bytes += count;
        let sample_elapsed = elapsed.saturating_sub(self.sample_started);
        if sample_elapsed >= TELEGRAM_SPEED_SAMPLE_INTERVAL {
            let rate = self.sample_bytes as u64 * 1_000 / (sample_elapsed.as_millis() as u64).max(1);
            self.peak_bps = self.peak_bps.max(rate);
            self.sample_started = elapsed;
            self.sample_bytes = 0;
        }
    }

    fn finish(self, control: &TransferControl<'_>, error: Option<String>, measure: bool) -> TelegramTransferResult {
        let status = error.as_deref().map_or("ok", |err| {
            abort_status_from_error(err).unwrap_or(if err == "stalled" { "stalled" } else { "error" })
        });
        let mut result = TelegramTransferResult {
            status: status.to_string(),
            avg_bps: 0,
            peak_bps: 0,
            bytes_total: self.bytes,
            duration_ms: control.started.elapsed().as_millis().max(1) as u64,
            measures_throughput: measure && self.bytes > 0,
            error,
        };
        if result.measures_throughput {
            result.avg_bps = self.bytes as u64 * 1_000 / (self.body_elapsed.as_millis() as u64).max(1);
            result.peak_bps = self.peak_bps.max(result.avg_bps);
        }
        result
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

pub(crate) fn telegram_download_probe_with_abort(
    target: &TelegramTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    should_abort: AbortCheck<'_>,
) -> TelegramTransferResult {
    let Ok(endpoint) = parse_https_endpoint(&target.media_url) else {
        return TelegramTransferResult::failed("invalid_media_url".to_string());
    };
    let peer =
        HttpPeer { address: upload_target_address(&endpoint.host), port: endpoint.port, tls_host: &endpoint.host };
    let path = endpoint.request_target;
    let host = endpoint.authority;
    let response_kind = TransferResponse::download(&path);
    let accept = response_kind.accept();
    let request = format!("GET {path} HTTP/1.1\r\nHost: {host}\r\nAccept: {accept}\r\nConnection: close\r\n\r\n");
    run_http_transfer(target, &peer, transport, key_log, should_abort, &request, |stream, control| {
        telegram_download_transfer_loop(stream, response_kind, control)
    })
}

pub(crate) fn telegram_upload_probe_with_abort(
    target: &TelegramTarget,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    should_abort: AbortCheck<'_>,
) -> TelegramTransferResult {
    let content_length = target.upload_size_bytes;
    if content_length == 0 {
        return TelegramTransferResult::failed("empty_upload".to_string());
    }
    if content_length > MAX_UPLOAD_BYTES || target.upload_port == 0 {
        return TelegramTransferResult::failed("invalid_upload_limits".to_string());
    }
    let address = upload_target_address(&target.upload_ip);
    let upload_host = upload_tls_host(&address);
    if crate::tls::make_server_name(&upload_host, &address).is_err() {
        return TelegramTransferResult::failed("invalid_upload_host".to_string());
    }
    let peer = HttpPeer { address, port: target.upload_port, tls_host: &upload_host };
    let authority =
        if target.upload_port == 443 { upload_host.clone() } else { format!("{upload_host}:{}", target.upload_port) };
    let request = format!(
        "POST /upload HTTP/1.1\r\nHost: {authority}\r\nContent-Length: {content_length}\r\n\
         Content-Type: application/octet-stream\r\nConnection: close\r\n\r\n"
    );
    run_http_transfer(target, &peer, transport, key_log, should_abort, &request, |stream, control| {
        telegram_upload_transfer_loop(stream, content_length, control)
    })
}

struct HttpPeer<'a> {
    address: TargetAddress,
    port: u16,
    tls_host: &'a str,
}

fn run_http_transfer(
    target: &TelegramTarget,
    peer: &HttpPeer<'_>,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    should_abort: AbortCheck<'_>,
    request: &str,
    exchange: impl FnOnce(&mut ConnectionStream, &TransferControl<'_>) -> TelegramTransferResult,
) -> TelegramTransferResult {
    let Some(control) = TransferControl::new(target.stall_timeout_ms, target.total_timeout_ms, should_abort) else {
        return TelegramTransferResult::failed("invalid_transfer_limits".to_string());
    };
    with_scan_io_deadline(Some(control.deadline), || {
        let mut stream = match open_transfer_stream(peer, transport, key_log, &control) {
            Ok(stream) => stream,
            Err(result) => return result,
        };
        let socket = match &stream {
            ConnectionStream::Plain(socket) => socket.try_clone(),
            ConnectionStream::Tls(tls) => tls.sock.try_clone(),
        };
        let Ok(socket) = socket else {
            return TelegramTransferResult::failed("socket_watchdog_setup".to_string());
        };
        control.mark_progress();
        let result = run_with_socket_abort_watchdog(socket, &|| control.exchange_abort().is_some(), || {
            let mut result = match write_transfer_bytes(&mut stream, request.as_bytes(), &control) {
                Ok(()) => exchange(&mut stream, &control),
                Err(error) => TransferProgress::default().finish(&control, Some(error), false),
            };
            if let Some(reason) = control.transfer_abort() {
                result.status = abort_status_from_error(reason).unwrap_or("error").to_string();
                result.error = Some(reason.to_string());
            }
            Ok(result)
        });
        result.unwrap_or_else(|_| TelegramTransferResult::failed("socket_watchdog_setup".to_string()))
    })
}

fn open_transfer_stream(
    peer: &HttpPeer<'_>,
    transport: &TransportConfig,
    key_log: Option<&TlsKeyLogCallback>,
    control: &TransferControl<'_>,
) -> Result<ConnectionStream, TelegramTransferResult> {
    let options = ProbeStreamOptions {
        profile: TlsClientProfile::Auto,
        application_protocol: ApplicationProtocolPolicy::Http11Only,
        certificate_policy: ProbeCertificatePolicy::WebPki,
        key_log,
    };
    open_probe_stream_targets_with_options_and_abort(
        std::slice::from_ref(&peer.address),
        peer.port,
        transport,
        Some(peer.tls_host),
        &options,
        &|| control.transfer_abort(),
    )
    .map(|r| r.stream)
    .map_err(|error| transfer_setup_error(error, control))
}

fn write_transfer_bytes(
    stream: &mut dyn TelegramIo,
    bytes: &[u8],
    control: &TransferControl<'_>,
) -> Result<(), String> {
    control.set_io_timeout(stream)?;
    stream.write_all(bytes).and_then(|_| stream.flush()).map_err(|_| control.io_error("http_write"))?;
    control.mark_progress();
    Ok(())
}

fn telegram_download_transfer_loop(
    stream: &mut dyn TelegramIo,
    response_kind: TransferResponse,
    control: &TransferControl<'_>,
) -> TelegramTransferResult {
    let mut progress = TransferProgress::default();
    let is_media = response_kind == TransferResponse::Png;
    let mut ending = [0; PNG_END.len()];
    let mut response = read_telegram_response(stream, response_kind, control, &mut |bytes, body_elapsed| {
        if is_media {
            if progress.bytes < PNG_START.len() {
                let prefix = &PNG_START[progress.bytes..];
                let n = prefix.len().min(bytes.len());
                if prefix[..n] != bytes[..n] {
                    return Err("invalid_png_header".to_string());
                }
            }
            let n = ending.len().min(bytes.len());
            ending.rotate_left(n);
            let pos = ending.len() - n;
            ending[pos..].copy_from_slice(&bytes[bytes.len() - n..]);
        }
        progress.record(bytes.len(), body_elapsed);
        Ok(())
    });
    if response.is_ok() {
        response = match (is_media, progress.bytes) {
            (true, len) if len < PNG_MIN_SIZE || ending != *PNG_END => Err("incomplete_png".to_string()),
            (false, 0) => Err("empty_response_body".to_string()),
            _ => Ok(()),
        };
    }
    progress.finish(control, response.err(), is_media)
}

fn telegram_upload_transfer_loop(
    stream: &mut dyn TelegramIo,
    content_length: usize,
    control: &TransferControl<'_>,
) -> TelegramTransferResult {
    let mut progress = TransferProgress::default();
    let chunk = [0; TELEGRAM_CHUNK_SIZE];
    let body_started = Instant::now();
    while progress.bytes < content_length {
        let to_send = (content_length - progress.bytes).min(chunk.len());
        if let Err(error) = write_transfer_bytes(stream, &chunk[..to_send], control) {
            return progress.finish(control, Some(error), true);
        }
        progress.record(to_send, body_started.elapsed());
    }
    let response = read_telegram_response(stream, TransferResponse::UploadAck, control, &mut |_, _| Ok(()));
    progress.finish(control, response.err(), true)
}

fn read_telegram_response(
    stream: &mut dyn TelegramIo,
    response_kind: TransferResponse,
    control: &TransferControl<'_>,
    on_data: &mut dyn FnMut(&[u8], Duration) -> Result<(), String>,
) -> Result<(), String> {
    let mut decoder = HttpResponseDecoder::new(response_kind.method(), MAX_HTTP_BYTES).map_err(str::to_string)?;
    let mut buffer = [0; 8192];
    let mut body_bytes = 0usize;
    let mut body_started = Instant::now();
    loop {
        if let Some(reason) = control.exchange_abort() {
            return Err(reason.to_string());
        }
        match decoder.next_event().map_err(str::to_string)? {
            HttpResponseEvent::NeedInput => {
                control.set_io_timeout(stream)?;
                match stream.read(&mut buffer[..decoder.input_capacity()]) {
                    Ok(0) => decoder.finish_input(),
                    Ok(count) => decoder.feed(&buffer[..count]).map_err(str::to_string)?,
                    Err(error) if matches!(error.kind(), ErrorKind::TimedOut | ErrorKind::WouldBlock) => continue,
                    Err(_) => return Err(control.io_error("http_read")),
                }
            }
            HttpResponseEvent::Head(head, framing) => {
                response_kind.check_head(&head, framing)?;
                body_started = Instant::now();
                control.mark_progress();
            }
            HttpResponseEvent::Data(bytes) => {
                body_bytes += bytes.len();
                if body_bytes > response_kind.max_body_bytes() {
                    return Err("response_too_large".to_string());
                }
                on_data(&bytes, body_started.elapsed())?;
                control.mark_progress();
            }
            HttpResponseEvent::Complete => return Ok(()),
        }
    }
}

fn abort_status_from_error(error: &str) -> Option<&'static str> {
    match error {
        "cancelled" | "probe_aborted:cancelled" => Some("cancelled"),
        "scan_deadline_exceeded" | "deadline_exceeded" | "probe_aborted:deadline_exceeded" => Some("deadline_exceeded"),
        _ => None,
    }
}

fn transfer_setup_error(error: ProbeStreamError, control: &TransferControl<'_>) -> TelegramTransferResult {
    match control.transfer_abort().or_else(|| abort_status_from_error(&error.message)) {
        Some("transfer_timeout") => TelegramTransferResult::failed("transfer_timeout".to_string()),
        Some(reason) => TelegramTransferResult::aborted(reason),
        None if error.rejected_server_certificate() => {
            TelegramTransferResult::failed("certificate_verification_failed".to_string())
        }
        None => TelegramTransferResult::failed(error.stage.as_str().to_string()),
    }
}

#[cfg(test)]
mod tests;
