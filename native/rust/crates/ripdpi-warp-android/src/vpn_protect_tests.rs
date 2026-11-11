use super::*;
use ripdpi_warp_core::{WarpEndpointProbeRequest, WarpProbeError, probe_endpoint_with_platform};
use tokio::net::UdpSocket;

struct Callback {
    failure: Option<io::ErrorKind>,
}

impl ProtectCallback for Callback {
    fn protect(&self, _fd: RawFd) -> io::Result<()> {
        match self.failure {
            Some(kind) => Err(io::Error::new(kind, "test callback rejected protection")),
            None => Ok(()),
        }
    }
}

struct Registration(ProtectGeneration);

impl Drop for Registration {
    fn drop(&mut self) {
        let _ = unregister_protect_callback_if(self.0);
    }
}

fn request(endpoint: std::net::SocketAddr) -> WarpEndpointProbeRequest {
    serde_json::from_value(serde_json::json!({
        "endpoint": {
            "host": endpoint.ip().to_string(),
            "ipv4": endpoint.ip().to_string(),
            "port": endpoint.port(),
            "source": "test"
        },
        "privateKey": "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
        "peerPublicKey": "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "timeoutMs": 50
    }))
    .expect("synthetic probe")
}

/// # Cancel safety:
/// NOT cancel-safe: a probe may send its initiation, but owns and drops its socket.
#[tokio::test(flavor = "current_thread")]
async fn explicit_policy_preserves_off_vpn_and_fail_closed_operation() {
    let peer = UdpSocket::bind("127.0.0.1:0").await.expect("silent peer");
    let endpoint = peer.local_addr().expect("endpoint");
    let inactive = warp_platform(false);
    let required = warp_platform(true);
    let result = probe_endpoint_with_platform(request(endpoint), &inactive).await;
    assert!(matches!(result, Err(WarpProbeError::Timeout)), "inactive must reach the peer: {result:?}");
    let result = probe_endpoint_with_platform(request(endpoint), &required).await;
    assert!(matches!(result, Err(WarpProbeError::Io(error)) if error.kind() == io::ErrorKind::NotConnected));
    for kind in [io::ErrorKind::PermissionDenied, io::ErrorKind::NotConnected] {
        let registered = Registration(register_protect_callback_versioned(Arc::new(Callback { failure: Some(kind) })));
        let result = probe_endpoint_with_platform(request(endpoint), &required).await;
        assert!(matches!(result, Err(WarpProbeError::Io(error)) if error.kind() == kind));
        let result = probe_endpoint_with_platform(request(endpoint), &inactive).await;
        assert!(matches!(result, Err(WarpProbeError::Timeout)), "presence must not change inactive policy");
        drop(registered);
    }
    let stale = Registration(register_protect_callback_versioned(Arc::new(Callback {
        failure: Some(io::ErrorKind::PermissionDenied),
    })));
    let current = Registration(register_protect_callback_versioned(Arc::new(Callback { failure: None })));
    assert!(!unregister_protect_callback_if(stale.0));
    let result = probe_endpoint_with_platform(request(endpoint), &required).await;
    assert!(matches!(result, Err(WarpProbeError::Timeout)), "current callback must remain registered");
    drop(current);
    drop(stale);
    let result = probe_endpoint_with_platform(request(endpoint), &required).await;
    assert!(matches!(result, Err(WarpProbeError::Io(error)) if error.kind() == io::ErrorKind::NotConnected));
}
