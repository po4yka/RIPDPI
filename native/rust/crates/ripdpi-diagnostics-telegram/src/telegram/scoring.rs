pub(crate) fn classify_telegram_verdict(
    dl_status: &str,
    ul_status: &str,
    dc_reachable: usize,
    dc_planned: usize,
    ws_status: &str,
) -> &'static str {
    let statuses = [dl_status, ul_status, ws_status];
    if statuses.contains(&"cancelled") {
        return "cancelled";
    }
    if statuses.contains(&"deadline_exceeded") {
        return "deadline_exceeded";
    }

    let dc_ok = dc_planned > 0 && dc_reachable == dc_planned;
    let transfer_degraded = |status: &str| matches!(status, "slow" | "stalled");
    if dc_ok && statuses.iter().all(|status| *status == "ok") {
        "ok"
    } else if dc_ok
        && ws_status == "ok"
        && [dl_status, ul_status].iter().all(|status| *status == "ok" || transfer_degraded(status))
    {
        "slow"
    } else if dc_reachable > 0 || statuses.iter().any(|status| *status == "ok" || transfer_degraded(status)) {
        "partial"
    } else if dc_planned == 0 || statuses.iter().any(|status| deficit(status).is_none()) {
        "unknown"
    } else if dl_status == "blocked" && ul_status == "blocked" {
        "blocked"
    } else {
        "error"
    }
}

/// Availability deficit from 0 (all paths worked) to 100 (all paths failed).
/// A missing measurement or undone attempt prevents comparison.
pub(crate) fn compute_telegram_quality_score(
    dl_status: &str,
    ul_status: &str,
    dc_reachable: usize,
    dc_attempted: usize,
    dc_planned: usize,
    ws_status: &str,
) -> Option<u8> {
    if dc_planned == 0 || dc_attempted != dc_planned || dc_reachable > dc_planned {
        return None;
    }
    let dc = (dc_planned - dc_reachable).saturating_mul(100).div_ceil(dc_planned);
    let deficit = 3 * deficit(dl_status)? + 2 * deficit(ul_status)? + dc + deficit(ws_status)?;
    u8::try_from(deficit.div_ceil(7)).ok()
}

fn deficit(status: &str) -> Option<usize> {
    match status {
        "ok" => Some(0),
        "slow" | "stalled" => Some(50),
        "error"
        | "blocked"
        | "unreachable"
        | "open_timed_out"
        | "subprotocol_not_negotiated"
        | "upgrade_rejected"
        | "upgrade_failed" => Some(100),
        _ => None,
    }
}
