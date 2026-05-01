use std::collections::HashMap;

/// The observed block class for a direct-path (host, ip-set) tuple.
///
/// Derived from the accumulated tuple state flags and terminal state. Each
/// variant maps to a distinct ranked arm list.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DirectPathBlockClass {
    /// No negative evidence — plain TCP or QUIC is worth trying first.
    Clean,
    /// UDP/QUIC datagrams are being dropped; TCP-based arms rank higher.
    QuicBlocked,
    /// TLS post-ClientHello interference detected; record-split arms rank higher.
    TlsPostClientHello,
    /// Both UDP blocked and TLS interference observed simultaneously.
    QuicBlockedAndTlsPostClientHello,
    /// UDP was suppressed and no TCP fallback appeared within the observation
    /// window; the host may be UDP-only or completely unreachable directly.
    NoTcpFallback,
    /// Every known IP for the target failed; relay is the only remaining option.
    AllIpsFailed,
    /// A previous QUIC attempt succeeded; QUIC arms rank highest.
    QuicConfirmed,
}

/// A single candidate arm returned by the ranked dispatcher.
///
/// Arms are ordered so that index 0 is the highest-priority choice. The
/// `score` field is a normalised `f32` in `[0, 1]` where higher means "try
/// this arm first". `attempt_budget` is currently a conservative per-arm
/// default.
#[derive(Clone, Debug, PartialEq)]
pub struct RankedArm {
    /// Short label identifying the transport / strategy arm.
    pub label: &'static str,
    /// Normalised priority score. Higher = preferred.
    pub score: f32,
    /// Block class that caused this arm to be ranked at this position.
    pub class: DirectPathBlockClass,
    /// Remaining attempt budget before the arm should be backed off.
    pub attempt_budget: u32,
}

/// Default attempt budget used for all arms.
pub(super) const DEFAULT_ATTEMPT_BUDGET: u32 = 3;

/// Return the ranked arm list for a given block class.
///
/// Each variant encodes expert knowledge about which transport arms are most
/// likely to succeed for the given failure pattern. Scores are chosen so that
/// the relative ordering is clear but not sensitive to floating-point equality.
pub(super) fn ranked_arms_for_class(class: DirectPathBlockClass) -> Vec<RankedArm> {
    match class {
        DirectPathBlockClass::Clean => vec![
            RankedArm { label: "quic", score: 0.9, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
            RankedArm { label: "tcp_plain", score: 0.8, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
        ],
        DirectPathBlockClass::QuicBlocked => vec![
            RankedArm { label: "tcp_plain", score: 0.9, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
            RankedArm { label: "tcp_tls_split", score: 0.7, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
        ],
        DirectPathBlockClass::TlsPostClientHello => vec![
            RankedArm { label: "tcp_tls_split", score: 0.9, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
            RankedArm { label: "tcp_plain", score: 0.6, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
        ],
        DirectPathBlockClass::QuicBlockedAndTlsPostClientHello => vec![
            RankedArm { label: "tcp_tls_split", score: 0.9, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
            RankedArm { label: "tcp_plain", score: 0.5, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
        ],
        DirectPathBlockClass::NoTcpFallback => vec![
            RankedArm { label: "quic", score: 0.8, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
            RankedArm { label: "tcp_plain", score: 0.3, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
        ],
        DirectPathBlockClass::AllIpsFailed => {
            vec![RankedArm { label: "relay_fallback", score: 0.9, class, attempt_budget: 1 }]
        }
        DirectPathBlockClass::QuicConfirmed => vec![
            RankedArm { label: "quic", score: 1.0, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
            RankedArm { label: "tcp_plain", score: 0.4, class, attempt_budget: DEFAULT_ATTEMPT_BUDGET },
        ],
    }
}

pub(super) fn apply_attempt_budgets(arms: &mut Vec<RankedArm>, attempts: Option<&HashMap<&'static str, u32>>) {
    let Some(attempts) = attempts else {
        return;
    };
    arms.retain_mut(|arm| {
        let used = attempts.get(arm.label).copied().unwrap_or(0);
        if used >= arm.attempt_budget {
            return false;
        }
        arm.attempt_budget -= used;
        true
    });

    if arms.is_empty() {
        arms.push(RankedArm {
            label: "relay_fallback",
            score: 0.5,
            class: DirectPathBlockClass::AllIpsFailed,
            attempt_budget: 1,
        });
    }
}
