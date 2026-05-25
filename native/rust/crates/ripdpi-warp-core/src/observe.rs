/// A single TCP upstream-connect observation for quality telemetry.
///
/// For the WARP virtual-TCP path (smoltcp kernel-bypass), there is no
/// measurable RTT at the SOCKS layer — `rtt_ms` is always `0`. The field
/// is present for API symmetry with `ripdpi-relay-core::TcpConnectObservation`
/// so adapter code can use the same `QualityWindow::record` call path.
/// `succeeded` reflects whether `handle_socks_client` returned `Ok(())`.
///
/// Cancel-safety: the observation is a plain `Copy` value; caller drops it on
/// cancellation with no side-effect.
#[derive(Debug, Clone, Copy)]
pub struct TcpConnectObservation {
    /// Elapsed milliseconds from connect-start to first upstream byte.
    /// Always `0` for virtual WARP TCP (smoltcp); present for API parity.
    pub rtt_ms: u64,
    /// `true` if the SOCKS session completed without error, `false` otherwise.
    pub succeeded: bool,
}
