use ripdpi_config::{
    parse_tcp_flag_mask, validate_tcp_flag_masks, FakeOrder, FakeSeqMode, OffsetBase, OffsetExpr, SeqOverlapFakeMode,
    TcpChainStep, TcpChainStepKind,
};

use crate::types::{
    ProxyConfigError, ProxyUiTcpChainStep, SEQOVL_DEFAULT_OVERLAP_SIZE, SEQOVL_FAKE_MODE_PROFILE,
    SEQOVL_FAKE_MODE_RAND, TLS_RANDREC_DEFAULT_FRAGMENT_COUNT, TLS_RANDREC_DEFAULT_MAX_FRAGMENT_SIZE,
    TLS_RANDREC_DEFAULT_MIN_FRAGMENT_SIZE,
};

use super::super::legacy_payload_adapter::parse_offset_expr_field;
use super::activation::parse_proxy_activation_filter;
use super::ipv6::parse_ipv6_extension_profile;

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

pub(crate) fn synthesize_tlsrec_prelude_for_bare_hostfake(chain: &mut Vec<TcpChainStep>) {
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

pub(crate) fn parse_proxy_tcp_chain(
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

        let (overlap_size, seqovl_fake_mode) = parse_seq_overlap_fields(kind, step, field_name)?;
        let (fragment_count, min_fragment_size, max_fragment_size) =
            parse_tlsrandrec_fragment_fields(kind, step, field_name)?;
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

fn parse_seq_overlap_fields(
    kind: TcpChainStepKind,
    step: &ProxyUiTcpChainStep,
    field_name: &str,
) -> Result<(i32, SeqOverlapFakeMode), ProxyConfigError> {
    match kind {
        TcpChainStepKind::SeqOverlap => {
            let overlap_size = normalize_seqovl_overlap_size(step.overlap_size);
            if !(1..=32).contains(&overlap_size) {
                return Err(ProxyConfigError::InvalidConfig(format!(
                    "{field_name} kind=seqovl overlapSize must be in 1..=32"
                )));
            }
            Ok((overlap_size, parse_seqovl_fake_mode(&step.fake_mode)?))
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
            Ok((0, SeqOverlapFakeMode::Profile))
        }
    }
}

fn parse_tlsrandrec_fragment_fields(
    kind: TcpChainStepKind,
    step: &ProxyUiTcpChainStep,
    field_name: &str,
) -> Result<(i32, i32, i32), ProxyConfigError> {
    match kind {
        TcpChainStepKind::TlsRandRec => Ok((
            normalize_tlsrandrec_step_field(step.fragment_count, TLS_RANDREC_DEFAULT_FRAGMENT_COUNT),
            normalize_tlsrandrec_step_field(step.min_fragment_size, TLS_RANDREC_DEFAULT_MIN_FRAGMENT_SIZE),
            normalize_tlsrandrec_step_field(step.max_fragment_size, TLS_RANDREC_DEFAULT_MAX_FRAGMENT_SIZE),
        )),
        _ => {
            if step.fragment_count != 0 || step.min_fragment_size != 0 || step.max_fragment_size != 0 {
                return Err(ProxyConfigError::InvalidConfig(format!(
                    "tlsrandrec fragment fields are only supported for {field_name} kind=tlsrandrec"
                )));
            }
            Ok((0, 0, 0))
        }
    }
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
