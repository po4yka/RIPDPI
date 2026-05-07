use crate::connectivity::adapters::transport::RouteExperimentReport;
use crate::types::ProbeDetail;

#[inline(never)]
pub(super) fn push_detail(details: &mut Vec<ProbeDetail>, key: &str, value: String) {
    details.push(ProbeDetail { key: key.to_string(), value });
}

#[inline(never)]
pub(super) fn push_joined_string_detail(details: &mut Vec<ProbeDetail>, key: &str, values: &[String]) {
    push_detail(details, key, values.join("|"));
}

#[inline(never)]
pub(super) fn push_joined_str_detail(details: &mut Vec<ProbeDetail>, key: &str, values: &[&str]) {
    push_detail(details, key, values.join("|"));
}

#[inline(never)]
pub(super) fn append_route_details(
    details: &mut Vec<ProbeDetail>,
    prefix: &str,
    local_addr: Option<std::net::SocketAddr>,
    route_report: Option<&RouteExperimentReport>,
) {
    let key = |suffix: &str| {
        if prefix.is_empty() {
            suffix.to_string()
        } else {
            format!("{prefix}{suffix}")
        }
    };
    if let Some(addr) = local_addr {
        details.push(ProbeDetail { key: key("LocalAddress"), value: addr.to_string() });
        details.push(ProbeDetail { key: key("LocalPort"), value: addr.port().to_string() });
    }
    if let Some(route_report) = route_report {
        details.push(ProbeDetail { key: key("RouteSelectedBucket"), value: route_report.selected_bucket.to_string() });
        details.push(ProbeDetail {
            key: key("RouteSelectedBucketKind"),
            value: route_report.selected_bucket_kind.clone(),
        });
        details.push(ProbeDetail {
            key: key("RouteStableAttemptsRun"),
            value: route_report.stable_attempts_run.to_string(),
        });
        details.push(ProbeDetail {
            key: key("RouteDiversityAttemptsRun"),
            value: route_report.diversity_attempts_run.to_string(),
        });
        details.push(ProbeDetail { key: key("RouteSummary"), value: route_report.summary.clone() });
    }
}
