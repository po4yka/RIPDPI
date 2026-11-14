use crate::{parse_proxy_config_json, runtime_config_from_payload};
use serde_json::json;

#[test]
fn cid_churn_config_count_and_chain_total_are_bounded() {
    for counts in [vec![0], vec![1], vec![64], vec![32, 32]] {
        let steps = counts.iter().map(|count| json!({"kind": "quic_cid_churn", "count": count})).collect::<Vec<_>>();
        let payload = parse_proxy_config_json(
            &json!({"kind": "ui", "schemaVersion": 2, "chains": {"udpSteps": steps}}).to_string(),
        )
        .expect("JSON payload");
        let config = runtime_config_from_payload(payload).expect("bounded chain");
        assert_eq!(config.groups[0].actions.udp_chain.iter().map(|step| step.count).collect::<Vec<_>>(), counts);
    }
    for counts in [vec![-1], vec![65], vec![i32::MAX], vec![32, 33], vec![64, i32::MAX]] {
        let steps = counts.iter().map(|count| json!({"kind": "quic_cid_churn", "count": count})).collect::<Vec<_>>();
        let payload = parse_proxy_config_json(
            &json!({"kind": "ui", "schemaVersion": 2, "chains": {"udpSteps": steps}}).to_string(),
        )
        .expect("JSON payload");
        assert!(runtime_config_from_payload(payload).is_err(), "count(s) {counts:?} must be rejected");
    }
}
