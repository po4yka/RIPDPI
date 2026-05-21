//! TCP fat-header classifier wrapped as a Probe.
//!
//! Pure offline classifier for the TCP "fat header" stage: a probe that
//! pushes a large (≈16 KiB) TLS `ClientHello`-shaped record over a TCP
//! connection to detect DPI middleboxes that cut the stream at a byte
//! threshold, freeze it, or reset it.
//!
//! ## Migration anchor
//!
//! This probe mirrors the scheduled connectivity stage emitted by
//! `classify_fat_header_outcome` in the `ripdpi-diagnostics-fat-header`
//! crate, surfaced through `run_tcp_probe` with `probe_type:
//! "tcp_fat_header"`. The [`TcpFatHeaderStatus::scheduled_outcome`] strings
//! are the exact `ProbeResult.outcome` values the previous scheduled runner
//! produced; they are the equivalence anchor for the Probe-trait migration.
//!
//! The probe captures a [`TcpFatHeaderStatus`] at construction and
//! [`Probe::run`] performs no I/O.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;

use crate::{Probe, ProbeContext, ProbeOutcome, ProbeVerdict};

/// Stable probe identifier.
pub const TCP_FAT_HEADER_PROBE_ID: &str = "tcp_fat_header";

/// Captured outcome of a TCP fat-header probe attempt.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TcpFatHeaderStatus {
    /// The full fat header was delivered and the upstream responded.
    /// Scheduled `ProbeResult.outcome`: `"tcp_fat_header_ok"`.
    Success,
    /// The stream was cut at a fixed byte threshold (≈16 KiB) — the
    /// classic TSPU 16 KiB ceiling.
    /// Scheduled `ProbeResult.outcome`: `"tcp_16kb_blocked"`.
    ThresholdCutoff,
    /// The connection delivered the threshold bytes then froze without a
    /// reset — silent stall after the cutoff point.
    /// Scheduled `ProbeResult.outcome`: `"tcp_freeze_after_threshold"`.
    FreezeAfterThreshold,
    /// The connection was torn down with a TCP RST.
    /// Scheduled `ProbeResult.outcome`: `"tcp_reset"`.
    Reset,
    /// The probe timed out waiting for any response.
    /// Scheduled `ProbeResult.outcome`: `"tcp_timeout"`.
    Timeout,
    /// The underlying TCP connect never completed.
    /// Scheduled `ProbeResult.outcome`: `"tcp_connect_failed"`.
    ConnectFailed,
    /// The TLS handshake on top of the connection failed.
    /// Scheduled `ProbeResult.outcome`: `"tls_handshake_failed"`.
    HandshakeFailed,
}

impl TcpFatHeaderStatus {
    /// Every variant of [`TcpFatHeaderStatus`], in declaration order.
    pub const ALL: &'static [Self] = &[
        Self::Success,
        Self::ThresholdCutoff,
        Self::FreezeAfterThreshold,
        Self::Reset,
        Self::Timeout,
        Self::ConnectFailed,
        Self::HandshakeFailed,
    ];

    /// The exact `ProbeResult.outcome` string the upstream scheduled
    /// runner emits for this condition. This is the migration's
    /// equivalence anchor — do not change without updating the scheduled
    /// `classify_fat_header_outcome` source in lockstep.
    pub fn scheduled_outcome(self) -> &'static str {
        match self {
            Self::Success => "tcp_fat_header_ok",
            Self::ThresholdCutoff => "tcp_16kb_blocked",
            Self::FreezeAfterThreshold => "tcp_freeze_after_threshold",
            Self::Reset => "tcp_reset",
            Self::Timeout => "tcp_timeout",
            Self::ConnectFailed => "tcp_connect_failed",
            Self::HandshakeFailed => "tls_handshake_failed",
        }
    }
}

/// Pure [`Probe`] adapter for the TCP fat-header stage.
#[derive(Debug, Clone)]
pub struct TcpFatHeaderProbe {
    endpoint: String,
    status: TcpFatHeaderStatus,
}

impl TcpFatHeaderProbe {
    /// Construct from a target endpoint identifier (for evidence) and the
    /// captured fat-header outcome.
    pub fn new(endpoint: String, status: TcpFatHeaderStatus) -> Self {
        Self { endpoint, status }
    }

    /// Endpoint identifier passed to [`Self::new`]. Exposed so consumers
    /// can correlate the probe outcome with the requested endpoint.
    pub fn endpoint(&self) -> &str {
        &self.endpoint
    }
}

impl Probe for TcpFatHeaderProbe {
    fn id(&self) -> &'static str {
        TCP_FAT_HEADER_PROBE_ID
    }

    fn family(&self) -> ProbeTaskFamily {
        ProbeTaskFamily::Tcp
    }

    fn run(&self, _ctx: &ProbeContext) -> ProbeOutcome {
        let verdict = classify_verdict(self.status);
        ProbeOutcome { probe_id: self.id(), family: self.family(), verdict }
    }
}

fn classify_verdict(status: TcpFatHeaderStatus) -> ProbeVerdict {
    match status {
        TcpFatHeaderStatus::Success => ProbeVerdict::Pass,
        TcpFatHeaderStatus::ThresholdCutoff => ProbeVerdict::Fail { class: "tcp-16kb-blocked".to_string() },
        TcpFatHeaderStatus::FreezeAfterThreshold => {
            ProbeVerdict::Fail { class: "tcp-freeze-after-threshold".to_string() }
        }
        TcpFatHeaderStatus::Reset => ProbeVerdict::Fail { class: "tcp-reset".to_string() },
        TcpFatHeaderStatus::Timeout => ProbeVerdict::Fail { class: "tcp-timeout".to_string() },
        TcpFatHeaderStatus::ConnectFailed => ProbeVerdict::Fail { class: "tcp-connect-failed".to_string() },
        TcpFatHeaderStatus::HandshakeFailed => ProbeVerdict::Fail { class: "tls-handshake-failed".to_string() },
    }
}
