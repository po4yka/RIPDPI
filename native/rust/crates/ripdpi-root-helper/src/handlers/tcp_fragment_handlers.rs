use std::os::fd::{IntoRawFd, RawFd};

use ripdpi_privileged_ops as platform;
use ripdpi_root_helper_protocol::{HelperResponse, IpFragTcpParams};
use tracing::{debug, error};

use super::fd_adoption::adopt_tcp_stream;

pub fn handle_send_ip_fragmented_tcp(fd: RawFd, params: IpFragTcpParams) -> (HelperResponse, Option<RawFd>) {
    debug!(fd, split = params.split_offset, "send_ip_fragmented_tcp");
    // SAFETY: `fd` was just received over SCM_RIGHTS by the helper
    // dispatch loop, which guarantees a live TCP socket exclusively owned
    // by this handler. Every exit path below releases the fd via
    // `into_raw_fd`, so the kernel descriptor is never double-closed.
    let stream = unsafe { adopt_tcp_stream(fd) };
    match platform::send_ip_fragmented_tcp(
        &stream,
        &params.payload,
        params.split_offset,
        params.default_ttl,
        None,
        params.disorder,
        ripdpi_ipfrag::Ipv6ExtHeaders::default(),
        platform::TcpFlagOverrides { set: params.tcp_flags_set, unset: params.tcp_flags_unset },
        params.ipv4_identification,
    ) {
        Ok(()) => {
            let out_fd = stream.into_raw_fd();
            (HelperResponse::success(serde_json::Value::Null), Some(out_fd))
        }
        Err(e) => {
            let _ = stream.into_raw_fd();
            error!(%e, "send_ip_fragmented_tcp failed");
            (HelperResponse::error(e.to_string()), None)
        }
    }
}
