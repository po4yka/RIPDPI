use ripdpi_diagnostics_contracts::FieldFailureStage;
use ripdpi_failure_classifier::{ClassifiedFailure, FailureClass, FailureStage};

/// Maps a classified connection failure to its `client-field-report` failure stage. The mapping is
/// total: every class reports exactly one stage, and classes without a network stage report `Unknown`.
pub fn field_failure_stage(failure: &ClassifiedFailure) -> FieldFailureStage {
    match failure.class {
        FailureClass::DnsTampering | FailureClass::DnsResolutionFailure => FieldFailureStage::Dns,
        FailureClass::ConnectFailure | FailureClass::IpBlockSuspect => FieldFailureStage::TcpConnect,
        FailureClass::TcpReset => FieldFailureStage::TcpReset,
        FailureClass::TlsAlert | FailureClass::TlsHandshakeFailure => FieldFailureStage::TlsHandshake,
        FailureClass::QuicBreakage => FieldFailureStage::QuicHandshake,
        FailureClass::HttpBlockpage
        | FailureClass::Redirect
        | FailureClass::TuicVersionUnsupported
        | FailureClass::ShadowTlsVersionMismatch
        | FailureClass::MasqueH3TcpUnsupported => FieldFailureStage::ProtocolError,
        FailureClass::ConnectionFreeze | FailureClass::ConfirmGoodDpiSuspected => FieldFailureStage::IdleTimeout,
        FailureClass::SilentDrop => silent_drop_stage(failure.stage),
        FailureClass::Unknown | FailureClass::StrategyExecutionFailure | FailureClass::CapabilitySkipped => {
            FieldFailureStage::Unknown
        }
    }
}

/// A silent drop is reported at the stage where the traffic stopped.
fn silent_drop_stage(stage: FailureStage) -> FieldFailureStage {
    match stage {
        FailureStage::Dns => FieldFailureStage::Dns,
        FailureStage::Connect => FieldFailureStage::TcpConnect,
        FailureStage::TlsHandshake => FieldFailureStage::TlsHandshake,
        FailureStage::QuicProbe => FieldFailureStage::QuicHandshake,
        FailureStage::FirstWrite
        | FailureStage::FirstResponse
        | FailureStage::HttpResponse
        | FailureStage::PostHandshake
        | FailureStage::Relay => FieldFailureStage::IdleTimeout,
        FailureStage::Diagnostic => FieldFailureStage::Unknown,
    }
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use ripdpi_failure_classifier::{FailureAction, FailureEvidence};

    use super::*;

    /// The shared Rust/Kotlin mapping table; Kotlin `FieldFailureStageContractTest` reads the same file.
    const MAPPING: &str = include_str!("../../../../../../contract-fixtures/native_failure_stage_mapping.tsv");

    const ALL_CLASSES: [FailureClass; 19] = [
        FailureClass::Unknown,
        FailureClass::DnsTampering,
        FailureClass::DnsResolutionFailure,
        FailureClass::TcpReset,
        FailureClass::SilentDrop,
        FailureClass::TlsAlert,
        FailureClass::HttpBlockpage,
        FailureClass::QuicBreakage,
        FailureClass::Redirect,
        FailureClass::TlsHandshakeFailure,
        FailureClass::ConnectFailure,
        FailureClass::StrategyExecutionFailure,
        FailureClass::ConnectionFreeze,
        FailureClass::CapabilitySkipped,
        FailureClass::TuicVersionUnsupported,
        FailureClass::ShadowTlsVersionMismatch,
        FailureClass::IpBlockSuspect,
        FailureClass::MasqueH3TcpUnsupported,
        FailureClass::ConfirmGoodDpiSuspected,
    ];

    const ALL_STAGES: [FailureStage; 10] = [
        FailureStage::Dns,
        FailureStage::Connect,
        FailureStage::FirstWrite,
        FailureStage::FirstResponse,
        FailureStage::TlsHandshake,
        FailureStage::HttpResponse,
        FailureStage::QuicProbe,
        FailureStage::Diagnostic,
        FailureStage::Relay,
        FailureStage::PostHandshake,
    ];

    /// Fails to compile when a variant is added, so `ALL_CLASSES` and `ALL_STAGES` must be updated with it.
    const fn assert_listed(class: FailureClass, stage: FailureStage) {
        match class {
            FailureClass::Unknown
            | FailureClass::DnsTampering
            | FailureClass::DnsResolutionFailure
            | FailureClass::TcpReset
            | FailureClass::SilentDrop
            | FailureClass::TlsAlert
            | FailureClass::HttpBlockpage
            | FailureClass::QuicBreakage
            | FailureClass::Redirect
            | FailureClass::TlsHandshakeFailure
            | FailureClass::ConnectFailure
            | FailureClass::StrategyExecutionFailure
            | FailureClass::ConnectionFreeze
            | FailureClass::CapabilitySkipped
            | FailureClass::TuicVersionUnsupported
            | FailureClass::ShadowTlsVersionMismatch
            | FailureClass::IpBlockSuspect
            | FailureClass::MasqueH3TcpUnsupported
            | FailureClass::ConfirmGoodDpiSuspected => {}
        }
        match stage {
            FailureStage::Dns
            | FailureStage::Connect
            | FailureStage::FirstWrite
            | FailureStage::FirstResponse
            | FailureStage::TlsHandshake
            | FailureStage::HttpResponse
            | FailureStage::QuicProbe
            | FailureStage::Diagnostic
            | FailureStage::Relay
            | FailureStage::PostHandshake => {}
        }
    }

    /// `(class, stage or "*") -> failure_stage` rows of the shared table.
    fn mapping_rows() -> BTreeMap<(&'static str, &'static str), &'static str> {
        let mut rows = BTreeMap::new();
        for line in MAPPING.lines().filter(|line| !line.is_empty() && !line.starts_with('#')) {
            let columns: Vec<&str> = line.split('\t').collect();
            let [class, stage, field_stage] = columns[..] else { panic!("malformed mapping row {line:?}") };
            assert!(rows.insert((class, stage), field_stage).is_none(), "duplicate mapping row {line:?}");
        }
        rows
    }

    fn failure(class: FailureClass, stage: FailureStage) -> ClassifiedFailure {
        ClassifiedFailure { class, stage, action: FailureAction::None, evidence: FailureEvidence::new("evidence") }
    }

    #[test]
    fn every_class_and_stage_maps_as_the_shared_table_states() {
        let rows = mapping_rows();
        for class in ALL_CLASSES {
            for stage in ALL_STAGES {
                assert_listed(class, stage);
                let expected = rows
                    .get(&(class.as_str(), stage.as_str()))
                    .or_else(|| rows.get(&(class.as_str(), "*")))
                    .unwrap_or_else(|| panic!("no mapping row for {class:?} at {stage:?}"));
                assert_eq!(field_failure_stage(&failure(class, stage)).as_str(), *expected, "{class:?} at {stage:?}");
            }
        }
    }

    #[test]
    fn shared_table_names_only_native_codes() {
        let classes: Vec<&str> = ALL_CLASSES.iter().map(|class| class.as_str()).collect();
        let stages: Vec<&str> = ALL_STAGES.iter().map(|stage| stage.as_str()).collect();
        let field_stages: Vec<&str> = FieldFailureStage::ALL.iter().map(|stage| stage.as_str()).collect();
        for ((class, stage), field_stage) in mapping_rows() {
            assert!(classes.contains(&class), "unknown class {class}");
            assert!(stage == "*" || stages.contains(&stage), "unknown stage {stage}");
            assert!(field_stages.contains(&field_stage), "unknown failure_stage {field_stage}");
        }
    }

    #[test]
    fn evidence_text_does_not_change_the_stage() {
        let mut timeout_text = failure(FailureClass::TcpReset, FailureStage::Connect);
        timeout_text.evidence = FailureEvidence::new("tls handshake timed out");
        assert_eq!(field_failure_stage(&timeout_text), FieldFailureStage::TcpReset);
    }
}
