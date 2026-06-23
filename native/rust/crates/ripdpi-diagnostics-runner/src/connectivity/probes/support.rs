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
        if prefix.is_empty() { suffix.to_string() } else { format!("{prefix}{suffix}") }
    };
    if let Some(addr) = local_addr {
        // Privacy (network-fingerprint-privacy.md): the device's own bound source IP
        // is a forbidden identifier in any exported diagnostics artifact, and this
        // ProbeDetail list is serialized and returned across JNI (a support-bundle /
        // UI sink). Emit only the ephemeral source port (not device-identifying);
        // never the address itself.
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

#[cfg(test)]
mod tests {
    use super::append_route_details;
    use std::net::SocketAddr;

    /// Privacy regression (audit F15): `append_route_details` must never surface the
    /// device's own bound source IP into the exported ProbeDetail list — that list is
    /// serialized to JSON and returned across JNI (a support-bundle / UI sink), and
    /// device LAN/WAN IPs are forbidden there per network-fingerprint-privacy.md. The
    /// non-identifying ephemeral source port is still emitted.
    #[test]
    fn append_route_details_omits_the_device_source_ip() {
        let device_addr: SocketAddr = "192.168.1.42:54321".parse().expect("valid test addr");
        let mut details = Vec::new();
        append_route_details(&mut details, "gateway", Some(device_addr), None);

        assert!(
            !details.iter().any(|d| d.key.contains("LocalAddress")),
            "no LocalAddress detail may be emitted: {details:?}",
        );
        assert!(
            !details.iter().any(|d| d.value.contains("192.168.1.42")),
            "no ProbeDetail value may carry the device source IP: {details:?}",
        );
        assert!(
            details.iter().any(|d| d.key == "gatewayLocalPort" && d.value == "54321"),
            "the non-identifying source port detail must still be emitted: {details:?}",
        );
    }
}
