use crate::candidates::prelude::*;

pub fn build_full_matrix_tcp_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let mut candidates = build_tcp_candidates(base);
    candidates.push(candidate_spec_with_notes(
        "fake_rst",
        "Fake RST (TTL trick)",
        "fake_rst",
        build_fake_rst_candidate(base),
        vec!["Sends a fake RST with low TTL to clear DPI state; lab-only rooted experiment"],
    ));
    candidates.push(candidate_spec_with_notes(
        "fake_synfin",
        "Fake packet + SYN|FIN",
        "fake_flags",
        build_tlsrec_fake_flag_candidate(base, "syn|fin"),
        vec!["Applies SYN and FIN on the fake packet while preserving the normal payload flow"],
    ));
    candidates.push(candidate_spec_with_notes(
        "fake_pshurg",
        "Fake packet + PSH|URG",
        "fake_flags",
        build_tlsrec_fake_flag_candidate(base, "psh|urg"),
        vec!["Applies PSH and URG on the fake packet while preserving the normal payload flow"],
    ));
    if supports_tcp_ip_fragmentation() {
        for (id, label, profile, note) in [
            (
                "ipfrag2_hopbyhop",
                "IP fragmentation + Hop-by-Hop",
                "hopByHop",
                "Adds one Hop-by-Hop header before fragmentation",
            ),
            (
                "ipfrag2_hopbyhop2",
                "IP fragmentation + Hop-by-Hop2",
                "hopByHop2",
                "Adds the double-header Tier 2 IPv6 extension profile",
            ),
            (
                "ipfrag2_destopt",
                "IP fragmentation + Dest Opt",
                "destOpt",
                "Adds one Destination Options header before fragmentation",
            ),
            (
                "ipfrag2_hopbyhop_destopt",
                "IP fragmentation + HBH + Dest Opt",
                "hopByHopDestOpt",
                "Adds both Hop-by-Hop and Destination Options headers before fragmentation",
            ),
        ] {
            candidates.push(candidate_spec_with_notes(
                id,
                label,
                "ipfrag2_ipv6_ext",
                build_ipfrag_candidate_with_ipv6_ext(base, profile),
                vec![note, "VPN-only raw-socket TCP fragmentation variant for IPv6-capable paths"],
            ));
        }
    }
    candidates.push(build_circular_tlsrec_split_spec(base));
    candidates.push(candidate_spec_with_notes(
        "tlsrec_fakedsplit_altorder1",
        "TLS record + fakedsplit (altorder 1)",
        "fake_approx",
        build_tlsrec_fakedsplit_altorder_candidate(base, "1"),
        vec!["Emits both fake regions before both genuine fakedsplit regions"],
    ));
    candidates.push(candidate_spec_with_notes(
        "tlsrec_fakedsplit_altorder2",
        "TLS record + fakedsplit (altorder 2)",
        "fake_approx",
        build_tlsrec_fakedsplit_altorder_candidate(base, "2"),
        vec!["Interleaves genuine then fake for each fakedsplit region pair"],
    ));
    candidates.extend([
        build_activation_window_split_spec(base),
        build_activation_window_hostfake_spec(base),
        build_adaptive_fake_ttl_spec(base),
        build_fake_payload_library_spec(base),
    ]);
    candidates
}
