use std::io;

use ripdpi_config::{DesyncGroup, TcpChainStepKind};
use ripdpi_desync::DesyncPlan;

pub fn primary_tcp_strategy_family(group: &DesyncGroup) -> Option<&'static str> {
    let chain = group.effective_tcp_chain();
    let has_tls_prelude = chain.iter().any(|step| step.kind().is_tls_prelude());
    let primary = chain.into_iter().find(|step| !step.kind().is_tls_prelude());
    if primary.is_none() && has_tls_prelude {
        return Some("tlsrec");
    }
    primary.map(|step| match step.kind() {
        TcpChainStepKind::Split | TcpChainStepKind::SynData => {
            if has_tls_prelude {
                "tlsrec_split"
            } else {
                "split"
            }
        }
        TcpChainStepKind::SeqOverlap => {
            if has_tls_prelude {
                "tlsrec_seqovl"
            } else {
                "seqovl"
            }
        }
        TcpChainStepKind::MultiDisorder => {
            if has_tls_prelude {
                "tlsrec_multidisorder"
            } else {
                "multidisorder"
            }
        }
        TcpChainStepKind::Disorder => "disorder",
        TcpChainStepKind::Oob => "oob",
        TcpChainStepKind::Disoob => "disoob",
        TcpChainStepKind::Fake => "fake",
        TcpChainStepKind::FakeSplit => "fakedsplit",
        TcpChainStepKind::FakeDisorder => "fakeddisorder",
        TcpChainStepKind::HostFake => "hostfake",
        TcpChainStepKind::IpFrag2 => "ipfrag2",
        TcpChainStepKind::FakeRst => "fakerst",
        TcpChainStepKind::TlsRec | TcpChainStepKind::TlsRandRec => "tlsrec",
        _ => "unknown",
    })
}

pub(crate) fn strategy_fallback_family(strategy_family: &'static str) -> Option<&'static str> {
    match strategy_family {
        "seg_mid_sni" => Some("seg_pre_sni"),
        "seg_post_sni" => Some("seg_mid_sni"),
        "rec_mid_sni" => Some("rec_pre_sni"),
        "seqovl" => Some("split"),
        "tlsrec_seqovl" => Some("tlsrec_split"),
        "disorder" => Some("split"),
        "disoob" => Some("oob"),
        "fakeddisorder" => Some("fakedsplit"),
        _ => None,
    }
}

pub(crate) fn effective_tcp_strategy_family(
    configured_family: Option<&'static str>,
    plan: &DesyncPlan,
    tls_prelude_applied: bool,
) -> (Option<&'static str>, bool) {
    let planned_kind = plan.steps.iter().find(|step| !step.kind.is_tls_prelude()).map(|step| step.kind);
    let (configured_family, prelude_fallback) = if tls_prelude_applied {
        (configured_family, false)
    } else {
        match configured_family {
            Some("tlsrec_split") => (Some("split"), true),
            Some("tlsrec_seqovl") => (Some("seqovl"), true),
            Some("tlsrec_multidisorder") => (Some("multidisorder"), true),
            family => (family, false),
        }
    };
    let (effective_family, plan_fallback) = match (configured_family, planned_kind) {
        (Some("seqovl"), Some(TcpChainStepKind::Split)) => (Some("split"), true),
        (Some("tlsrec_seqovl"), Some(TcpChainStepKind::Split)) => (Some("tlsrec_split"), true),
        (Some("hostfake"), Some(TcpChainStepKind::Split)) => (Some("split"), true),
        _ => (configured_family, false),
    };
    (effective_family, prelude_fallback || plan_fallback)
}

pub(crate) fn tcp_fallback_kind_for_strategy(strategy_family: &'static str) -> Option<TcpChainStepKind> {
    match strategy_family {
        "seqovl" | "tlsrec_seqovl" | "disorder" => Some(TcpChainStepKind::Split),
        "disoob" => Some(TcpChainStepKind::Oob),
        "fakeddisorder" => Some(TcpChainStepKind::FakeSplit),
        _ => None,
    }
}

pub(crate) fn write_action_name(strategy_family: &'static str) -> &'static str {
    match strategy_family {
        "split" | "seg_pre_sni" | "seg_mid_sni" | "seg_post_sni" | "two_phase_send" => "write_split",
        "seqovl" | "tlsrec_seqovl" => "write_seqovl",
        "disorder" => "write_disorder",
        "oob" => "write_oob",
        "disoob" => "write_disoob",
        "fake" => "write_fake",
        "fakedsplit" => "write_fakesplit",
        "fakeddisorder" => "write_fakeddisorder",
        "hostfake" => "write_hostfake",
        "rec_pre_sni" | "rec_mid_sni" => "write_tlsrec",
        _ => "write",
    }
}

pub(crate) fn should_fallback_ipfrag2_tcp_error_kind(kind: io::ErrorKind) -> bool {
    matches!(kind, io::ErrorKind::InvalidInput | io::ErrorKind::WouldBlock | io::ErrorKind::Unsupported)
}

pub(crate) fn log_ipfrag2_flow_fallback(error: &impl std::fmt::Display) {
    tracing::debug!("falling back to normal TCP write for ipfrag2 after per-flow repair downgrade: {error}");
}

pub(crate) fn should_fallback_seqovl_error_kind(kind: io::ErrorKind) -> bool {
    matches!(
        kind,
        io::ErrorKind::InvalidInput
            | io::ErrorKind::WouldBlock
            | io::ErrorKind::Unsupported
            | io::ErrorKind::PermissionDenied
    )
}

pub(crate) fn await_writable_action_name(strategy_family: &'static str) -> &'static str {
    match strategy_family {
        "split" | "seg_pre_sni" | "seg_mid_sni" | "seg_post_sni" | "two_phase_send" => "await_writable_split",
        "seqovl" | "tlsrec_seqovl" => "await_writable_seqovl",
        "disorder" => "await_writable_disorder",
        "oob" => "await_writable_oob",
        "disoob" => "await_writable_disoob",
        "fakedsplit" => "await_writable_fakesplit",
        "fakeddisorder" => "await_writable_fakeddisorder",
        "hostfake" => "await_writable_hostfake",
        "rec_pre_sni" | "rec_mid_sni" => "await_writable_tlsrec",
        _ => "await_writable",
    }
}

pub(crate) fn set_ttl_action_name(strategy_family: &'static str) -> &'static str {
    match strategy_family {
        "disorder" => "set_ttl_disorder",
        "disoob" => "set_ttl_disoob",
        "fakeddisorder" => "set_ttl_fakeddisorder",
        _ => "set_ttl",
    }
}

pub(crate) fn restore_ttl_action_name(strategy_family: &'static str) -> &'static str {
    match strategy_family {
        "disorder" => "restore_default_ttl_disorder",
        "disoob" => "restore_default_ttl_disoob",
        "fakeddisorder" => "restore_default_ttl_fakeddisorder",
        _ => "restore_default_ttl",
    }
}
