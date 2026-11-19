use ripdpi_diagnostics_contracts::util::active_scan_io_deadline;
use ripdpi_diagnostics_contracts::{
    ProbeDetail, ProbeResult, QuicResidualEvidence, QuicVersionCellEvidence, QuicVersionMatrixConfig,
    QuicVersionMatrixEvidence,
};
use ripdpi_diagnostics_transport::transport::{
    QuicCellStage, QuicInitialCodec, QuicMatrixLimit, QuicMatrixSni, QuicMatrixStop, QuicMatrixVersion, QuicReply,
    QuicVersionMatrixReport, QuicVersionMatrixRequest, QuicVersionStatus, TargetAddress, TransportConfig,
    TransportError, first_routable_address, resolve_addresses_with_cancel, run_quic_version_matrix,
};
use ripdpi_packets::{QuicProbeResponseKind, build_probe_quic_initial, validate_quic_probe_response};
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

pub const QUIC_VERSION_EVIDENCE_KEY: &str = "quicVersionEvidence";

const CELL_TIMEOUT: Duration = Duration::from_millis(1500);
const CELL_BYTE_BUDGET: usize = 8 * 1024;
const TOTAL_DEADLINE: Duration = Duration::from_secs(20);
const RESIDUAL_LIMIT: &str = "same_flow_or_endpoint_state_indistinguishable";

/// `ripdpi-packets` Initial builders and the DIA-07 response validator.
struct PacketsQuicCodec;

impl QuicInitialCodec for PacketsQuicCodec {
    fn build(&self, version: u32, sni: &str) -> Option<Vec<u8>> {
        build_probe_quic_initial(version, Some(sni))
    }

    fn classify(&self, request: &[u8], response: &[u8]) -> QuicReply {
        match validate_quic_probe_response(request, response) {
            Some(QuicProbeResponseKind::VersionNegotiation) => QuicReply::VersionNegotiation,
            Some(
                QuicProbeResponseKind::InitialCrypto
                | QuicProbeResponseKind::InitialAck
                | QuicProbeResponseKind::InitialOther
                | QuicProbeResponseKind::InitialClose,
            ) => QuicReply::ValidInitial,
            Some(QuicProbeResponseKind::Retry) | None => QuicReply::Other,
        }
    }
}

/// Run the QUIC v1/v2 Initial matrix against the one endpoint the user selected, on the direct path only.
/// Returns one row per version and a comparison row, plus a residual row when the user enabled it, or a
/// single `quic_version_matrix` row when the matrix cannot run.
pub fn run_quic_version_matrix_probe(
    config: &QuicVersionMatrixConfig,
    transport: &TransportConfig,
    raw_path: bool,
    cancel: &AtomicBool,
) -> Vec<ProbeResult> {
    // Acquire observes the scan owner's published cancellation flag.
    let cancelled = || cancel.load(Ordering::Acquire);
    if !raw_path || !matches!(transport, TransportConfig::Direct { .. }) {
        return vec![matrix_row(config, "UNSUPPORTED", "proxy_path")];
    }
    if config.validate().is_err() {
        return vec![matrix_row(config, "INCONCLUSIVE", "invalid_config")];
    }
    if cancelled() {
        return vec![matrix_row(config, "CANCELLED", "cancelled")];
    }
    let endpoint = match config.connect_ip.as_deref().map(str::parse::<IpAddr>) {
        Some(Ok(ip)) => SocketAddr::new(ip, QuicVersionMatrixConfig::PORT),
        _ => {
            let target = TargetAddress::Host(config.host.clone());
            match resolve_addresses_with_cancel(&target, QuicVersionMatrixConfig::PORT, &cancelled) {
                Ok(addresses) if !addresses.is_empty() => first_routable_address(&addresses).unwrap_or(addresses[0]),
                Err(_) if cancelled() => return vec![matrix_row(config, "CANCELLED", "cancelled")],
                _ => return vec![matrix_row(config, "INCONCLUSIVE", "dns_error")],
            }
        }
    };
    let mut total_deadline = TOTAL_DEADLINE;
    if let Some(deadline) = active_scan_io_deadline() {
        total_deadline = total_deadline.min(deadline.saturating_duration_since(Instant::now()));
    }
    let request = QuicVersionMatrixRequest {
        endpoint,
        control_sni: &config.control_sni,
        test_sni: &config.test_sni,
        cell_timeout: CELL_TIMEOUT,
        cell_byte_budget: CELL_BYTE_BUDGET,
        total_deadline,
        residual: config.residual_version.map(|version| match version {
            2 => QuicMatrixVersion::V2,
            _ => QuicMatrixVersion::V1,
        }),
    };
    match run_quic_version_matrix(&request, &PacketsQuicCodec, cancel) {
        Ok(report) => summarize(config, &report),
        Err(TransportError::Io(error)) if error.kind() == io::ErrorKind::PermissionDenied => {
            vec![matrix_row(config, "UNSUPPORTED", "protect")]
        }
        Err(TransportError::InvalidProbePayload) => vec![matrix_row(config, "INCONCLUSIVE", "initial_builder")],
        Err(_) => vec![matrix_row(config, "INCONCLUSIVE", "transport_error")],
    }
}

fn summarize(config: &QuicVersionMatrixConfig, report: &QuicVersionMatrixReport) -> Vec<ProbeResult> {
    let limits: Vec<String> = report.limits.iter().map(|limit| limit_token(*limit).into()).collect();
    let stop = report.stopped.map(|stop| match stop {
        QuicMatrixStop::Cancelled => "CANCELLED",
        QuicMatrixStop::DeadlineExceeded => "DEADLINE_EXCEEDED",
    });
    let mut rows = Vec::with_capacity(4);
    for (version, status) in [(QuicMatrixVersion::V1, report.v1), (QuicMatrixVersion::V2, report.v2)] {
        let cells = report
            .cells
            .iter()
            .filter(|cell| cell.version == version)
            .map(|cell| QuicVersionCellEvidence {
                sequence: u32::try_from(cell.sequence).unwrap_or(u32::MAX),
                repetition: u32::try_from(cell.repetition).unwrap_or(u32::MAX),
                sni: match cell.sni {
                    QuicMatrixSni::Control => "CONTROL",
                    QuicMatrixSni::Test => "TEST",
                }
                .into(),
                stage: stage_token(cell.outcome.stage).into(),
                bytes_sent: u32::try_from(cell.outcome.bytes_sent).unwrap_or(u32::MAX),
                bytes_received: u32::try_from(cell.outcome.bytes_received).unwrap_or(u32::MAX),
            })
            .collect();
        let (status, outcome) = match status {
            QuicVersionStatus::TestAnswered => ("TEST_ANSWERED", "quic_version_test_answered"),
            QuicVersionStatus::TestNotAnswered => ("TEST_NOT_ANSWERED", "quic_version_test_not_answered"),
            QuicVersionStatus::TestMixed => ("TEST_MIXED", "quic_version_test_mixed"),
            QuicVersionStatus::UnsupportedByEndpoint => {
                ("UNSUPPORTED_BY_ENDPOINT", "quic_version_unsupported_by_endpoint")
            }
            QuicVersionStatus::Inconclusive => ("INCONCLUSIVE", "quic_version_inconclusive"),
        };
        let probe_type = match version {
            QuicMatrixVersion::V1 => "quic_version_v1",
            QuicMatrixVersion::V2 => "quic_version_v2",
        };
        rows.push(row(
            config,
            probe_type,
            outcome,
            QuicVersionMatrixEvidence {
                status: status.into(),
                version: Some(version_token(version).into()),
                stop: stop.map(Into::into),
                limits: limits.clone(),
                cells,
                ..Default::default()
            },
        ));
    }
    let comparable = |status| {
        matches!(
            status,
            QuicVersionStatus::TestAnswered | QuicVersionStatus::TestNotAnswered | QuicVersionStatus::TestMixed
        )
    };
    let (status, outcome) = if report.version_difference {
        ("DIFFERENCE", "quic_version_difference_observed")
    } else if comparable(report.v1) && comparable(report.v2) {
        ("NO_DIFFERENCE", "quic_version_difference_not_observed")
    } else {
        ("INCONCLUSIVE", "quic_version_difference_inconclusive")
    };
    rows.push(row(
        config,
        "quic_version_comparison",
        outcome,
        QuicVersionMatrixEvidence {
            status: status.into(),
            stop: stop.map(Into::into),
            limits: limits.clone(),
            ..Default::default()
        },
    ));
    if let Some(residual) = &report.residual {
        let (status, outcome) = if residual.residual_state_suspected {
            ("SUSPECTED", "quic_version_residual_suspected")
        } else {
            ("NOT_OBSERVED", "quic_version_residual_not_observed")
        };
        let mut residual_limits = limits;
        residual_limits.push(RESIDUAL_LIMIT.into());
        rows.push(row(
            config,
            "quic_version_residual",
            outcome,
            QuicVersionMatrixEvidence {
                status: status.into(),
                version: Some(version_token(residual.version).into()),
                limits: residual_limits,
                residual: Some(QuicResidualEvidence {
                    test_stage: stage_token(residual.test.stage).into(),
                    control_stage: stage_token(residual.control.stage).into(),
                }),
                ..Default::default()
            },
        ));
    }
    rows
}

fn matrix_row(config: &QuicVersionMatrixConfig, status: &str, reason: &str) -> ProbeResult {
    let outcome = match status {
        "UNSUPPORTED" => "quic_version_unsupported",
        "CANCELLED" => "quic_version_cancelled",
        _ => "quic_version_matrix_inconclusive",
    };
    let evidence =
        QuicVersionMatrixEvidence { status: status.into(), reason: Some(reason.into()), ..Default::default() };
    row(config, "quic_version_matrix", outcome, evidence)
}

fn row(
    config: &QuicVersionMatrixConfig,
    probe_type: &str,
    outcome: &str,
    evidence: QuicVersionMatrixEvidence,
) -> ProbeResult {
    ProbeResult {
        probe_type: probe_type.into(),
        target: config.host.clone(),
        outcome: outcome.into(),
        details: vec![ProbeDetail {
            key: QUIC_VERSION_EVIDENCE_KEY.into(),
            value: evidence.to_detail_json().unwrap_or_default(),
        }],
    }
}

fn version_token(version: QuicMatrixVersion) -> &'static str {
    match version {
        QuicMatrixVersion::V1 => "V1",
        QuicMatrixVersion::V2 => "V2",
    }
}

fn stage_token(stage: QuicCellStage) -> &'static str {
    match stage {
        QuicCellStage::ValidInitial => "VALID_INITIAL",
        QuicCellStage::VersionNegotiationOnly => "VERSION_NEGOTIATION_ONLY",
        QuicCellStage::Other => "OTHER",
        QuicCellStage::Timeout => "TIMEOUT",
    }
}

fn limit_token(limit: QuicMatrixLimit) -> &'static str {
    match limit {
        QuicMatrixLimit::InitialStageOnly => "initial_stage_only",
        QuicMatrixLimit::LocalPortSeparationOnly => "local_port_separation_only",
    }
}

#[cfg(test)]
#[path = "quic_version_tests.rs"]
mod tests;
