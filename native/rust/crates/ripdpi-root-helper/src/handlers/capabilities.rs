use std::os::fd::RawFd;

use ripdpi_privileged_ops as platform;
use ripdpi_root_helper_protocol::HelperResponse;
use tracing::{error, info};

pub fn handle_probe_capabilities() -> (HelperResponse, Option<RawFd>) {
    info!("probing capabilities");
    match platform::probe_ip_fragmentation_capabilities(None) {
        Ok(caps) => {
            let data = serde_json::json!({
                "raw_ipv4": caps.raw_ipv4,
                "raw_ipv6": caps.raw_ipv6,
                "tcp_repair": caps.tcp_repair,
            });
            info!(?caps, "capabilities probed");
            (HelperResponse::success(data), None)
        }
        Err(e) => {
            error!(%e, "probe_capabilities failed");
            (HelperResponse::error(e.to_string()), None)
        }
    }
}
