pub(super) fn quic_sample_quality(outcome: &str) -> usize {
    match outcome {
        "quic_initial_response" => 4,
        "quic_initial_ack"
        | "quic_initial_other"
        | "quic_initial_close"
        | "quic_retry"
        | "quic_version_negotiation" => 3,
        _ => 0,
    }
}
