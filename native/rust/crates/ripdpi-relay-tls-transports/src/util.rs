use std::io;

/// Convert any [`Display`](std::fmt::Display) error into an [`io::Error`] with
/// [`io::ErrorKind::Other`].
///
/// Used throughout this crate to adapt protocol-specific errors into the
/// `io::Result` surface exposed by `RelaySession` / `RelaySessionFactory`.
/// The `to_string()` call is intentional: it gives the caller a clean,
/// human-readable message and avoids surfacing internal type-erased error
/// chains that differ between protocol libraries.
///
/// # Why not use the `Error + Send + Sync + 'static` overload?
///
/// Some call sites (e.g. `tor.rs`) use `io::Error::other(error)` directly so
/// the full error chain is preserved. Those sites keep their own local helper
/// with the broader bound. This function is for the common case where the
/// protocol library returns a display-only or non-`'static` error type.
pub(crate) fn to_io_error(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}

/// Split a `host:port` relay target into its host and port parts.
///
/// Only the non-socket fallback path: call sites try
/// `target.parse::<SocketAddr>()` first, so bracketed IPv6
/// (`[2001:db8::1]:443`) never reaches here. A host that still contains `:`
/// is therefore a bare IPv6 literal (`2001:db8::1`); accepting it would let
/// `rsplit_once(':')` silently corrupt it into host `"2001:db8:"` with a
/// bogus port, so it is rejected with `InvalidInput` instead.
pub(crate) fn split_target_authority(target: &str) -> io::Result<(&str, u16)> {
    let (host, port) = target
        .rsplit_once(':')
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target authority: {target}")))?;
    if host.contains(':') {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("bare IPv6 target must use bracketed form: {target}"),
        ));
    }
    let port = port.parse::<u16>().map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target port in authority: {target}"))
    })?;
    Ok((host, port))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn split_target_authority_rejects_bare_ipv6_target() {
        let error = split_target_authority("2001:db8::1").expect_err("bare IPv6 target must be rejected");
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    }

    #[test]
    fn split_target_authority_rejects_target_without_port() {
        let error = split_target_authority("example.com").expect_err("missing port must be rejected");
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    }

    #[test]
    fn split_target_authority_rejects_non_numeric_port() {
        let error = split_target_authority("example.com:https").expect_err("non-numeric port must be rejected");
        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    }

    #[test]
    fn split_target_authority_splits_domain_target() {
        let (host, port) = split_target_authority("example.com:443").expect("domain target parses");
        assert_eq!(host, "example.com");
        assert_eq!(port, 443);
    }
}
