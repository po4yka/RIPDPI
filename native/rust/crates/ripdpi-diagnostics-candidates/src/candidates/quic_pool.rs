use super::prelude::*;

pub fn build_quic_candidates(base_tcp: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let mut candidates = vec![with_quic_layout_family(
        candidate_spec_with_notes(
            "quic_multi_initial_realistic",
            "QUIC multi-initial realistic",
            "quic_multi_initial_realistic",
            build_quic_multi_initial_realistic_candidate(base_tcp),
            vec![
                "Sends multiple browser-like QUIC Initials to pressure parser state tracking",
                "Alternates Chrome-like and Firefox-like Initial layouts",
            ],
        ),
        "quic_multi_initial_realistic",
    )];
    candidates.push(with_quic_layout_family(
        candidate_spec_with_notes(
            "quic_sni_split",
            "QUIC SNI split",
            "quic_sni_split",
            build_quic_sni_split_candidate(base_tcp),
            vec!["Splits QUIC Initial at the authority boundary via the packetizer"],
        ),
        "quic_sni_split",
    ));
    candidates.push(with_quic_layout_family(
        candidate_spec_with_notes(
            "quic_crypto_split",
            "QUIC CRYPTO split",
            "quic_crypto_split",
            build_quic_crypto_split_candidate(base_tcp),
            vec!["Splits the QUIC CRYPTO payload into two packetizer-owned frame regions"],
        ),
        "quic_crypto_split",
    ));
    candidates.push(with_quic_layout_family(
        candidate_spec_with_notes(
            "quic_padding_ladder",
            "QUIC padding ladder",
            "quic_padding_ladder",
            build_quic_padding_ladder_candidate(base_tcp),
            vec!["Builds browser-like Initials with progressively larger padding envelopes"],
        ),
        "quic_padding_ladder",
    ));
    candidates.push(with_quic_layout_family(
        candidate_spec_with_notes(
            "quic_version_negotiation_decoy",
            "QUIC version negotiation decoy",
            "quic_version_negotiation_decoy",
            build_quic_version_negotiation_decoy_candidate(base_tcp),
            vec!["Injects a browser-like header-version decoy before the real Initial"],
        ),
        "quic_version_negotiation_decoy",
    ));
    candidates.push(with_quic_layout_family(
        candidate_spec_with_notes(
            "quic_fake_version",
            "QUIC fake version",
            "quic_fake_version",
            build_quic_fake_version_candidate(base_tcp),
            vec!["Sends a browser-like QUIC Initial with an unrecognized version field"],
        ),
        "quic_fake_version",
    ));
    candidates.push(with_quic_layout_family(
        candidate_spec_with_notes(
            "quic_dummy_prepend",
            "QUIC dummy prepend",
            "quic_dummy_prepend",
            build_quic_dummy_prepend_candidate(base_tcp),
            vec!["Prepends compact browser-like QUIC Initial decoys before the real Initial"],
        ),
        "quic_dummy_prepend",
    ));
    if supports_udp_ip_fragmentation() {
        candidates.push(candidate_spec_with_notes(
            "quic_ipfrag2",
            "QUIC IP fragmentation",
            "quic_ipfrag2",
            build_quic_ipfrag_candidate(base_tcp),
            vec!["VPN-only raw-socket fragmentation of the first QUIC Initial datagram"],
        ));
    }
    candidates.push(candidate_spec(
        "quic_disabled",
        "QUIC disabled",
        "quic_disabled",
        build_quic_candidate(base_tcp, false, "disabled"),
    ));
    candidates
}

pub fn build_full_matrix_quic_candidates(base_tcp: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let mut candidates = build_quic_candidates(base_tcp);
    if supports_udp_ip_fragmentation() {
        for (id, label, profile, note) in [
            (
                "quic_ipfrag2_hopbyhop",
                "QUIC IP fragmentation + Hop-by-Hop",
                "hopByHop",
                "Adds one Hop-by-Hop header before fragmenting the QUIC Initial datagram",
            ),
            (
                "quic_ipfrag2_hopbyhop2",
                "QUIC IP fragmentation + Hop-by-Hop2",
                "hopByHop2",
                "Adds the double-header Tier 2 IPv6 extension profile before fragmentation",
            ),
            (
                "quic_ipfrag2_destopt",
                "QUIC IP fragmentation + Dest Opt",
                "destOpt",
                "Adds one Destination Options header before fragmenting the QUIC Initial datagram",
            ),
            (
                "quic_ipfrag2_hopbyhop_destopt",
                "QUIC IP fragmentation + HBH + Dest Opt",
                "hopByHopDestOpt",
                "Adds both Hop-by-Hop and Destination Options headers before fragmentation",
            ),
        ] {
            candidates.push(candidate_spec_with_notes(
                id,
                label,
                "quic_ipfrag2_ipv6_ext",
                build_quic_ipfrag_candidate_with_ipv6_ext(base_tcp, profile),
                vec![note, "VPN-only raw-socket QUIC fragmentation variant for IPv6-capable paths"],
            ));
        }
    }
    candidates
}
