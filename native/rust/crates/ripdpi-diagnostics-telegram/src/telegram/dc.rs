use std::net::{IpAddr, Shutdown, SocketAddr, TcpStream};
use std::time::Duration;

use crate::types::TelegramTarget;

pub(crate) struct TelegramDcResult {
    pub(crate) reachable: usize,
    pub(crate) total: usize,
    pub(crate) results: Vec<String>,
}

pub(crate) fn telegram_dc_probe(target: &TelegramTarget) -> TelegramDcResult {
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
