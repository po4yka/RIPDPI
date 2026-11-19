use serde::{Deserialize, Serialize};

/// Version of the vendored `client-field-report` contract (its `x-contract-version`).
pub const CLIENT_FIELD_REPORT_CONTRACT_VERSION: u32 = 1;

/// The `failure_stage` of a `client-field-report` v1 bucket. The serialized values are the schema
/// enum and equal the Kotlin `FieldFailureStage.wireValue` set.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FieldFailureStage {
    Dns,
    TcpConnect,
    TcpReset,
    TlsHandshake,
    RealityAuth,
    QuicHandshake,
    ProxyAuth,
    ProtocolError,
    IdleTimeout,
    Throttled,
    Unknown,
}

impl FieldFailureStage {
    pub const ALL: [Self; 11] = [
        Self::Dns,
        Self::TcpConnect,
        Self::TcpReset,
        Self::TlsHandshake,
        Self::RealityAuth,
        Self::QuicHandshake,
        Self::ProxyAuth,
        Self::ProtocolError,
        Self::IdleTimeout,
        Self::Throttled,
        Self::Unknown,
    ];

    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Dns => "dns",
            Self::TcpConnect => "tcp_connect",
            Self::TcpReset => "tcp_reset",
            Self::TlsHandshake => "tls_handshake",
            Self::RealityAuth => "reality_auth",
            Self::QuicHandshake => "quic_handshake",
            Self::ProxyAuth => "proxy_auth",
            Self::ProtocolError => "protocol_error",
            Self::IdleTimeout => "idle_timeout",
            Self::Throttled => "throttled",
            Self::Unknown => "unknown",
        }
    }
}

#[cfg(test)]
mod tests {
    use std::fs;

    use golden_test_support::repo_root;
    use serde_json::Value;

    use super::*;

    const CONTRACT_DIR: &str = "core/data/src/test/resources/contract";

    fn contract(name: &str) -> Value {
        let path = repo_root().join(CONTRACT_DIR).join(name);
        serde_json::from_str(&fs::read_to_string(&path).expect("vendored contract")).expect("contract json")
    }

    /// The bucket rule the schema states: a stage is present exactly on failure and is a known value.
    fn bucket_stage_is_valid(bucket: &Value) -> bool {
        match (bucket["outcome"].as_str(), bucket.get("failure_stage")) {
            (Some("failure"), Some(stage)) => serde_json::from_value::<FieldFailureStage>(stage.clone()).is_ok(),
            (Some("success"), None) => true,
            _ => false,
        }
    }

    fn buckets(report: &Value) -> &Vec<Value> {
        report["buckets"].as_array().expect("buckets")
    }

    #[test]
    fn contract_version_matches_vendored_schema() {
        let schema = contract("client-field-report.schema.json");
        assert_eq!(schema["x-contract-version"].as_u64(), Some(u64::from(CLIENT_FIELD_REPORT_CONTRACT_VERSION)));
        assert_eq!(
            schema["properties"]["schema_version"]["const"].as_u64(),
            Some(u64::from(CLIENT_FIELD_REPORT_CONTRACT_VERSION))
        );
    }

    #[test]
    fn enum_values_equal_vendored_schema_enum() {
        let schema = contract("client-field-report.schema.json");
        let schema_values: Vec<&str> = schema["$defs"]["bucket"]["properties"]["failure_stage"]["enum"]
            .as_array()
            .expect("failure_stage enum")
            .iter()
            .map(|value| value.as_str().expect("string"))
            .collect();
        let ours: Vec<&str> = FieldFailureStage::ALL.iter().map(|stage| stage.as_str()).collect();
        assert_eq!(ours, schema_values);
        for stage in FieldFailureStage::ALL {
            assert_eq!(serde_json::to_value(stage).expect("serialize"), Value::from(stage.as_str()));
        }
    }

    #[test]
    fn golden_report_stages_decode() {
        let golden = contract("client-field-report.golden.json");
        let stages: Vec<FieldFailureStage> = buckets(&golden)
            .iter()
            .inspect(|bucket| assert!(bucket_stage_is_valid(bucket), "invalid bucket {bucket}"))
            .filter_map(|bucket| bucket.get("failure_stage"))
            .map(|stage| serde_json::from_value(stage.clone()).expect("stage"))
            .collect();
        assert_eq!(stages, vec![FieldFailureStage::TlsHandshake, FieldFailureStage::QuicHandshake]);
    }

    #[test]
    fn negative_stage_fixtures_are_rejected() {
        for name in
            ["report-unknown-failure-stage.json", "report-failure-without-stage.json", "report-success-with-stage.json"]
        {
            let report = contract(&format!("negative/{name}"));
            assert!(buckets(&report).iter().any(|bucket| !bucket_stage_is_valid(bucket)), "{name} must be rejected");
        }
    }
}
