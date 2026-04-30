mod common;
mod tcp;
mod udp;

use crate::transport::types::{RouteExperimentConfig, RouteExperimentReport};

pub(super) use common::route_identity;
pub(super) use tcp::connect_addresses_with_route_experiment;
pub(super) use udp::relay_udp_direct_with_route_experiment;

struct RouteAttemptTracker {
    stable_attempts: usize,
    stable_attempts_run: usize,
    diversity_attempts_run: usize,
    events: Vec<String>,
    last_error: Option<String>,
    fallback_error: &'static str,
}

impl RouteAttemptTracker {
    fn new(config: &RouteExperimentConfig, fallback_error: &'static str) -> Self {
        Self {
            stable_attempts: config.stable_flow_attempts.max(1),
            stable_attempts_run: 0,
            diversity_attempts_run: 0,
            events: Vec::new(),
            last_error: None,
            fallback_error,
        }
    }

    fn stable_attempts(&self) -> usize {
        self.stable_attempts
    }

    fn stable_success(&mut self, attempt_index: usize, bucket: usize) -> RouteExperimentReport {
        self.stable_attempts_run += 1;
        self.events.push(format!("stable#{attempt_index}:ok"));
        self.report(bucket, "stable")
    }

    fn stable_failure(&mut self, attempt_index: usize, error: String) {
        self.stable_attempts_run += 1;
        self.events.push(format!("stable#{attempt_index}:{error}"));
        self.last_error = Some(error);
    }

    fn diversity_success(&mut self, bucket: usize) -> RouteExperimentReport {
        self.diversity_attempts_run += 1;
        self.events.push(format!("bucket#{bucket}:ok"));
        self.report(bucket, "diversity")
    }

    fn diversity_failure(&mut self, bucket: usize, error: String) {
        self.diversity_attempts_run += 1;
        self.events.push(format!("bucket#{bucket}:{error}"));
        self.last_error = Some(error);
    }

    fn failure_summary(self) -> String {
        format!(
            "{}; route={}",
            self.last_error.unwrap_or_else(|| self.fallback_error.to_string()),
            self.events.join("|"),
        )
    }

    fn report(&self, selected_bucket: usize, selected_bucket_kind: &str) -> RouteExperimentReport {
        RouteExperimentReport {
            selected_bucket,
            selected_bucket_kind: selected_bucket_kind.to_string(),
            stable_attempts_run: self.stable_attempts_run,
            diversity_attempts_run: self.diversity_attempts_run,
            summary: self.events.join("|"),
        }
    }
}
