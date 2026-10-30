//! Resolve-only mode: `ripdpi-naiveproxy --resolve <HOST> --server-port <N>`.
//!
//! The upstream Chromium client resolves the proxy hostname through this mode,
//! so the name goes out over RIPDPI's protected DoH. The mode binds no
//! listener, reads no stdin, takes no credentials and prints no `RIPDPI-READY`.

use std::collections::HashSet;
use std::ffi::OsString;
use std::io;
use std::net::{IpAddr, SocketAddr};

use crate::errors::resolve_error_line;
use crate::reachability::{PROBE_STEP_BOUND, PROBE_TIMEOUT, order_by_reachability};

pub(crate) const RESOLVE_FLAG: &str = "--resolve";
const SERVER_PORT_FLAG: &str = "--server-port";
const RESOLVED_PREFIX: &str = "RIPDPI-RESOLVED|";

/// Parsed arguments of the resolve-only mode.
#[derive(Debug, PartialEq, Eq)]
pub(crate) struct ResolveArgs {
    pub(crate) host: String,
    pub(crate) port: u16,
}

/// Accepts exactly `--resolve <HOST> --server-port <N>`. Any other argument,
/// including every relay-mode flag, is an error: the mode must not look as if
/// it honored a flag it ignores.
pub(crate) fn parse_resolve_args(args: impl IntoIterator<Item = OsString>) -> io::Result<ResolveArgs> {
    let invalid = |message: String| io::Error::new(io::ErrorKind::InvalidInput, message);
    let mut args = pico_args::Arguments::from_vec(args.into_iter().collect());
    let host: String = args
        .value_from_str(RESOLVE_FLAG)
        .map_err(|error| invalid(format!("invalid {RESOLVE_FLAG} argument: {error}")))?;
    let host = host.trim().trim_matches(['[', ']']).to_owned();
    if host.is_empty() {
        return Err(invalid(format!("empty {RESOLVE_FLAG} host")));
    }
    let port: u16 = args
        .value_from_str(SERVER_PORT_FLAG)
        .map_err(|error| invalid(format!("invalid {SERVER_PORT_FLAG} argument: {error}")))?;
    let rest = args.finish();
    if !rest.is_empty() {
        return Err(invalid(format!("{RESOLVE_FLAG} accepts only {SERVER_PORT_FLAG}, got {rest:?}")));
    }
    Ok(ResolveArgs { host, port })
}

/// Renders the success line: bare IP literals in resolver order, de-duplicated.
pub(crate) fn render_resolved_line(addrs: &[SocketAddr]) -> io::Result<String> {
    let mut seen: HashSet<IpAddr> = HashSet::new();
    let ips: Vec<String> =
        addrs.iter().map(SocketAddr::ip).filter(|ip| seen.insert(*ip)).map(|ip| ip.to_string()).collect();
    if ips.is_empty() {
        return Err(io::Error::new(io::ErrorKind::NotFound, "DNS resolved to no addresses"));
    }
    Ok(format!("{RESOLVED_PREFIX}{}", ips.join(",")))
}

/// The lookup is always DoH: protected sockets with a protect path, plain
/// direct sockets without one. There is no system-resolver fallback. The
/// answers are then ordered so addresses that accept a TCP connection come
/// first. An IP-literal host is echoed without any lookup or probe.
///
/// # Cancel safety
///
/// cancel-safe: see [`ripdpi_vless::endpoint_resolver::resolve_server_addrs_encrypted`]
/// and [`order_by_reachability`].
pub(crate) async fn resolve_line(args: &ResolveArgs, protect_path: Option<String>) -> io::Result<String> {
    let protect_fn = |path: String| {
        Box::new(move |fd| ripdpi_subprocess_protect::protect_fd(fd, &path))
            as Box<ripdpi_vless::endpoint_resolver::SocketProtectFn>
    };
    let is_literal = args.host.parse::<IpAddr>().is_ok();
    let addrs = ripdpi_vless::endpoint_resolver::resolve_server_addrs_encrypted(
        &args.host,
        args.port,
        None,
        protect_path.clone().map(protect_fn),
    )
    .await?;
    if is_literal {
        return render_resolved_line(&addrs);
    }
    let shared: Option<std::sync::Arc<Box<ripdpi_vless::endpoint_resolver::SocketProtectFn>>> =
        protect_path.map(|path| std::sync::Arc::new(protect_fn(path)));
    let ordered = order_by_reachability(addrs, PROBE_STEP_BOUND, |target| {
        ripdpi_vless::endpoint_resolver::probe_tcp_reachable(target, PROBE_TIMEOUT, shared.clone())
    })
    .await;
    render_resolved_line(&ordered)
}

/// Runs the mode: one stdout line and exit 0, or one error line and exit 1.
///
/// # Cancel safety
///
/// cancel-safe: see [`resolve_line`]; it holds no state of its own.
pub(crate) async fn run_resolve_mode() -> io::Result<()> {
    let outcome = match parse_resolve_args(std::env::args_os().skip(1)) {
        Ok(args) => resolve_line(&args, ripdpi_subprocess_protect::protect_path_from_env()).await,
        Err(error) => Err(error),
    };
    match outcome {
        Ok(line) => {
            println!("{line}");
            Ok(())
        }
        Err(error) => {
            eprintln!("{}", resolve_error_line(&error));
            std::process::exit(1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(values: &[&str]) -> Vec<OsString> {
        values.iter().map(OsString::from).collect()
    }

    fn parsed(host: &str, port: u16) -> ResolveArgs {
        ResolveArgs { host: host.to_owned(), port }
    }

    #[test]
    fn parses_host_and_port_in_either_order() {
        assert_eq!(
            parse_resolve_args(args(&["--resolve", "a.example", "--server-port", "443"])).unwrap(),
            parsed("a.example", 443)
        );
        assert_eq!(
            parse_resolve_args(args(&["--server-port", "8443", "--resolve", "a.example"])).unwrap(),
            parsed("a.example", 8443)
        );
    }

    #[test]
    fn strips_brackets_from_an_ipv6_host() {
        assert_eq!(
            parse_resolve_args(args(&["--resolve", "[::1]", "--server-port", "443"])).unwrap(),
            parsed("::1", 443)
        );
    }

    #[test]
    fn rejects_missing_empty_and_malformed_arguments() {
        for bad in [
            &["--resolve", "a.example"][..],
            &["--resolve", "--server-port", "443"],
            &["--resolve", " ", "--server-port", "443"],
            &["--resolve", "a.example", "--server-port", "70000"],
            &["--resolve", "a.example", "--server-port", "x"],
        ] {
            let error = parse_resolve_args(args(bad)).unwrap_err();
            assert_eq!(error.kind(), io::ErrorKind::InvalidInput, "{bad:?}");
        }
    }

    #[test]
    fn excludes_relay_mode_flags_and_stray_arguments() {
        for extra in [&["--server", "x"][..], &["--listen", "127.0.0.1:1"], &["--probe"], &["stray"]] {
            let mut all = vec!["--resolve", "a.example", "--server-port", "443"];
            all.extend_from_slice(extra);
            assert!(parse_resolve_args(args(&all)).is_err(), "{extra:?}");
        }
    }

    #[test]
    fn renders_bare_ips_in_resolver_order_without_duplicates() {
        let addrs: Vec<SocketAddr> = ["[2001:db8::1]:443", "192.0.2.1:443", "[2001:db8::1]:443", "192.0.2.2:443"]
            .iter()
            .map(|a| a.parse().unwrap())
            .collect();
        assert_eq!(render_resolved_line(&addrs).unwrap(), "RIPDPI-RESOLVED|2001:db8::1,192.0.2.1,192.0.2.2");
    }

    #[test]
    fn an_empty_result_is_an_error() {
        assert_eq!(render_resolved_line(&[]).unwrap_err().kind(), io::ErrorKind::NotFound);
    }

    #[tokio::test]
    async fn ip_literals_are_echoed_without_lookup() {
        // A protect path that cannot work proves that no socket is opened.
        let dead = Some("/nonexistent/protect.sock".to_owned());
        assert_eq!(resolve_line(&parsed("1.2.3.4", 443), dead.clone()).await.unwrap(), "RIPDPI-RESOLVED|1.2.3.4");
        assert_eq!(resolve_line(&parsed("::1", 443), dead).await.unwrap(), "RIPDPI-RESOLVED|::1");
        assert_eq!(resolve_line(&parsed("2001:db8::7", 443), None).await.unwrap(), "RIPDPI-RESOLVED|2001:db8::7");
    }

    #[test]
    fn error_line_is_always_dns_class_and_sanitized() {
        let line = resolve_error_line(&io::Error::new(io::ErrorKind::InvalidInput, "bad|arg\nnext"));
        assert_eq!(line, "RIPDPI-ERROR|naiveproxy|dns|bad/arg next");
    }
}
