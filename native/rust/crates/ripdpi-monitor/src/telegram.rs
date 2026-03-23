use std::io::{self, ErrorKind, Read, Write};
use std::net::{IpAddr, Shutdown, SocketAddr, TcpStream};
use std::time::Duration;

use crate::http::extract_host_from_url;
use crate::http::extract_path_from_url;
use crate::tls::{open_probe_stream, TlsClientProfile};
use crate::transport::{TargetAddress, TransportConfig};
use crate::types::{ProbeDetail, ProbeResult, TelegramTarget};
use crate::util::*;

// --- Types ---

struct TelegramTransferResult {
    status: String,
    avg_bps: u64,
    peak_bps: u64,
    bytes_total: usize,
    duration_ms: u64,
    error: Option<String>,
}

struct TelegramDcResult {
    reachable: usize,
    total: usize,
    results: Vec<String>,
}

struct TelegramWsProbeResult {
    status: String,
    rtt_ms: u64,
    error: Option<String>,
}

// --- Functions ---

pub(crate) fn run_telegram_probe(target: &TelegramTarget, transport: &TransportConfig) -> ProbeResult {
    let dl = telegram_download_probe(target, transport);
    let ul = telegram_upload_probe(target, transport);
    let dc = telegram_dc_probe(target);
    let ws = telegram_ws_tunnel_probe();

    let verdict = if (dl.status == "blocked" || ul.status == "blocked") && dc.reachable == 0 {
        "blocked"
    } else if dl.status == "stalled" || dl.status == "slow" || ul.status == "stalled" || ul.status == "slow" {
        "slow"
    } else if dc.reachable < dc.total && dc.reachable > 0 {
        "partial"
    } else if dl.status == "ok" && ul.status == "ok" {
        "ok"
    } else {
        "error"
    };

    let quality_score = compute_telegram_quality_score(&dl, &ul, &dc, &ws);

    ProbeResult {
        probe_type: "telegram_availability".to_string(),
        target: "telegram.org".to_string(),
        outcome: verdict.to_string(),
        details: vec![
            ProbeDetail { key: "verdict".to_string(), value: verdict.to_string() },
            ProbeDetail { key: "qualityScore".to_string(), value: quality_score.to_string() },
            ProbeDetail { key: "downloadStatus".to_string(), value: dl.status },
            ProbeDetail { key: "downloadAvgBps".to_string(), value: dl.avg_bps.to_string() },
            ProbeDetail { key: "downloadPeakBps".to_string(), value: dl.peak_bps.to_string() },
            ProbeDetail { key: "downloadBytes".to_string(), value: dl.bytes_total.to_string() },
            ProbeDetail { key: "downloadDurationMs".to_string(), value: dl.duration_ms.to_string() },
            ProbeDetail { key: "downloadError".to_string(), value: dl.error.unwrap_or_else(|| "none".to_string()) },
            ProbeDetail { key: "uploadStatus".to_string(), value: ul.status },
            ProbeDetail { key: "uploadAvgBps".to_string(), value: ul.avg_bps.to_string() },
            ProbeDetail { key: "uploadPeakBps".to_string(), value: ul.peak_bps.to_string() },
            ProbeDetail { key: "uploadBytes".to_string(), value: ul.bytes_total.to_string() },
            ProbeDetail { key: "uploadDurationMs".to_string(), value: ul.duration_ms.to_string() },
            ProbeDetail { key: "uploadError".to_string(), value: ul.error.unwrap_or_else(|| "none".to_string()) },
            ProbeDetail { key: "dcReachable".to_string(), value: dc.reachable.to_string() },
            ProbeDetail { key: "dcTotal".to_string(), value: dc.total.to_string() },
            ProbeDetail { key: "dcResults".to_string(), value: dc.results.join("|") },
            ProbeDetail { key: "wsTunnelStatus".to_string(), value: ws.status },
            ProbeDetail { key: "wsTunnelRttMs".to_string(), value: ws.rtt_ms.to_string() },
            ProbeDetail { key: "wsTunnelError".to_string(), value: ws.error.unwrap_or_else(|| "none".to_string()) },
        ],
    }
}

/// Compute a composite quality score from Telegram probe sub-results.
///
/// Lower score = better quality. Returns `u64::MAX` if all probes failed.
///
/// Weights: download (3x), upload (2x), DC-reachability (1x per DC), WS tunnel (1x).
/// Penalizes failures with +2000ms per failed component (adapted from tglock's
/// `benchmark_telegram` scoring algorithm).
fn compute_telegram_quality_score(
    dl: &TelegramTransferResult,
    ul: &TelegramTransferResult,
    dc: &TelegramDcResult,
    ws: &TelegramWsProbeResult,
) -> u64 {
    const FAILURE_PENALTY_MS: u64 = 2000;
    const PARTIAL_PENALTY_MS: u64 = 1000;
    const DL_WEIGHT: u64 = 3;
    const UL_WEIGHT: u64 = 2;
    const WS_WEIGHT: u64 = 1;

    let transfer_score = |t: &TelegramTransferResult| -> u64 {
        match t.status.as_str() {
            "ok" => t.duration_ms,
            "slow" | "stalled" => t.duration_ms.saturating_add(PARTIAL_PENALTY_MS),
            _ => FAILURE_PENALTY_MS,
        }
    };

    let dl_score = transfer_score(dl) * DL_WEIGHT;
    let ul_score = transfer_score(ul) * UL_WEIGHT;

    let dc_total = dc.total.max(1) as u64;
    let dc_unreachable = dc.total.saturating_sub(dc.reachable) as u64;
    let dc_score = dc_unreachable * FAILURE_PENALTY_MS;
    let dc_weight = dc_total;

    let ws_score = if ws.status == "ok" { ws.rtt_ms } else { FAILURE_PENALTY_MS } * WS_WEIGHT;

    let total_weighted = dl_score + ul_score + dc_score + ws_score;
    let total_weight = DL_WEIGHT + UL_WEIGHT + dc_weight + WS_WEIGHT;

    if dl.status == "blocked" && ul.status == "blocked" && dc.reachable == 0 && ws.status != "ok" {
        return u64::MAX;
    }

    total_weighted / total_weight
}

fn telegram_download_probe(target: &TelegramTarget, transport: &TransportConfig) -> TelegramTransferResult {
    let host = match extract_host_from_url(&target.media_url) {
        Some(h) => h,
        None => {
            return TelegramTransferResult {
                status: "blocked".to_string(),
                avg_bps: 0,
                peak_bps: 0,
                bytes_total: 0,
                duration_ms: 0,
                error: Some("invalid media_url".to_string()),
            };
        }
    };
    let path = extract_path_from_url(&target.media_url);

    let mut stream = match open_probe_stream(
        &TargetAddress::Host(host.clone()),
        443,
        transport,
        Some(&host),
        false,
        TlsClientProfile::Auto,
        None,
    ) {
        Ok(s) => s,
        Err(err) => {
            return TelegramTransferResult {
                status: "blocked".to_string(),
                avg_bps: 0,
                peak_bps: 0,
                bytes_total: 0,
                duration_ms: 0,
                error: Some(err),
            };
        }
    };

    let request = format!("GET {path} HTTP/1.1\r\nHost: {host}\r\nAccept: */*\r\nConnection: close\r\n\r\n");
    if let Err(err) = stream.write_all(request.as_bytes()).and_then(|_| stream.flush()) {
        stream.shutdown();
        return TelegramTransferResult {
            status: "blocked".to_string(),
            avg_bps: 0,
            peak_bps: 0,
            bytes_total: 0,
            duration_ms: 0,
            error: Some(err.to_string()),
        };
    }

    // Skip HTTP headers
    let mut header_buf = Vec::new();
    let mut header_byte = [0u8; 1];
    loop {
        match stream.read(&mut header_byte) {
            Ok(0) => break,
            Ok(_) => {
                header_buf.push(header_byte[0]);
                if header_buf.len() >= 4 && header_buf[header_buf.len() - 4..] == *b"\r\n\r\n" {
                    break;
                }
                if header_buf.len() > MAX_HTTP_BYTES {
                    stream.shutdown();
                    return TelegramTransferResult {
                        status: "blocked".to_string(),
                        avg_bps: 0,
                        peak_bps: 0,
                        bytes_total: 0,
                        duration_ms: 0,
                        error: Some("headers too large".to_string()),
                    };
                }
            }
            Err(err) => {
                stream.shutdown();
                return TelegramTransferResult {
                    status: "blocked".to_string(),
                    avg_bps: 0,
                    peak_bps: 0,
                    bytes_total: 0,
                    duration_ms: 0,
                    error: Some(err.to_string()),
                };
            }
        }
    }

    let stall_timeout = Duration::from_millis(target.stall_timeout_ms);
    let total_timeout = Duration::from_millis(target.total_timeout_ms);
    let start = std::time::Instant::now();
    let mut last_data_at = start;
    let mut bytes_total = 0usize;
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
            let elapsed_ms = start.elapsed().as_millis() as u64;
            let avg_bps = if elapsed_ms > 0 { (bytes_total as u64) * 1000 / elapsed_ms } else { 0 };
            return TelegramTransferResult {
                status: "stalled".to_string(),
                avg_bps,
                peak_bps,
                bytes_total,
                duration_ms: elapsed_ms,
                error: Some("stall detected".to_string()),
            };
        }

        match stream.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                bytes_total += n;
                sample_bytes += n;
                last_data_at = std::time::Instant::now();

                if sample_start.elapsed() >= TELEGRAM_SPEED_SAMPLE_INTERVAL {
                    let sample_ms = sample_start.elapsed().as_millis() as u64;
                    if sample_ms > 0 {
                        let sample_bps = (sample_bytes as u64) * 1000 / sample_ms;
                        if sample_bps > peak_bps {
                            peak_bps = sample_bps;
                        }
                    }
                    sample_bytes = 0;
                    sample_start = std::time::Instant::now();
                }
            }
            Err(ref err) if err.kind() == ErrorKind::TimedOut || err.kind() == ErrorKind::WouldBlock => {
                continue;
            }
            Err(err) => {
                stream.shutdown();
                let elapsed_ms = start.elapsed().as_millis() as u64;
                let avg_bps = if elapsed_ms > 0 { (bytes_total as u64) * 1000 / elapsed_ms } else { 0 };
                let status = if bytes_total == 0 { "blocked" } else { "stalled" };
                return TelegramTransferResult {
                    status: status.to_string(),
                    avg_bps,
                    peak_bps,
                    bytes_total,
                    duration_ms: elapsed_ms,
                    error: Some(err.to_string()),
                };
            }
        }
    }

    stream.shutdown();
    let elapsed_ms = start.elapsed().as_millis() as u64;
    let avg_bps = if elapsed_ms > 0 { (bytes_total as u64) * 1000 / elapsed_ms } else { 0 };

    let status = if bytes_total >= TELEGRAM_DOWNLOAD_EXPECTED_BYTES * 98 / 100 {
        "ok"
    } else if bytes_total > 0 {
        "slow"
    } else {
        "blocked"
    };

    TelegramTransferResult {
        status: status.to_string(),
        avg_bps,
        peak_bps,
        bytes_total,
        duration_ms: elapsed_ms,
        error: None,
    }
}

fn telegram_upload_probe(target: &TelegramTarget, transport: &TransportConfig) -> TelegramTransferResult {
    let upload_ip: IpAddr = match target.upload_ip.parse() {
        Ok(ip) => ip,
        Err(err) => {
            return TelegramTransferResult {
                status: "blocked".to_string(),
                avg_bps: 0,
                peak_bps: 0,
                bytes_total: 0,
                duration_ms: 0,
                error: Some(err.to_string()),
            };
        }
    };

    let mut stream = match open_probe_stream(
        &TargetAddress::Ip(upload_ip),
        target.upload_port,
        transport,
        Some("telegram.org"),
        false,
        TlsClientProfile::Auto,
        None,
    ) {
        Ok(s) => s,
        Err(err) => {
            return TelegramTransferResult {
                status: "blocked".to_string(),
                avg_bps: 0,
                peak_bps: 0,
                bytes_total: 0,
                duration_ms: 0,
                error: Some(err),
            };
        }
    };

    let content_length = target.upload_size_bytes;
    let header = format!(
        "POST /upload HTTP/1.1\r\nHost: telegram.org\r\nContent-Length: {content_length}\r\n\
         Content-Type: application/octet-stream\r\nConnection: close\r\n\r\n"
    );
    if let Err(err) = stream.write_all(header.as_bytes()).and_then(|_| stream.flush()) {
        stream.shutdown();
        return TelegramTransferResult {
            status: "blocked".to_string(),
            avg_bps: 0,
            peak_bps: 0,
            bytes_total: 0,
            duration_ms: 0,
            error: Some(err.to_string()),
        };
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
                    if sample_ms > 0 {
                        let sample_bps = (sample_bytes as u64) * 1000 / sample_ms;
                        if sample_bps > peak_bps {
                            peak_bps = sample_bps;
                        }
                    }
                    sample_bytes = 0;
                    sample_start = std::time::Instant::now();
                }
            }
            Err(err) => {
                stream.shutdown();
                let elapsed_ms = start.elapsed().as_millis() as u64;
                let avg_bps = if elapsed_ms > 0 { (bytes_total as u64) * 1000 / elapsed_ms } else { 0 };
                let status = if bytes_total == 0 { "blocked" } else { "stalled" };
                return TelegramTransferResult {
                    status: status.to_string(),
                    avg_bps,
                    peak_bps,
                    bytes_total,
                    duration_ms: elapsed_ms,
                    error: Some(err.to_string()),
                };
            }
        }

        if sample_start.elapsed() > stall_timeout {
            stream.shutdown();
            let elapsed_ms = start.elapsed().as_millis() as u64;
            let avg_bps = if elapsed_ms > 0 { (bytes_total as u64) * 1000 / elapsed_ms } else { 0 };
            return TelegramTransferResult {
                status: "stalled".to_string(),
                avg_bps,
                peak_bps,
                bytes_total,
                duration_ms: elapsed_ms,
                error: Some("upload stall detected".to_string()),
            };
        }
    }

    stream.shutdown();
    let elapsed_ms = start.elapsed().as_millis() as u64;
    let avg_bps = if elapsed_ms > 0 { (bytes_total as u64) * 1000 / elapsed_ms } else { 0 };

    let status = if bytes_total >= content_length * 98 / 100 {
        "ok"
    } else if bytes_total > 0 {
        "slow"
    } else {
        "blocked"
    };

    TelegramTransferResult {
        status: status.to_string(),
        avg_bps,
        peak_bps,
        bytes_total,
        duration_ms: elapsed_ms,
        error: None,
    }
}

fn telegram_dc_probe(target: &TelegramTarget) -> TelegramDcResult {
    let dc_timeout = Duration::from_secs(5);
    let mut results = Vec::new();
    let mut reachable = 0usize;

    for dc in &target.dc_endpoints {
        let ip: IpAddr = match dc.ip.parse() {
            Ok(ip) => ip,
            Err(_) => {
                results.push(format!("{}:fail:parse_error", dc.label));
                continue;
            }
        };
        let addr = SocketAddr::new(ip, dc.port);
        let start = std::time::Instant::now();
        match TcpStream::connect_timeout(&addr, dc_timeout) {
            Ok(stream) => {
                let rtt_ms = start.elapsed().as_millis();
                let _ = stream.shutdown(Shutdown::Both);
                results.push(format!("{}:ok:{}ms", dc.label, rtt_ms));
                reachable += 1;
            }
            Err(_) => {
                results.push(format!("{}:fail", dc.label));
            }
        }
    }

    TelegramDcResult { reachable, total: target.dc_endpoints.len(), results }
}

/// Probe whether the Telegram WebSocket tunnel endpoint is reachable.
///
/// Attempts a TLS + WebSocket handshake to `wss://kws2.web.telegram.org/apiws`
/// (DC2 is the default/most common). Does not send any MTProto data -- only
/// verifies that the WSS endpoint accepts connections.
fn telegram_ws_tunnel_probe() -> TelegramWsProbeResult {
    telegram_ws_tunnel_probe_with(
        || ripdpi_runtime::ws_bootstrap::resolve_ws_tunnel_addr(2, None),
        |resolved_addr| ripdpi_ws_tunnel::probe_ws_tunnel_with_addr(2, resolved_addr),
    )
}

fn telegram_ws_tunnel_probe_with<ResolveWsAddr, ProbeWs>(
    resolve_ws_addr: ResolveWsAddr,
    probe_ws: ProbeWs,
) -> TelegramWsProbeResult
where
    ResolveWsAddr: FnOnce() -> io::Result<SocketAddr>,
    ProbeWs: FnOnce(Option<SocketAddr>) -> io::Result<()>,
{
    let start = std::time::Instant::now();
    let resolved_addr = match resolve_ws_addr() {
        Ok(addr) => Some(addr),
        Err(err) => {
            log::warn!("Telegram WS tunnel encrypted DNS bootstrap failed: {err}");
            None
        }
    };

    match probe_ws(resolved_addr) {
        Ok(()) => {
            let rtt_ms = start.elapsed().as_millis() as u64;
            TelegramWsProbeResult { status: "ok".to_string(), rtt_ms, error: None }
        }
        Err(e) => {
            let rtt_ms = start.elapsed().as_millis() as u64;
            TelegramWsProbeResult { status: "unreachable".to_string(), rtt_ms, error: Some(e.to_string()) }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::cell::Cell;
    use std::net::Ipv4Addr;

    #[test]
    fn verdict_blocked_when_both_transfers_blocked_and_no_dc() {
        // This tests the verdict classification logic
        let dl_status = "blocked";
        let ul_status = "blocked";
        let dc_reachable = 0usize;
        let dc_total = 3usize;

        let verdict = if (dl_status == "blocked" || ul_status == "blocked") && dc_reachable == 0 {
            "blocked"
        } else if dl_status == "stalled" || dl_status == "slow" || ul_status == "stalled" || ul_status == "slow" {
            "slow"
        } else if dc_reachable < dc_total && dc_reachable > 0 {
            "partial"
        } else if dl_status == "ok" && ul_status == "ok" {
            "ok"
        } else {
            "error"
        };
        assert_eq!(verdict, "blocked");
    }

    #[test]
    fn verdict_slow_when_download_stalled() {
        let dl_status = "stalled";
        let ul_status = "ok";
        let dc_reachable = 3usize;
        let dc_total = 3usize;

        let verdict = if (dl_status == "blocked" || ul_status == "blocked") && dc_reachable == 0 {
            "blocked"
        } else if dl_status == "stalled" || dl_status == "slow" || ul_status == "stalled" || ul_status == "slow" {
            "slow"
        } else if dc_reachable < dc_total && dc_reachable > 0 {
            "partial"
        } else if dl_status == "ok" && ul_status == "ok" {
            "ok"
        } else {
            "error"
        };
        assert_eq!(verdict, "slow");
    }

    #[test]
    fn verdict_partial_when_some_dc_unreachable() {
        let dl_status = "ok";
        let ul_status = "ok";
        let dc_reachable = 2usize;
        let dc_total = 3usize;

        let verdict = if (dl_status == "blocked" || ul_status == "blocked") && dc_reachable == 0 {
            "blocked"
        } else if dl_status == "stalled" || dl_status == "slow" || ul_status == "stalled" || ul_status == "slow" {
            "slow"
        } else if dc_reachable < dc_total && dc_reachable > 0 {
            "partial"
        } else if dl_status == "ok" && ul_status == "ok" {
            "ok"
        } else {
            "error"
        };
        assert_eq!(verdict, "partial");
    }

    // --- Quality score tests ---

    fn make_transfer(status: &str, duration_ms: u64) -> TelegramTransferResult {
        TelegramTransferResult {
            status: status.to_string(),
            avg_bps: 0,
            peak_bps: 0,
            bytes_total: 0,
            duration_ms,
            error: None,
        }
    }

    fn make_dc(reachable: usize, total: usize) -> TelegramDcResult {
        TelegramDcResult { reachable, total, results: vec![] }
    }

    fn make_ws(status: &str, rtt_ms: u64) -> TelegramWsProbeResult {
        TelegramWsProbeResult { status: status.to_string(), rtt_ms, error: None }
    }

    #[test]
    fn quality_score_low_for_perfect_results() {
        let dl = make_transfer("ok", 100);
        let ul = make_transfer("ok", 80);
        let dc = make_dc(3, 3);
        let ws = make_ws("ok", 50);

        let score = compute_telegram_quality_score(&dl, &ul, &dc, &ws);
        // (100*3 + 80*2 + 0 + 50*1) / (3+2+3+1) = (300+160+0+50)/9 = 510/9 = 56
        assert_eq!(score, 56);
    }

    #[test]
    fn quality_score_penalizes_blocked_download_heavily() {
        let dl = make_transfer("blocked", 0);
        let ul = make_transfer("ok", 80);
        let dc = make_dc(3, 3);
        let ws = make_ws("ok", 50);

        let score = compute_telegram_quality_score(&dl, &ul, &dc, &ws);
        // (2000*3 + 80*2 + 0 + 50*1) / 9 = (6000+160+0+50)/9 = 6210/9 = 690
        assert_eq!(score, 690);
    }

    #[test]
    fn quality_score_penalizes_unreachable_dcs() {
        let dl = make_transfer("ok", 100);
        let ul = make_transfer("ok", 80);
        let dc = make_dc(1, 3); // 2 unreachable
        let ws = make_ws("ok", 50);

        let score = compute_telegram_quality_score(&dl, &ul, &dc, &ws);
        // (100*3 + 80*2 + 2*2000 + 50*1) / 9 = (300+160+4000+50)/9 = 4510/9 = 501
        assert_eq!(score, 501);
    }

    #[test]
    fn quality_score_is_max_when_all_probes_fail() {
        let dl = make_transfer("blocked", 0);
        let ul = make_transfer("blocked", 0);
        let dc = make_dc(0, 3);
        let ws = make_ws("unreachable", 0);

        let score = compute_telegram_quality_score(&dl, &ul, &dc, &ws);
        assert_eq!(score, u64::MAX);
    }

    #[test]
    fn quality_score_reflects_latency_differences() {
        let fast_dl = make_transfer("ok", 50);
        let slow_dl = make_transfer("ok", 500);
        let ul = make_transfer("ok", 80);
        let dc = make_dc(3, 3);
        let ws = make_ws("ok", 50);

        let fast_score = compute_telegram_quality_score(&fast_dl, &ul, &dc, &ws);
        let slow_score = compute_telegram_quality_score(&slow_dl, &ul, &dc, &ws);
        assert!(fast_score < slow_score, "faster download should produce lower (better) score");
    }

    #[test]
    fn quality_score_partial_penalty_for_slow_transfer() {
        let dl = make_transfer("slow", 200);
        let ul = make_transfer("ok", 80);
        let dc = make_dc(3, 3);
        let ws = make_ws("ok", 50);

        let score = compute_telegram_quality_score(&dl, &ul, &dc, &ws);
        // (1200*3 + 80*2 + 0 + 50*1) / 9 = (3600+160+0+50)/9 = 3810/9 = 423
        assert_eq!(score, 423);
    }

    #[test]
    fn verdict_ok_when_all_good() {
        let dl_status = "ok";
        let ul_status = "ok";
        let dc_reachable = 3usize;
        let dc_total = 3usize;

        let verdict = if (dl_status == "blocked" || ul_status == "blocked") && dc_reachable == 0 {
            "blocked"
        } else if dl_status == "stalled" || dl_status == "slow" || ul_status == "stalled" || ul_status == "slow" {
            "slow"
        } else if dc_reachable < dc_total && dc_reachable > 0 {
            "partial"
        } else if dl_status == "ok" && ul_status == "ok" {
            "ok"
        } else {
            "error"
        };
        assert_eq!(verdict, "ok");
    }

    #[test]
    fn telegram_ws_tunnel_probe_passes_resolved_addr_to_probe() {
        let expected = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)), 443);
        let captured_addr = Cell::new(None);

        let result = telegram_ws_tunnel_probe_with(
            || Ok(expected),
            |resolved_addr| {
                captured_addr.set(resolved_addr);
                Ok(())
            },
        );

        assert_eq!(captured_addr.get(), Some(expected));
        assert_eq!(result.status, "ok");
        assert!(result.error.is_none());
    }

    #[test]
    fn telegram_ws_tunnel_probe_falls_back_to_unresolved_probe_when_lookup_fails() {
        let captured_addr = Cell::new(Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 443)));

        let result = telegram_ws_tunnel_probe_with(
            || Err(io::Error::new(io::ErrorKind::TimedOut, "dns timed out")),
            |resolved_addr| {
                captured_addr.set(resolved_addr);
                Ok(())
            },
        );

        assert_eq!(captured_addr.get(), None);
        assert_eq!(result.status, "ok");
        assert!(result.error.is_none());
    }
}
