use serde::{Deserialize, Serialize};
use std::net::IpAddr;

/// One user-selected QUIC endpoint (UDP port 443) probed with v1 and v2 Initials for a control and a test SNI.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct QuicVersionMatrixConfig {
    pub host: String,
    /// IP literal to send to instead of resolving `host`.
    pub connect_ip: Option<String>,
    pub control_sni: String,
    pub test_sni: String,
    /// Run the same-socket residual cell for QUIC version 1 or 2. `None` keeps it disabled.
    pub residual_version: Option<u8>,
}

impl QuicVersionMatrixConfig {
    pub const PORT: u16 = 443;

    pub fn validate(&self) -> Result<(), &'static str> {
        let valid_host = |host: &str| super::Http3ProbeConfig { host: host.into(), ..Default::default() }.validate();
        // The SNIs go into the ClientHello, so both must be DNS names.
        let valid_sni = |sni: &str| valid_host(sni).is_ok() && sni.parse::<IpAddr>().is_err();
        if valid_host(&self.host).is_err()
            || self.connect_ip.as_deref().is_some_and(|ip| ip.parse::<IpAddr>().is_err())
            || !valid_sni(&self.control_sni)
            || !valid_sni(&self.test_sni)
            || self.control_sni.eq_ignore_ascii_case(&self.test_sni)
            || self.residual_version.is_some_and(|version| !matches!(version, 1 | 2))
        {
            return Err("invalid_config");
        }
        Ok(())
    }
}

/// Exported evidence of one QUIC version matrix row. It holds no SNI, local port or timing.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QuicVersionMatrixEvidence {
    /// Version row: `TEST_ANSWERED`, `TEST_NOT_ANSWERED`, `TEST_MIXED`, `UNSUPPORTED_BY_ENDPOINT` or `INCONCLUSIVE`.
    /// Comparison row: `DIFFERENCE`, `NO_DIFFERENCE` or `INCONCLUSIVE`. Residual row: `SUSPECTED` or `NOT_OBSERVED`.
    /// Matrix row: `UNSUPPORTED`, `CANCELLED` or `INCONCLUSIVE`.
    pub status: String,
    /// `V1` or `V2` on version and residual rows.
    pub version: Option<String>,
    /// Why a matrix row has no result, for example `proxy_path`, `protect` or `dns_error`.
    pub reason: Option<String>,
    /// `CANCELLED` or `DEADLINE_EXCEEDED` when the run stopped early.
    pub stop: Option<String>,
    pub limits: Vec<String>,
    /// Version row cells in execution order.
    pub cells: Vec<QuicVersionCellEvidence>,
    pub residual: Option<QuicResidualEvidence>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QuicVersionCellEvidence {
    /// Execution order across the whole matrix, starting at 0.
    pub sequence: u32,
    pub repetition: u32,
    /// `CONTROL` or `TEST`.
    pub sni: String,
    /// `VALID_INITIAL`, `VERSION_NEGOTIATION_ONLY`, `OTHER` or `TIMEOUT`.
    pub stage: String,
    pub bytes_sent: u32,
    pub bytes_received: u32,
}

/// Test SNI then control SNI on one socket. It cannot tell same-flow state from endpoint-wide state.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QuicResidualEvidence {
    pub test_stage: String,
    pub control_stage: String,
}

impl QuicVersionMatrixEvidence {
    pub fn to_detail_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }

    pub fn from_detail_json(value: &str) -> Result<Self, serde_json::Error> {
        serde_json::from_str(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid() -> QuicVersionMatrixConfig {
        QuicVersionMatrixConfig {
            host: "quic.example".into(),
            connect_ip: Some("203.0.113.7".into()),
            control_sni: "control.example".into(),
            test_sni: "test.example".into(),
            residual_version: Some(1),
        }
    }

    #[test]
    fn validates_endpoint_ip_snis_and_residual_version() {
        assert!(valid().validate().is_ok());
        assert!(QuicVersionMatrixConfig { connect_ip: None, residual_version: None, ..valid() }.validate().is_ok());
        for config in [
            QuicVersionMatrixConfig { host: String::new(), ..valid() },
            QuicVersionMatrixConfig { connect_ip: Some("quic.example".into()), ..valid() },
            QuicVersionMatrixConfig { control_sni: "192.0.2.1".into(), ..valid() },
            QuicVersionMatrixConfig { test_sni: "CONTROL.example".into(), ..valid() },
            QuicVersionMatrixConfig { residual_version: Some(3), ..valid() },
            QuicVersionMatrixConfig::default(),
        ] {
            assert!(config.validate().is_err(), "{config:?}");
        }
    }

    /// The exact JSON `QuicVersionMatrixTest` in core/diagnostics parses; a rename here must change both sides.
    #[test]
    fn evidence_and_config_wire_shape_match_the_kotlin_contract() {
        let evidence = QuicVersionMatrixEvidence {
            status: "TEST_NOT_ANSWERED".into(),
            version: Some("V1".into()),
            limits: vec!["initial_stage_only".into(), "local_port_separation_only".into()],
            cells: vec![
                QuicVersionCellEvidence {
                    sequence: 0,
                    repetition: 0,
                    sni: "CONTROL".into(),
                    stage: "VALID_INITIAL".into(),
                    bytes_sent: 1200,
                    bytes_received: 1252,
                },
                QuicVersionCellEvidence {
                    sequence: 1,
                    repetition: 0,
                    sni: "TEST".into(),
                    stage: "TIMEOUT".into(),
                    bytes_sent: 1200,
                    bytes_received: 0,
                },
            ],
            ..Default::default()
        };
        assert_eq!(
            evidence.to_detail_json().unwrap(),
            concat!(
                r#"{"status":"TEST_NOT_ANSWERED","version":"V1","reason":null,"stop":null,"#,
                r#""limits":["initial_stage_only","local_port_separation_only"],"cells":["#,
                r#"{"sequence":0,"repetition":0,"sni":"CONTROL","stage":"VALID_INITIAL","bytesSent":1200,"#,
                r#""bytesReceived":1252},{"sequence":1,"repetition":0,"sni":"TEST","stage":"TIMEOUT","#,
                r#""bytesSent":1200,"bytesReceived":0}],"residual":null}"#,
            )
        );
        let config: QuicVersionMatrixConfig = serde_json::from_str(
            r#"{"host":"quic.example","connectIp":"203.0.113.7","controlSni":"control.example","testSni":"test.example","residualVersion":1}"#,
        )
        .unwrap();
        assert_eq!(config.connect_ip.as_deref(), Some("203.0.113.7"));
        assert_eq!(config.residual_version, Some(1));
        assert!(config.validate().is_ok());
    }
}
