use super::*;
use ripdpi_diagnostics_transport::transport::{
    QUIC_MATRIX_LIMITS, QuicCellOutcome, QuicMatrixCell, QuicResidualCell, quic_version_difference, quic_version_status,
};

fn config() -> QuicVersionMatrixConfig {
    QuicVersionMatrixConfig {
        host: "quic.example".into(),
        connect_ip: Some("203.0.113.7".into()),
        control_sni: "control.example".into(),
        test_sni: "test.example".into(),
        residual_version: None,
    }
}

fn evidence(result: &ProbeResult) -> QuicVersionMatrixEvidence {
    let detail = result.details.iter().find(|detail| detail.key == QUIC_VERSION_EVIDENCE_KEY).unwrap();
    QuicVersionMatrixEvidence::from_detail_json(&detail.value).unwrap()
}

fn outcomes(rows: &[ProbeResult]) -> Vec<(&str, &str)> {
    rows.iter().map(|row| (row.probe_type.as_str(), row.outcome.as_str())).collect()
}

/// A full matrix where `stage(version, sni)` gives every repetition's stage.
fn report(
    stage: impl Fn(QuicMatrixVersion, QuicMatrixSni) -> QuicCellStage,
    residual: Option<QuicResidualCell>,
) -> QuicVersionMatrixReport {
    let mut cells = Vec::new();
    for repetition in 0..2 {
        for version in [QuicMatrixVersion::V1, QuicMatrixVersion::V2] {
            for sni in [QuicMatrixSni::Control, QuicMatrixSni::Test] {
                let outcome = QuicCellOutcome { stage: stage(version, sni), bytes_sent: 1200, bytes_received: 0 };
                let sequence = cells.len();
                cells.push(QuicMatrixCell { sequence, repetition, version, sni, local_port: 40000, outcome });
            }
        }
    }
    let v1 = quic_version_status(&cells, QuicMatrixVersion::V1);
    let v2 = quic_version_status(&cells, QuicMatrixVersion::V2);
    QuicVersionMatrixReport {
        cells,
        residual,
        stopped: None,
        v1,
        v2,
        version_difference: quic_version_difference(v1, v2),
        limits: QUIC_MATRIX_LIMITS,
    }
}

#[test]
fn proxy_path_is_an_unsupported_row_without_io() {
    let rows = run_quic_version_matrix_probe(
        &config(),
        &TransportConfig::Direct { route_experiment: None },
        false,
        &AtomicBool::new(false),
    );
    assert_eq!(outcomes(&rows), [("quic_version_matrix", "quic_version_unsupported")]);
    assert_eq!(rows[0].target, "quic.example");
    assert_eq!(evidence(&rows[0]).reason.as_deref(), Some("proxy_path"));
}

#[test]
fn cancelled_before_start_is_a_cancelled_row() {
    let rows = run_quic_version_matrix_probe(
        &config(),
        &TransportConfig::Direct { route_experiment: None },
        true,
        &AtomicBool::new(true),
    );
    assert_eq!(outcomes(&rows), [("quic_version_matrix", "quic_version_cancelled")]);
}

#[test]
fn v1_test_timeout_with_v2_answer_is_an_initial_stage_difference() {
    let rows = summarize(
        &config(),
        &report(
            |version, sni| match (version, sni) {
                (QuicMatrixVersion::V1, QuicMatrixSni::Test) => QuicCellStage::Timeout,
                _ => QuicCellStage::ValidInitial,
            },
            None,
        ),
    );
    assert_eq!(
        outcomes(&rows),
        [
            ("quic_version_v1", "quic_version_test_not_answered"),
            ("quic_version_v2", "quic_version_test_answered"),
            ("quic_version_comparison", "quic_version_difference_observed"),
        ]
    );
    let comparison = evidence(&rows[2]);
    assert_eq!(comparison.status, "DIFFERENCE");
    assert_eq!(comparison.limits, ["initial_stage_only", "local_port_separation_only"]);
    let v1 = evidence(&rows[0]);
    assert_eq!(v1.cells.iter().map(|cell| cell.sequence).collect::<Vec<_>>(), [0, 1, 4, 5]);
    assert_eq!(v1.cells[1].stage, "TIMEOUT");
    // The export carries roles only: no SNI, local port or timing.
    let json = rows.iter().map(|row| row.details[0].value.clone()).collect::<String>();
    for absent in ["test.example", "control.example", "40000", "elapsed", "localPort"] {
        assert!(!json.contains(absent), "{absent} in {json}");
    }
}

#[test]
fn version_negotiation_for_v2_is_unsupported_by_endpoint_not_a_difference() {
    let rows = summarize(
        &config(),
        &report(
            |version, _| match version {
                QuicMatrixVersion::V1 => QuicCellStage::ValidInitial,
                QuicMatrixVersion::V2 => QuicCellStage::VersionNegotiationOnly,
            },
            None,
        ),
    );
    assert_eq!(
        outcomes(&rows),
        [
            ("quic_version_v1", "quic_version_test_answered"),
            ("quic_version_v2", "quic_version_unsupported_by_endpoint"),
            ("quic_version_comparison", "quic_version_difference_inconclusive"),
        ]
    );
}

#[test]
fn control_timeout_is_inconclusive() {
    let rows = summarize(
        &config(),
        &report(
            |version, sni| match (version, sni) {
                (QuicMatrixVersion::V1, QuicMatrixSni::Control) => QuicCellStage::Timeout,
                (_, QuicMatrixSni::Test) => QuicCellStage::Timeout,
                _ => QuicCellStage::ValidInitial,
            },
            None,
        ),
    );
    assert_eq!(rows[0].outcome, "quic_version_inconclusive");
    assert_eq!(rows[2].outcome, "quic_version_difference_inconclusive");
}

#[test]
fn residual_row_is_separate_and_never_a_version_verdict() {
    let timeout = QuicCellOutcome { stage: QuicCellStage::Timeout, bytes_sent: 1200, bytes_received: 0 };
    let residual = QuicResidualCell {
        version: QuicMatrixVersion::V1,
        local_port: 40001,
        test: timeout,
        control: timeout,
        residual_state_suspected: true,
    };
    let rows = summarize(&config(), &report(|_, _| QuicCellStage::ValidInitial, Some(residual)));
    assert_eq!(rows[2].outcome, "quic_version_difference_not_observed");
    assert_eq!(rows[3].probe_type, "quic_version_residual");
    assert_eq!(rows[3].outcome, "quic_version_residual_suspected");
    let residual = evidence(&rows[3]);
    assert_eq!(residual.version.as_deref(), Some("V1"));
    assert!(residual.cells.is_empty());
    assert!(residual.limits.iter().any(|limit| limit == RESIDUAL_LIMIT));
    assert_eq!(residual.residual.unwrap().control_stage, "TIMEOUT");
}

#[test]
fn codec_builds_requested_versions_and_reads_version_negotiation() {
    let codec = PacketsQuicCodec;
    for version in [QuicMatrixVersion::V1, QuicMatrixVersion::V2] {
        let initial = codec.build(version.wire(), "test.example").unwrap();
        assert_eq!(initial[1..5], version.wire().to_be_bytes());
    }
    let request = codec.build(QuicMatrixVersion::V2.wire(), "test.example").unwrap();
    // RFC 9000 Version Negotiation: long header, version 0, echoed connection IDs, then v1.
    let dcid_len = usize::from(request[5]);
    let dcid = &request[6..6 + dcid_len];
    let scid_len = usize::from(request[6 + dcid_len]);
    let scid = &request[7 + dcid_len..7 + dcid_len + scid_len];
    let mut negotiation = vec![0x80, 0, 0, 0, 0];
    negotiation.push(u8::try_from(scid.len()).unwrap());
    negotiation.extend_from_slice(scid);
    negotiation.push(u8::try_from(dcid.len()).unwrap());
    negotiation.extend_from_slice(dcid);
    negotiation.extend_from_slice(&1u32.to_be_bytes());
    assert_eq!(codec.classify(&request, &negotiation), QuicReply::VersionNegotiation);
    assert_eq!(codec.classify(&request, b"noise"), QuicReply::Other);
}
