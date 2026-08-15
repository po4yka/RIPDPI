use std::sync::{Arc, atomic::AtomicBool};

use rustls::client::danger::ServerCertVerifier;

use crate::connectivity::ProbeExecutionContext;
use crate::engine::runtime::{CollectedStageOutcome, ExecutionPlan, ExecutionStageId, ExecutionStageRunner};
use crate::types::{DnsTarget, ProbeDetail, ProbeResult};

use super::support::{ConnectivityProbeFamily, collect_family_steps, target_count};

/// DoH-JSON resolver survey stage.
///
/// Registered so the vendor JSON DoH APIs surface as a first-class engine
/// stage / probe family alongside the wire DoH path (DNS stage). The probe
/// itself currently emits a deterministic, offline placeholder result: the
/// live JSON survey lives in `ripdpi-diagnostics-probes::doh_json_survey`
/// (`DohJsonSurveyRunner`, async, generic over `DohHttpClient`), and wiring a
/// concrete HTTP client through the synchronous connectivity probe path is the
/// remaining follow-up. The stage runs only when a `DohJsonSurvey`-family probe
/// task is requested, so it does not perturb existing scan profiles.
pub(in crate::engine::runners) struct DohJsonSurveyRunner;

struct DohJsonSurveyFamily;

impl ConnectivityProbeFamily for DohJsonSurveyFamily {
    type Target = DnsTarget;

    const PHASE: &'static str = "doh_json";
    const ARTIFACT_SOURCE: &'static str = "doh_json_survey";

    fn targets(plan: &ExecutionPlan) -> Vec<Self::Target> {
        plan.request.dns_targets.clone()
    }

    fn message(target: &Self::Target) -> String {
        format!("DoH-JSON {}", target.domain)
    }

    fn run_probe(
        target: &Self::Target,
        _plan: &ExecutionPlan,
        _probe_context: &ProbeExecutionContext,
        _cancel: &std::sync::atomic::AtomicBool,
        _tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> ProbeResult {
        // Offline placeholder. Surfaces the JSON resolver panel that the live
        // survey probes; the synchronous HTTP wiring is the documented
        // follow-up (see crate `ripdpi-diagnostics-probes::doh_json_survey`).
        ProbeResult {
            probe_type: "doh_json_survey".to_string(),
            target: target.domain.clone(),
            outcome: "doh_json_survey_pending".to_string(),
            details: vec![
                ProbeDetail {
                    key: "status".to_string(),
                    value: "registered; live JSON survey HTTP wiring is a follow-up".to_string(),
                },
                ProbeDetail {
                    key: "jsonResolvers".to_string(),
                    value: "google,cloudflare,adguard,alibaba".to_string(),
                },
            ],
        }
    }
}

impl ExecutionStageRunner for DohJsonSurveyRunner {
    fn id(&self) -> ExecutionStageId {
        ExecutionStageId::DohJsonSurvey
    }

    fn phase(&self) -> &'static str {
        DohJsonSurveyFamily::PHASE
    }

    fn total_steps(&self, plan: &ExecutionPlan) -> usize {
        target_count::<DohJsonSurveyFamily>(plan)
    }

    fn run_collecting(
        &self,
        plan: &ExecutionPlan,
        cancel: &AtomicBool,
        tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    ) -> CollectedStageOutcome {
        collect_family_steps::<DohJsonSurveyFamily>(plan, cancel, tls_verifier)
    }
}
