use std::collections::BTreeSet;

use crate::types::{Diagnosis, ProbeResult};

use super::failure_detail_value;

pub(crate) struct DiagnosisSink {
    diagnoses: Vec<Diagnosis>,
    seen: BTreeSet<String>,
}

impl DiagnosisSink {
    pub(crate) fn new() -> Self {
        Self { diagnoses: Vec::new(), seen: BTreeSet::new() }
    }

    pub(crate) fn push(&mut self, diagnosis: Diagnosis) {
        let key = format!("{}:{}", diagnosis.code, diagnosis.target.as_deref().unwrap_or("*"));
        if self.seen.insert(key) {
            self.diagnoses.push(diagnosis);
        }
    }

    pub(crate) fn contains_code(&self, codes: &[&str]) -> bool {
        self.diagnoses.iter().any(|diagnosis| codes.contains(&diagnosis.code.as_str()))
    }

    pub(crate) fn contains_code_for_target(&self, code: &str, target: &str) -> bool {
        self.diagnoses.iter().any(|diagnosis| diagnosis.code == code && diagnosis.target.as_deref() == Some(target))
    }

    pub(crate) fn into_vec(self) -> Vec<Diagnosis> {
        self.diagnoses
    }
}

pub(crate) fn diagnosis_evidence(result: &ProbeResult, keys: &[&str]) -> Vec<String> {
    keys.iter().filter_map(|key| failure_detail_value(result, key).map(|value| format!("{key}={value}"))).collect()
}

pub(crate) fn normalize_host(value: &str) -> String {
    value.trim().to_ascii_lowercase().trim_start_matches("www.").to_string()
}

pub(crate) fn is_timeout_error(value: &str) -> bool {
    value.contains("timed out") || value.contains("timeout") || value.contains("would block")
}

pub(crate) fn is_reset_error(value: &str) -> bool {
    value.contains("reset") || value.contains("broken pipe") || value.contains("aborted")
}

pub(crate) fn is_close_error(value: &str) -> bool {
    value.contains("unexpected eof") || value.contains("closed") || value.contains("close notify")
}

pub(crate) fn is_http_failure(value: &str) -> bool {
    !matches!(value, "not_run" | "http_ok")
}

pub(crate) fn is_tls_failure(value: &str) -> bool {
    !matches!(value, "not_run" | "tls_ok" | "tcp_connect_ok")
}

pub(crate) fn is_quic_failure(value: &str) -> bool {
    !matches!(value, "not_run" | "quic_initial_response" | "quic_response")
}
