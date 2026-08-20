use crate::candidates::prelude::*;

use super::allows_direct_tfo_candidates;

pub fn build_primary_candidates(base: &ProxyUiConfig) -> Vec<StrategyCandidateSpec> {
    let plain_direct = plain_direct_probe_config(base);
    let baseline_config = sanitize_current_probe_config(base);
    let mut baseline = candidate_spec("baseline_current", "Current strategy", "baseline", baseline_config);
    baseline.preserve_adaptive_fake_ttl = true;
    baseline.active_snapshot_faithful = !base.host_autolearn.enabled;
    let parser_only = build_parser_only_candidate(base);
    let parser_unixeol = build_parser_unixeol_candidate(base);
    let parser_methodeol = build_parser_methodeol_candidate(base);
    let parser_methodspace = build_parser_methodspace_candidate(base);
    let parser_hostpad = build_parser_hostpad_candidate(base);
    let parser_host_extra_space = build_parser_host_extra_space_candidate(base);
    let parser_host_tab = build_parser_host_tab_candidate(base);
    let split_host = build_split_host_candidate(base);
    let ech_split = build_ech_split_candidate(base);
    let ech_tlsrec = build_ech_tlsrec_candidate(base);
    let tlsrec_split_host = build_tlsrec_split_host_candidate(base);
    let tlsrec_hostfake_split = build_tlsrec_hostfake_candidate(base, true);
    let ipfrag_capable = supports_tcp_ip_fragmentation();

    let mut candidates = vec![
        candidate_spec("baseline_plain_direct", "Plain direct", "baseline", plain_direct),
        baseline,
        candidate_spec("tlsrec_split_host", "TLS record + split host", "tlsrec_split", tlsrec_split_host.clone()),
        candidate_spec_with_notes(
            "tlsrec_hostfake_split",
            "TLS record + hostfake split",
            "hostfake",
            tlsrec_hostfake_split,
            vec!["Adds a follow-up split after hostfake midhost reconstruction"],
        ),
        candidate_spec_with_notes(
            "tlsrec_hostfake_random",
            "TLS record + hostfake (random)",
            "hostfake",
            build_tlsrec_hostfake_random_candidate(base),
            vec!["Random domain per connection defeats DPI fake-SNI caching"],
        ),
        candidate_spec("split_host", "Split Host", "split", split_host.clone()),
        candidate_spec("oob_host", "OOB host", "oob", build_oob_host_candidate(base)),
        candidate_spec("tlsrec_oob", "TLS record + OOB", "tlsrec_oob", build_tlsrec_oob_candidate(base)),
        candidate_spec(
            "tlsrandrec_split",
            "TLS random record + split",
            "tlsrandrec_split",
            build_tlsrandrec_split_candidate(base),
        ),
        candidate_spec_with_notes(
            "tlsrec_seqovl_midsld",
            "TLS record + seq overlap (midsld)",
            "tlsrec_seqovl",
            build_tlsrec_seqovl_candidate(base, "midsld"),
            vec!["Sequence overlap at midsld; falls back to split if TCP_REPAIR unavailable"],
        ),
        candidate_spec_with_notes(
            "tlsrec_seqovl_sniext",
            "TLS record + seq overlap (sniext)",
            "tlsrec_seqovl",
            build_tlsrec_seqovl_candidate(base, "sniext"),
            vec!["Sequence overlap at sniext; falls back to split if TCP_REPAIR unavailable"],
        ),
        candidate_spec_with_notes(
            "split_delayed_50ms",
            "Split host + 50ms delay",
            "split_delayed",
            build_split_delayed_candidate(base, 50),
            vec!["50ms inter-segment delay exploits DPI timeout windows"],
        ),
        candidate_spec_with_notes(
            "split_delayed_150ms",
            "Split host + 150ms delay",
            "split_delayed",
            build_split_delayed_candidate(base, 150),
            vec!["150ms inter-segment delay for longer DPI timeout windows"],
        ),
        candidate_spec("parser_only", "Parser-only", "parser", parser_only),
        candidate_spec("parser_hostpad", "Parser + Host Pad", "parser", parser_hostpad),
        candidate_spec("parser_unixeol", "Parser + Unix EOL", "parser_aggressive", parser_unixeol),
        candidate_spec("parser_methodeol", "Parser + Method EOL", "parser_aggressive", parser_methodeol),
        candidate_spec("parser_methodspace", "Parser + Method Space", "parser_aggressive", parser_methodspace),
        candidate_spec("parser_host_tab", "Parser + Host Tab", "parser", parser_host_tab),
        candidate_spec(
            "parser_host_extra_space",
            "Parser + Host Extra Space",
            "parser_aggressive",
            parser_host_extra_space,
        ),
        candidate_spec_with_notes_and_eligibility(
            "ech_split",
            "ECH extension split",
            "ech_split",
            CandidateEligibility::RequiresEchCapability,
            ech_split,
            vec!["Runs only when the baseline proves an ECH-capable HTTPS path"],
        ),
        candidate_spec_with_notes_and_eligibility(
            "ech_tlsrec",
            "ECH TLS record split",
            "ech_tlsrec",
            CandidateEligibility::RequiresEchCapability,
            ech_tlsrec,
            vec!["Runs only when the baseline proves an ECH-capable HTTPS path"],
        ),
    ];
    if probe_tcp_fast_open_capability() && allows_direct_tfo_candidates(base) {
        candidates.push(candidate_spec_with_notes(
            "tlsrec_split_host_tfo",
            "TLS record + split host + TFO",
            "tlsrec_split_tfo",
            build_tfo_variant(&tlsrec_split_host),
            vec!["Enables TCP Fast Open for the upstream connect path"],
        ));
        candidates.push(candidate_spec_with_notes(
            "split_host_tfo",
            "Split host + TFO",
            "split_tfo",
            build_tfo_variant(&split_host),
            vec!["Enables TCP Fast Open for the upstream connect path"],
        ));
    }
    if ipfrag_capable {
        candidates.push(candidate_spec_with_notes(
            "ipfrag2",
            "IP fragmentation",
            "ipfrag2",
            build_ipfrag_candidate(base),
            vec!["VPN-only raw-socket TCP fragmentation of the first application-data segment"],
        ));
    }
    candidates
}
