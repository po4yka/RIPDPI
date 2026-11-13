use ripdpi_config::TcpTlsRandRecPayload;
use serde_json::{Value, json};

use crate::{ProxyConfigError, parse_proxy_config_json, runtime_config_from_payload};

fn parse_tlsrandrec_fields(fields: Value, rotation: bool) -> Result<TcpTlsRandRecPayload, ProxyConfigError> {
    let mut step = json!({ "kind": "tlsrandrec", "marker": "sniext+4" });
    step.as_object_mut().expect("step object").extend(fields.as_object().expect("fragment fields").clone());
    let chains = if rotation {
        json!({ "tcpSteps": [], "udpSteps": [], "tcpRotation": { "candidates": [{ "tcpSteps": [step] }] } })
    } else {
        json!({ "tcpSteps": [step], "udpSteps": [] })
    };
    let payload = parse_proxy_config_json(&json!({ "kind": "ui", "schemaVersion": 2, "chains": chains }).to_string())?;
    let config = runtime_config_from_payload(payload)?;
    let chain = if rotation {
        &config.groups[0].actions.rotation_policy.as_ref().expect("rotation policy").candidates[0].tcp_chain
    } else {
        &config.groups[0].actions.tcp_chain
    };
    Ok(chain[0].tls_randrec_payload().expect("TLS random-record payload"))
}

#[test]
fn tlsrandrec_rejects_invalid_positive_fragment_counts_in_primary_and_rotation_chains() {
    for rotation in [false, true] {
        for count in [1, 17, 1485, i32::MAX] {
            let error = parse_tlsrandrec_fields(
                json!({ "fragmentCount": count, "minFragmentSize": 16, "maxFragmentSize": 96 }),
                rotation,
            )
            .expect_err("invalid fragment count");

            let field = if rotation { "chains.tcpRotation.candidates[0].tcpSteps" } else { "chains.tcpSteps" };
            assert!(error.to_string().contains(field), "{error}");
            assert!(error.to_string().contains("fragmentCount"), "{error}");
        }
    }
}

#[test]
fn tlsrandrec_rejects_invalid_fragment_size_bounds_in_primary_and_rotation_chains() {
    for rotation in [false, true] {
        for (min, max) in [(4097, 4097), (16, 4097), (i32::MAX, i32::MAX), (16, i32::MAX), (80, 16), (4096, 0)] {
            let error = parse_tlsrandrec_fields(
                json!({ "fragmentCount": 4, "minFragmentSize": min, "maxFragmentSize": max }),
                rotation,
            )
            .expect_err("invalid fragment size bounds");

            let field = if rotation { "chains.tcpRotation.candidates[0].tcpSteps" } else { "chains.tcpSteps" };
            assert!(error.to_string().contains(field), "{error}");
            assert!(error.to_string().contains("FragmentSize"), "{error}");
        }
    }
}

#[test]
fn tlsrandrec_preserves_valid_fragment_boundaries_in_primary_and_rotation_chains() {
    for rotation in [false, true] {
        for (count, min, max) in [(2, 1, 1), (16, 4096, 4096), (4, 16, 96)] {
            let payload = parse_tlsrandrec_fields(
                json!({ "fragmentCount": count, "minFragmentSize": min, "maxFragmentSize": max }),
                rotation,
            )
            .expect("valid fragment fields");

            assert_eq!(
                payload,
                TcpTlsRandRecPayload { fragment_count: count, min_fragment_size: min, max_fragment_size: max }
            );
        }
    }
}

#[test]
fn tlsrandrec_normalizes_absent_and_nonpositive_fragment_fields_before_validation() {
    for rotation in [false, true] {
        for fields in [
            json!({}),
            json!({ "fragmentCount": 0, "minFragmentSize": 0, "maxFragmentSize": 0 }),
            json!({ "fragmentCount": -1, "minFragmentSize": -1, "maxFragmentSize": -1 }),
        ] {
            let payload = parse_tlsrandrec_fields(fields, rotation).expect("default fragment fields");

            assert_eq!(
                payload,
                TcpTlsRandRecPayload { fragment_count: 4, min_fragment_size: 16, max_fragment_size: 96 }
            );
        }
    }
}
