use ripdpi_config::{
    parse_tcp_flag_mask, validate_tcp_flag_masks, ActivationFilter, DesyncGroup, FakeOrder, FakeSeqMode, NumericRange,
    OffsetBase, OffsetExpr, RotationCandidate, RotationPolicy, SeqOverlapFakeMode, TcpChainStep, TcpChainStepKind,
    UdpChainStep, UdpChainStepKind,
};
use ripdpi_packets::{IS_HTTP, IS_HTTPS};

use crate::types::{
    ProxyConfigError, ProxyUiActivationFilter, ProxyUiChainConfig, ProxyUiNumericRange, ProxyUiTcpChainStep,
    ProxyUiUdpChainStep, SEQOVL_DEFAULT_OVERLAP_SIZE, SEQOVL_FAKE_MODE_PROFILE, SEQOVL_FAKE_MODE_RAND,
    TLS_RANDREC_DEFAULT_FRAGMENT_COUNT, TLS_RANDREC_DEFAULT_MAX_FRAGMENT_SIZE, TLS_RANDREC_DEFAULT_MIN_FRAGMENT_SIZE,
};

use super::legacy_payload_adapter::parse_offset_expr_field;

pub(crate) fn apply_chain_section(
    group: &mut DesyncGroup,
    chains: &ProxyUiChainConfig,
) -> Result<(), ProxyConfigError> {
    group.matches.any_protocol = chains.any_protocol;
    group.matches.payload_disable = parse_payload_disable(&chains.payload_disable);
    if let Some(filter) =
        parse_proxy_activation_filter(chains.group_activation_filter.as_ref(), "chains.groupActivationFilter", false)?
    {
        group.set_activation_filter(filter);
    }

    group.actions.tcp_chain = parse_proxy_tcp_chain(&chains.tcp_steps, "chains.tcpSteps")?;
    synthesize_tlsrec_prelude_for_bare_hostfake(&mut group.actions.tcp_chain);
    validate_tcp_chain(&group.actions.tcp_chain)?;

    if let Some(rotation) = chains.tcp_rotation.as_ref() {
        if rotation.candidates.is_empty() {
            return Err(ProxyConfigError::InvalidConfig(
                "chains.tcpRotation must declare at least one candidate".to_string(),
            ));
        }
        if rotation.fails == 0 {
            return Err(ProxyConfigError::InvalidConfig("chains.tcpRotation fails must be positive".to_string()));
        }
        if rotation.retrans == 0 {
            return Err(ProxyConfigError::InvalidConfig("chains.tcpRotation retrans must be positive".to_string()));
        }
        if rotation.time_secs == 0 {
            return Err(ProxyConfigError::InvalidConfig("chains.tcpRotation timeSecs must be positive".to_string()));
        }

        let candidates = rotation
            .candidates
            .iter()
            .enumerate()
            .map(|(index, candidate)| {
                let field_name = format!("chains.tcpRotation.candidates[{index}].tcpSteps");
                let mut tcp_chain = parse_proxy_tcp_chain(&candidate.tcp_steps, &field_name)?;
                synthesize_tlsrec_prelude_for_bare_hostfake(&mut tcp_chain);
                validate_tcp_chain(&tcp_chain)?;
                Ok(RotationCandidate { tcp_chain })
            })
            .collect::<Result<Vec<_>, ProxyConfigError>>()?;
        group.actions.rotation_policy = Some(RotationPolicy {
            fails: rotation.fails,
            retrans: rotation.retrans,
            seq: rotation.seq,
            rst: rotation.rst,
            time_secs: rotation.time_secs,
            cancel_on_failure: rotation.cancel_on_failure.unwrap_or(true),
            candidates,
        });
    }

    group.actions.udp_chain = parse_proxy_udp_chain(&chains.udp_steps)?;
    validate_udp_chain(&group.actions.udp_chain)?;
    Ok(())
}

pub fn parse_tcp_chain_step_kind(value: &str) -> Result<TcpChainStepKind, ProxyConfigError> {
    match value {
        "split" => Ok(TcpChainStepKind::Split),
        "syndata" => Ok(TcpChainStepKind::SynData),
        "seqovl" => Ok(TcpChainStepKind::SeqOverlap),
        "disorder" => Ok(TcpChainStepKind::Disorder),
        "multidisorder" => Ok(TcpChainStepKind::MultiDisorder),
        "fake" => Ok(TcpChainStepKind::Fake),
        "fakedsplit" => Ok(TcpChainStepKind::FakeSplit),
        "fakeddisorder" => Ok(TcpChainStepKind::FakeDisorder),
        "hostfake" => Ok(TcpChainStepKind::HostFake),
        "oob" => Ok(TcpChainStepKind::Oob),
        "disoob" => Ok(TcpChainStepKind::Disoob),
        "tlsrec" => Ok(TcpChainStepKind::TlsRec),
        "tlsrandrec" => Ok(TcpChainStepKind::TlsRandRec),
        "ipfrag2" => Ok(TcpChainStepKind::IpFrag2),
        "fakerst" => Ok(TcpChainStepKind::FakeRst),
        _ => Err(ProxyConfigError::InvalidConfig(format!("Unknown tcpChainSteps kind: {value}"))),
    }
}

pub fn parse_udp_chain_step_kind(value: &str) -> Result<UdpChainStepKind, ProxyConfigError> {
    match value {
        "fake_burst" => Ok(UdpChainStepKind::FakeBurst),
        "dummyprepend" | "dummy_prepend" => Ok(UdpChainStepKind::DummyPrepend),
        "quicsnisplit" | "quic_sni_split" => Ok(UdpChainStepKind::QuicSniSplit),
        "quicfakeversion" | "quic_fake_version" => Ok(UdpChainStepKind::QuicFakeVersion),
        "quiccryptosplit" | "quic_crypto_split" => Ok(UdpChainStepKind::QuicCryptoSplit),
        "quicpaddingladder" | "quic_padding_ladder" => Ok(UdpChainStepKind::QuicPaddingLadder),
        "quiccidchurn" | "quic_cid_churn" => Ok(UdpChainStepKind::QuicCidChurn),
        "quicpacketnumbergap" | "quic_packet_number_gap" => Ok(UdpChainStepKind::QuicPacketNumberGap),
        "quicversionnegotiationdecoy" | "quic_version_negotiation_decoy" => {
            Ok(UdpChainStepKind::QuicVersionNegotiationDecoy)
        }
        "quicmultiinitialrealistic" | "quic_multi_initial_realistic" => Ok(UdpChainStepKind::QuicMultiInitialRealistic),
        "ipfrag2_udp" => Ok(UdpChainStepKind::IpFrag2Udp),
        _ => Err(ProxyConfigError::InvalidConfig(format!("Unknown udpChainSteps kind: {value}"))),
    }
}

fn parse_payload_disable(names: &[String]) -> u32 {
    let mut mask = 0u32;
    for name in names {
        match name.as_str() {
            "http" => mask |= IS_HTTP,
            "tls" | "https" => mask |= IS_HTTPS,
            _ => {}
        }
    }
    mask
}

fn synthesize_tlsrec_prelude_for_bare_hostfake(chain: &mut Vec<TcpChainStep>) {
    let has_hostfake = chain.iter().any(|step| step.kind == TcpChainStepKind::HostFake);
    let has_tls_prelude = chain.iter().any(|step| step.kind.is_tls_prelude());
    if !has_hostfake || has_tls_prelude {
        return;
    }

    chain.insert(
        0,
        TcpChainStep {
            kind: TcpChainStepKind::TlsRec,
            offset: OffsetExpr::tls_marker(OffsetBase::ExtLen, 0),
            activation_filter: None,
            midhost_offset: None,
            fake_host_template: None,
            fake_order: FakeOrder::BeforeEach,
            fake_seq_mode: FakeSeqMode::Duplicate,
            tcp_flags_set: None,
            tcp_flags_unset: None,
            tcp_flags_orig_set: None,
            tcp_flags_orig_unset: None,
            overlap_size: 0,
            seqovl_fake_mode: SeqOverlapFakeMode::Profile,
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
            inter_segment_delay_ms: 0,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: false,
            ipv6_dest_opt: false,
            ipv6_dest_opt2: false,
            ipv6_routing: false,
            ipv6_frag_next_override: None,
            random_fake_host: false,
        },
    );
}

fn parse_proxy_numeric_range(
    range: &ProxyUiNumericRange,
    field_name: &str,
    minimum: i64,
) -> Result<Option<NumericRange<i64>>, ProxyConfigError> {
    let start = range.start;
    let end = range.end;
    if start.is_none() && end.is_none() {
        return Ok(None);
    }

    let start = start.or(end).unwrap_or(minimum);
    let end = end.or(Some(start)).unwrap_or(start);
    if start < minimum || end < minimum || start > end {
        return Err(ProxyConfigError::InvalidConfig(format!("Invalid {field_name}")));
    }

    Ok(Some(NumericRange::new(start, end)))
}

fn parse_proxy_activation_filter(
    filter: Option<&ProxyUiActivationFilter>,
    field_name: &str,
    allow_tcp_state_predicates: bool,
) -> Result<Option<ActivationFilter>, ProxyConfigError> {
    let Some(filter) = filter else {
        return Ok(None);
    };

    let round = filter
        .round
        .as_ref()
        .map(|value| parse_proxy_numeric_range(value, &format!("{field_name}.round"), 1))
        .transpose()?
        .flatten();
    let payload_size = filter
        .payload_size
        .as_ref()
        .map(|value| parse_proxy_numeric_range(value, &format!("{field_name}.payloadSize"), 0))
        .transpose()?
        .flatten();
    let stream_bytes = filter
        .stream_bytes
        .as_ref()
        .map(|value| parse_proxy_numeric_range(value, &format!("{field_name}.streamBytes"), 0))
        .transpose()?
        .flatten();

    if !allow_tcp_state_predicates
        && (filter.tcp_has_timestamp.is_some()
            || filter.tcp_has_ech.is_some()
            || filter.tcp_window_below.is_some()
            || filter.tcp_mss_below.is_some())
    {
        return Err(ProxyConfigError::InvalidConfig(format!("{field_name} must not declare TCP-state predicates")));
    }

    let filter = ActivationFilter {
        round,
        payload_size,
        stream_bytes,
        tcp_has_timestamp: filter.tcp_has_timestamp,
        tcp_has_ech: filter.tcp_has_ech,
        tcp_window_below: filter.tcp_window_below,
        tcp_mss_below: filter.tcp_mss_below,
    };
    Ok((!filter.is_unbounded()).then_some(filter))
}

fn normalize_tlsrandrec_step_field(value: i32, default: i32) -> i32 {
    if value > 0 {
        value
    } else {
        default
    }
}

fn normalize_seqovl_overlap_size(value: i32) -> i32 {
    if value > 0 {
        value
    } else {
        SEQOVL_DEFAULT_OVERLAP_SIZE
    }
}

fn parse_seqovl_fake_mode(value: &str) -> Result<SeqOverlapFakeMode, ProxyConfigError> {
    match value.trim().to_ascii_lowercase().as_str() {
        SEQOVL_FAKE_MODE_PROFILE | "" => Ok(SeqOverlapFakeMode::Profile),
        SEQOVL_FAKE_MODE_RAND => Ok(SeqOverlapFakeMode::Rand),
        _ => Err(ProxyConfigError::InvalidConfig(
            "tcpChainSteps kind=seqovl fakeMode must be profile or rand".to_string(),
        )),
    }
}

fn parse_fake_order(value: &str) -> Result<FakeOrder, ProxyConfigError> {
    match value.trim().to_ascii_lowercase().as_str() {
        "" | "0" => Ok(FakeOrder::BeforeEach),
        "1" => Ok(FakeOrder::AllFakesFirst),
        "2" => Ok(FakeOrder::RealFakeRealFake),
        "3" => Ok(FakeOrder::AllRealsFirst),
        _ => Err(ProxyConfigError::InvalidConfig("tcpChainSteps fakeOrder must be 0, 1, 2, 3, or empty".to_string())),
    }
}

fn parse_fake_seq_mode(value: &str) -> Result<FakeSeqMode, ProxyConfigError> {
    match value.trim().to_ascii_lowercase().as_str() {
        "" | "duplicate" => Ok(FakeSeqMode::Duplicate),
        "sequential" => Ok(FakeSeqMode::Sequential),
        _ => Err(ProxyConfigError::InvalidConfig(
            "tcpChainSteps fakeSeqMode must be duplicate, sequential, or empty".to_string(),
        )),
    }
}

fn parse_proxy_tcp_chain(
    steps: &[ProxyUiTcpChainStep],
    field_name: &str,
) -> Result<Vec<TcpChainStep>, ProxyConfigError> {
    let activation_field_name = format!("{field_name}.activationFilter");
    let mut parsed = Vec::with_capacity(steps.len());

    for step in steps {
        let kind = parse_tcp_chain_step_kind(&step.kind)?;
        let offset = parse_offset_expr_field(Some(step.marker.as_str()), "0", field_name)?;
        if kind == TcpChainStepKind::HostFake && offset.base.is_adaptive() {
            return Err(ProxyConfigError::InvalidConfig(format!(
                "Adaptive markers are not supported for {field_name} kind=hostfake"
            )));
        }

        let midhost_offset = Some(str::trim(step.midhost_marker.as_str()))
            .filter(|value| !value.is_empty())
            .map(ripdpi_config::parse_offset_expr)
            .transpose()
            .map_err(|_| ProxyConfigError::InvalidConfig(format!("Invalid {field_name} midhostMarker")))?;
        if kind == TcpChainStepKind::HostFake && midhost_offset.is_some_and(|value| value.base.is_adaptive()) {
            return Err(ProxyConfigError::InvalidConfig(format!(
                "Adaptive markers are not supported for {field_name} midhostMarker"
            )));
        }

        let fake_host_template = Some(str::trim(step.fake_host_template.as_str()))
            .filter(|value| !value.is_empty())
            .map(ripdpi_config::normalize_fake_host_template)
            .transpose()
            .map_err(|_| ProxyConfigError::InvalidConfig(format!("Invalid {field_name} fakeHostTemplate")))?;
        let fake_order = parse_fake_order(&step.fake_order)?;
        let fake_seq_mode = parse_fake_seq_mode(&step.fake_seq_mode)?;
        let tcp_flags_set = Some(str::trim(step.tcp_flags_set.as_str()))
            .filter(|value| !value.is_empty())
            .map(parse_tcp_flag_mask)
            .transpose()
            .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid {field_name} tcpFlags: {err}")))?;
        let tcp_flags_unset = Some(str::trim(step.tcp_flags_unset.as_str()))
            .filter(|value| !value.is_empty())
            .map(parse_tcp_flag_mask)
            .transpose()
            .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid {field_name} tcpFlagsUnset: {err}")))?;
        let tcp_flags_orig_set = Some(str::trim(step.tcp_flags_orig_set.as_str()))
            .filter(|value| !value.is_empty())
            .map(parse_tcp_flag_mask)
            .transpose()
            .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid {field_name} tcpFlagsOrig: {err}")))?;
        let tcp_flags_orig_unset = Some(str::trim(step.tcp_flags_orig_unset.as_str()))
            .filter(|value| !value.is_empty())
            .map(parse_tcp_flag_mask)
            .transpose()
            .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid {field_name} tcpFlagsOrigUnset: {err}")))?;
        validate_tcp_flag_masks(
            kind,
            tcp_flags_set,
            tcp_flags_unset,
            tcp_flags_orig_set,
            tcp_flags_orig_unset,
            field_name,
        )
        .map_err(|err| ProxyConfigError::InvalidConfig(err.to_string()))?;

        let (overlap_size, seqovl_fake_mode) = match kind {
            TcpChainStepKind::SeqOverlap => {
                let overlap_size = normalize_seqovl_overlap_size(step.overlap_size);
                if !(1..=32).contains(&overlap_size) {
                    return Err(ProxyConfigError::InvalidConfig(format!(
                        "{field_name} kind=seqovl overlapSize must be in 1..=32"
                    )));
                }
                (overlap_size, parse_seqovl_fake_mode(&step.fake_mode)?)
            }
            _ => {
                if step.overlap_size != 0 {
                    return Err(ProxyConfigError::InvalidConfig(format!(
                        "{field_name} kind={} must not declare overlapSize",
                        step.kind
                    )));
                }
                if !step.fake_mode.trim().is_empty() && !step.fake_mode.eq_ignore_ascii_case(SEQOVL_FAKE_MODE_PROFILE) {
                    return Err(ProxyConfigError::InvalidConfig(format!(
                        "{field_name} kind={} must not declare fakeMode",
                        step.kind
                    )));
                }
                (0, SeqOverlapFakeMode::Profile)
            }
        };

        let (fragment_count, min_fragment_size, max_fragment_size) = match kind {
            TcpChainStepKind::TlsRandRec => (
                normalize_tlsrandrec_step_field(step.fragment_count, TLS_RANDREC_DEFAULT_FRAGMENT_COUNT),
                normalize_tlsrandrec_step_field(step.min_fragment_size, TLS_RANDREC_DEFAULT_MIN_FRAGMENT_SIZE),
                normalize_tlsrandrec_step_field(step.max_fragment_size, TLS_RANDREC_DEFAULT_MAX_FRAGMENT_SIZE),
            ),
            _ => {
                if step.fragment_count != 0 || step.min_fragment_size != 0 || step.max_fragment_size != 0 {
                    return Err(ProxyConfigError::InvalidConfig(format!(
                        "tlsrandrec fragment fields are only supported for {field_name} kind=tlsrandrec"
                    )));
                }
                (0, 0, 0)
            }
        };

        let activation_filter =
            parse_proxy_activation_filter(step.activation_filter.as_ref(), &activation_field_name, true)?;
        let ipv6_ext = parse_ipv6_extension_profile(&step.ipv6_extension_profile)?;
        parsed.push(TcpChainStep {
            kind,
            offset,
            activation_filter,
            midhost_offset,
            fake_host_template,
            fake_order,
            fake_seq_mode,
            tcp_flags_set,
            tcp_flags_unset,
            tcp_flags_orig_set,
            tcp_flags_orig_unset,
            overlap_size,
            seqovl_fake_mode,
            fragment_count,
            min_fragment_size,
            max_fragment_size,
            inter_segment_delay_ms: step.inter_segment_delay_ms.min(500),
            ip_frag_disorder: false,
            ipv6_hop_by_hop: ipv6_ext.hop_by_hop,
            ipv6_dest_opt: ipv6_ext.dest_opt,
            ipv6_dest_opt2: ipv6_ext.dest_opt2,
            ipv6_routing: false,
            ipv6_frag_next_override: None,
            random_fake_host: step.random_fake_host,
        });
    }

    Ok(parsed)
}

fn validate_tcp_chain(steps: &[TcpChainStep]) -> Result<(), ProxyConfigError> {
    let mut saw_send_step = false;
    let mut saw_ipfrag2 = false;
    let mut saw_seqovl = false;
    let mut send_step_count = 0usize;
    let mut multidisorder_count = 0usize;

    for (index, step) in steps.iter().enumerate() {
        if step.kind.is_tls_prelude() {
            if saw_send_step {
                return Err(ProxyConfigError::InvalidConfig(format!(
                    "{} must be declared before tcp send steps",
                    tcp_chain_step_kind_label(step.kind)
                )));
            }
        } else {
            saw_send_step = true;
            if step.kind == TcpChainStepKind::SeqOverlap {
                if saw_seqovl {
                    return Err(ProxyConfigError::InvalidConfig(
                        "seqovl must appear at most once per tcp chain".to_string(),
                    ));
                }
                if send_step_count != 0 {
                    return Err(ProxyConfigError::InvalidConfig("seqovl must be the first tcp send step".to_string()));
                }
                if !(1..=32).contains(&step.overlap_size) {
                    return Err(ProxyConfigError::InvalidConfig("seqovl overlapSize must be in 1..=32".to_string()));
                }
                saw_seqovl = true;
            }
            if step.kind == TcpChainStepKind::MultiDisorder {
                multidisorder_count += 1;
            } else if multidisorder_count != 0 {
                return Err(ProxyConfigError::InvalidConfig(
                    "multidisorder must be the only tcp send step family".to_string(),
                ));
            }
            if step.kind == TcpChainStepKind::IpFrag2 {
                saw_ipfrag2 = true;
                if index + 1 != steps.len() {
                    return Err(ProxyConfigError::InvalidConfig("ipfrag2 must be the only tcp send step".to_string()));
                }
            } else if saw_ipfrag2 {
                return Err(ProxyConfigError::InvalidConfig("ipfrag2 must be the only tcp send step".to_string()));
            }
            send_step_count += 1;
        }

        if matches!(step.kind, TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder) && index + 1 != steps.len()
        {
            return Err(ProxyConfigError::InvalidConfig(format!(
                "{} must be the last tcp send step",
                tcp_chain_step_kind_label(step.kind)
            )));
        }
        if step.kind.supports_fake_ordering() {
            if step.kind == TcpChainStepKind::HostFake
                && step.fake_order != FakeOrder::BeforeEach
                && step.midhost_offset.is_none()
            {
                return Err(ProxyConfigError::InvalidConfig("hostfake fakeOrder requires midhostMarker".to_string()));
            }
        } else if step.fake_order != FakeOrder::BeforeEach || step.fake_seq_mode != FakeSeqMode::Duplicate {
            return Err(ProxyConfigError::InvalidConfig(format!(
                "{} must not declare fake ordering fields",
                tcp_chain_step_kind_label(step.kind)
            )));
        }
    }

    if multidisorder_count > 0 {
        if send_step_count != multidisorder_count {
            return Err(ProxyConfigError::InvalidConfig(
                "multidisorder must be the only tcp send step family".to_string(),
            ));
        }
        if multidisorder_count < 2 {
            return Err(ProxyConfigError::InvalidConfig("multidisorder must declare at least two markers".to_string()));
        }
    }

    Ok(())
}

fn tcp_chain_step_kind_label(kind: TcpChainStepKind) -> &'static str {
    match kind {
        TcpChainStepKind::Split => "split",
        TcpChainStepKind::SynData => "syndata",
        TcpChainStepKind::SeqOverlap => "seqovl",
        TcpChainStepKind::Disorder => "disorder",
        TcpChainStepKind::MultiDisorder => "multidisorder",
        TcpChainStepKind::Fake => "fake",
        TcpChainStepKind::FakeSplit => "fakedsplit",
        TcpChainStepKind::FakeDisorder => "fakeddisorder",
        TcpChainStepKind::HostFake => "hostfake",
        TcpChainStepKind::Oob => "oob",
        TcpChainStepKind::Disoob => "disoob",
        TcpChainStepKind::TlsRec => "tlsrec",
        TcpChainStepKind::TlsRandRec => "tlsrandrec",
        TcpChainStepKind::IpFrag2 => "ipfrag2",
        TcpChainStepKind::FakeRst => "fakerst",
        _ => "unknown",
    }
}

fn parse_proxy_udp_chain(steps: &[ProxyUiUdpChainStep]) -> Result<Vec<UdpChainStep>, ProxyConfigError> {
    let mut parsed = Vec::with_capacity(steps.len());
    for step in steps {
        if step.count < 0 {
            return Err(ProxyConfigError::InvalidConfig("udpChainSteps count must be non-negative".to_string()));
        }

        let kind = parse_udp_chain_step_kind(&step.kind)?;
        if kind == UdpChainStepKind::IpFrag2Udp {
            if step.count != 0 {
                return Err(ProxyConfigError::InvalidConfig(
                    "udpChainSteps kind=ipfrag2_udp must not declare count".to_string(),
                ));
            }
            if step.split_bytes <= 0 {
                return Err(ProxyConfigError::InvalidConfig(
                    "udpChainSteps kind=ipfrag2_udp must declare positive splitBytes".to_string(),
                ));
            }
        } else if step.split_bytes != 0 {
            return Err(ProxyConfigError::InvalidConfig(
                "udpChainSteps splitBytes is only supported for kind=ipfrag2_udp".to_string(),
            ));
        }

        let ipv6_ext = parse_ipv6_extension_profile(&step.ipv6_extension_profile)?;
        parsed.push(UdpChainStep {
            kind,
            count: step.count,
            split_bytes: step.split_bytes,
            activation_filter: parse_proxy_activation_filter(
                step.activation_filter.as_ref(),
                "chains.udpSteps.activationFilter",
                false,
            )?,
            ip_frag_disorder: false,
            ipv6_hop_by_hop: ipv6_ext.hop_by_hop,
            ipv6_dest_opt: ipv6_ext.dest_opt,
            ipv6_dest_opt2: ipv6_ext.dest_opt2,
            ipv6_frag_next_override: None,
        });
    }
    Ok(parsed)
}

fn validate_udp_chain(steps: &[UdpChainStep]) -> Result<(), ProxyConfigError> {
    if steps.iter().any(|step| step.kind == UdpChainStepKind::IpFrag2Udp) && steps.len() != 1 {
        return Err(ProxyConfigError::InvalidConfig("ipfrag2_udp must be the only udp chain step".to_string()));
    }
    Ok(())
}

#[derive(Clone, Copy)]
struct ParsedIpv6ExtensionProfile {
    hop_by_hop: bool,
    dest_opt: bool,
    dest_opt2: bool,
}

fn parse_ipv6_extension_profile(value: &str) -> Result<ParsedIpv6ExtensionProfile, ProxyConfigError> {
    match value.trim() {
        "" | "none" => Ok(ParsedIpv6ExtensionProfile { hop_by_hop: false, dest_opt: false, dest_opt2: false }),
        "hopByHop" => Ok(ParsedIpv6ExtensionProfile { hop_by_hop: true, dest_opt: false, dest_opt2: false }),
        "hopByHop2" => Ok(ParsedIpv6ExtensionProfile { hop_by_hop: true, dest_opt: false, dest_opt2: true }),
        "destOpt" => Ok(ParsedIpv6ExtensionProfile { hop_by_hop: false, dest_opt: true, dest_opt2: false }),
        "hopByHopDestOpt" => Ok(ParsedIpv6ExtensionProfile { hop_by_hop: true, dest_opt: true, dest_opt2: false }),
        _ => Err(ProxyConfigError::InvalidConfig(
            "Unsupported ipv6ExtensionProfile; expected none, hopByHop, hopByHop2, destOpt, or hopByHopDestOpt"
                .to_string(),
        )),
    }
}
