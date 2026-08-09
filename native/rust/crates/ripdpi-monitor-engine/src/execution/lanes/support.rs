use crate::candidates::StrategyCandidateSpec;
use crate::types::ProbeDetail;

pub(super) fn candidate_probe_details(
    candidate: &StrategyCandidateSpec,
    protocol: &str,
    latency_ms: u64,
) -> Vec<ProbeDetail> {
    vec![
        ProbeDetail { key: "candidateId".to_string(), value: candidate.id.to_string() },
        ProbeDetail { key: "candidateLabel".to_string(), value: candidate.label.to_string() },
        ProbeDetail { key: "candidateFamily".to_string(), value: candidate.family.to_string() },
        ProbeDetail { key: "protocol".to_string(), value: protocol.to_string() },
        ProbeDetail { key: "latencyMs".to_string(), value: latency_ms.to_string() },
    ]
}

pub(super) fn push_http_response_details(details: &mut Vec<ProbeDetail>, h3_advertised: bool, ttfb_ms: Option<u64>) {
    details.push(ProbeDetail { key: "h3Advertised".to_string(), value: h3_advertised.to_string() });
    if let Some(ttfb_ms) = ttfb_ms {
        details.push(ProbeDetail { key: "ttfbMs".to_string(), value: ttfb_ms.to_string() });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn http_probe_exports_response_details() {
        let mut details = Vec::new();

        push_http_response_details(&mut details, true, Some(23));

        assert_eq!(details.len(), 2);
        assert_eq!(details[0].key, "h3Advertised");
        assert_eq!(details[0].value, "true");
        assert_eq!(details[1].key, "ttfbMs");
        assert_eq!(details[1].value, "23");
    }
}
