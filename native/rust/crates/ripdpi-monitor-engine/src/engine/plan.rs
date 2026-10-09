mod connectivity_order;

use crate::transport::TransportConfig;
use crate::types::{ScanKind, ScanPathMode, ScanRequest};

use super::runtime::{ExecutionPlan, ExecutionStageId};
use super::strategy_plan::build_strategy_execution_plan;

pub(super) fn build_execution_plan(
    session_id: String,
    request: ScanRequest,
    started_at: u64,
    transport: TransportConfig,
) -> Result<ExecutionPlan, String> {
    let strategy = if matches!(request.kind, ScanKind::StrategyProbe) {
        Some(build_strategy_execution_plan(&session_id, &request)?)
    } else {
        None
    };
    let stage_order = match request.kind {
        ScanKind::Connectivity => connectivity_stage_order(&request),
        ScanKind::StrategyProbe => strategy_stage_order(&request),
    };
    let runtime_context = strategy.as_ref().and_then(|plan| plan.runtime_context.as_ref());
    let probe_context =
        crate::connectivity::ProbeExecutionContext::from_runtime_context(transport.clone(), runtime_context)?;
    Ok(ExecutionPlan {
        session_id,
        request,
        started_at,
        total_steps: 0,
        transport,
        probe_context,
        stage_order,
        strategy,
    })
}

pub(super) fn strategy_stage_order(request: &ScanRequest) -> Vec<ExecutionStageId> {
    let mut stages = vec![ExecutionStageId::Environment];
    if matches!(request.path_mode, ScanPathMode::InPath)
        && request.in_path_route.is_some()
        && !request.domain_targets.is_empty()
    {
        stages.push(ExecutionStageId::Web);
    }
    stages.push(ExecutionStageId::StrategyDnsBaseline);
    if request.confirm_good_dpi_evidence.is_some() {
        stages.push(ExecutionStageId::StrategyQuicCandidates);
        stages.push(ExecutionStageId::StrategyTcpCandidates);
    } else {
        stages.push(ExecutionStageId::StrategyTcpCandidates);
        stages.push(ExecutionStageId::StrategyQuicCandidates);
    }
    stages.push(ExecutionStageId::StrategyConnectionConcurrency);
    stages.push(ExecutionStageId::StrategyRecommendation);
    stages
}

pub(super) fn connectivity_stage_order(request: &ScanRequest) -> Vec<ExecutionStageId> {
    connectivity_order::connectivity_stage_order(request)
}

#[cfg(test)]
mod ip_family_tests {
    use super::*;
    #[test]
    fn absent_configuration_never_schedules_ip_family_even_with_task() {
        let mut request:ScanRequest=serde_json::from_str(r#"{"profileId":"test","displayName":"test","pathMode":"RAW_PATH","domainTargets":[],"dnsTargets":[],"tcpTargets":[],"whitelistSni":[]}"#).unwrap();
        assert!(!connectivity_stage_order(&request).contains(&ExecutionStageId::IpFamily));
        request.probe_tasks.push(crate::types::ProbeTask {
            family: crate::types::ProbeTaskFamily::IpFamily,
            target_id: "ip-family".into(),
            label: "IP family".into(),
        });
        assert!(!connectivity_stage_order(&request).contains(&ExecutionStageId::IpFamily));
        request.ip_family_probe = Some(crate::types::IpFamilyProbeConfig {
            version: 1,
            ipv4_address: "1.1.1.1".into(),
            ipv6_address: "2606:4700:4700::1111".into(),
            port: 443,
            timeout_ms: 1500,
        });
        assert_eq!(connectivity_stage_order(&request), [ExecutionStageId::Environment, ExecutionStageId::IpFamily]);
    }
}

#[cfg(test)]
mod http3_tests {
    use super::*;
    #[test]
    fn http3_requires_explicit_task_and_configuration() {
        let mut request: ScanRequest = serde_json::from_str(r#"{"profileId":"test","displayName":"test","pathMode":"RAW_PATH","domainTargets":[],"dnsTargets":[],"tcpTargets":[],"whitelistSni":[]}"#).unwrap();
        assert!(!connectivity_stage_order(&request).contains(&ExecutionStageId::Http3));
        request.probe_tasks = vec![crate::types::ProbeTask {
            family: crate::types::ProbeTaskFamily::Http3,
            target_id: "http3".into(),
            label: "HTTP/3".into(),
        }];
        assert!(!connectivity_stage_order(&request).contains(&ExecutionStageId::Http3));
        request.http3_probe = Some(crate::types::Http3ProbeConfig::default());
        assert!(connectivity_stage_order(&request).contains(&ExecutionStageId::Http3));
        request.probe_tasks.clear();
        assert!(!connectivity_stage_order(&request).contains(&ExecutionStageId::Http3));
    }
}
