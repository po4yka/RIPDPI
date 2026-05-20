use std::io;

use ripdpi_config::{RuntimeConfig, TcpChainStepKind, UdpChainStepKind};
use ripdpi_runtime_platform::raw_packet::{probe_ip_fragmentation_capabilities, IpFragmentationCapabilities};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RawPacketRequirements {
    protect_path: Option<String>,
    root_helper_socket_path: Option<String>,
    ipv6_enabled: bool,
    requires_packet_owned_tcp: bool,
    requires_udp_ipfrag: bool,
}

pub fn raw_packet_requirements(config: &RuntimeConfig) -> RawPacketRequirements {
    RawPacketRequirements {
        protect_path: config.process.protect_path.clone(),
        root_helper_socket_path: config.process.root_helper_socket_path.clone(),
        ipv6_enabled: config.network.ipv6,
        requires_packet_owned_tcp: requires_packet_owned_tcp(config),
        requires_udp_ipfrag: requires_udp_ipfrag(config),
    }
}

pub fn validate_ip_fragmentation_support(requirements: &RawPacketRequirements) -> io::Result<()> {
    if !requirements.requires_raw_packet_desync() {
        return Ok(());
    }

    let _root_helper = RootHelperProbeRegistration::for_path(requirements.root_helper_socket_path.as_deref());
    let capabilities = probe_ip_fragmentation_capabilities(requirements.protect_path.as_deref())?;
    validate_ip_fragmentation_capabilities(requirements, capabilities)
}

impl RawPacketRequirements {
    fn requires_raw_packet_desync(&self) -> bool {
        self.requires_packet_owned_tcp || self.requires_udp_ipfrag
    }
}

fn requires_packet_owned_tcp(config: &RuntimeConfig) -> bool {
    config
        .groups
        .iter()
        .flat_map(ripdpi_config::DesyncGroup::effective_tcp_chain)
        .any(|step| matches!(step.kind(), TcpChainStepKind::IpFrag2 | TcpChainStepKind::MultiDisorder))
}

fn requires_udp_ipfrag(config: &RuntimeConfig) -> bool {
    config
        .groups
        .iter()
        .flat_map(ripdpi_config::DesyncGroup::effective_udp_chain)
        .any(|step| step.kind == UdpChainStepKind::IpFrag2Udp)
}

fn validate_ip_fragmentation_capabilities(
    requirements: &RawPacketRequirements,
    capabilities: IpFragmentationCapabilities,
) -> io::Result<()> {
    let requires_packet_owned_tcp = requirements.requires_packet_owned_tcp;
    let requires_udp_ipfrag = requirements.requires_udp_ipfrag;
    if !requires_packet_owned_tcp && !requires_udp_ipfrag {
        return Ok(());
    }

    let mut missing = Vec::new();
    if (requires_packet_owned_tcp || requires_udp_ipfrag) && !capabilities.raw_ipv4 {
        missing.push("raw IPv4 sockets");
    }
    if (requires_packet_owned_tcp || requires_udp_ipfrag) && requirements.ipv6_enabled && !capabilities.raw_ipv6 {
        missing.push("raw IPv6 sockets");
    }
    if requires_packet_owned_tcp && !capabilities.tcp_repair {
        missing.push("TCP repair");
    }
    if missing.is_empty() {
        Ok(())
    } else {
        Err(io::Error::new(io::ErrorKind::Unsupported, format!("raw-packet desync requires {}", missing.join(", "))))
    }
}

/// Registration token carried by the `RootHelperProbeRegistration` RAII guard.
///
/// A `RootHelperGeneration` on platforms with a root helper; `()` elsewhere,
/// where there is nothing to register. The portable alias keeps the guard
/// struct compilable on every target while the `root_helper` module itself is
/// `linux`/`android`-only.
#[cfg(any(target_os = "linux", target_os = "android"))]
type RootHelperToken = crate::platform::root_helper::RootHelperGeneration;
#[cfg(not(any(target_os = "linux", target_os = "android")))]
type RootHelperToken = ();

struct RootHelperProbeRegistration {
    /// `Some(token)` once this guard registered the root helper client; `None`
    /// when registration was skipped (helper already registered, or no usable
    /// path). Releasing through the generation token makes a stale `Drop` from
    /// a superseded session a no-op rather than clearing a newer session's
    /// registration.
    generation: Option<RootHelperToken>,
}

impl RootHelperProbeRegistration {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn for_path(path: Option<&str>) -> Self {
        if crate::platform::root_helper::has_root_helper() {
            return Self { generation: None };
        }
        let Some(path) = path.map(str::trim).filter(|path| !path.is_empty()) else {
            return Self { generation: None };
        };
        let generation = crate::platform::root_helper::register_root_helper_versioned(path.to_owned());
        Self { generation: Some(generation) }
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    fn for_path(_path: Option<&str>) -> Self {
        Self { generation: None }
    }
}

impl Drop for RootHelperProbeRegistration {
    fn drop(&mut self) {
        if let Some(generation) = self.generation {
            unregister_root_helper(generation);
        }
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn unregister_root_helper(generation: RootHelperToken) {
    crate::platform::root_helper::unregister_root_helper_if(generation);
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn unregister_root_helper(_generation: RootHelperToken) {}

#[cfg(test)]
mod tests {
    use std::io::ErrorKind;

    use ripdpi_config::{
        DesyncGroup, OffsetExpr, RuntimeConfig, TcpChainStep, TcpChainStepKind, UdpChainStep, UdpChainStepKind,
    };

    use super::{raw_packet_requirements, validate_ip_fragmentation_capabilities};
    use crate::platform::raw_packet::IpFragmentationCapabilities;

    fn runtime_config_with_ipfrag(tcp: bool, udp: bool, ipv6: bool) -> RuntimeConfig {
        let mut config = RuntimeConfig::default();
        config.network.ipv6 = ipv6;
        let mut group = DesyncGroup::new(0);
        if tcp {
            group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::IpFrag2, OffsetExpr::host(2)));
        }
        if udp {
            group.actions.udp_chain.push(UdpChainStep {
                kind: UdpChainStepKind::IpFrag2Udp,
                count: 0,
                split_bytes: 8,
                activation_filter: None,
                ip_frag_disorder: false,
                ipv6_hop_by_hop: false,
                ipv6_dest_opt: false,
                ipv6_dest_opt2: false,
                ipv6_frag_next_override: None,
            });
        }
        config.groups = vec![group];
        config
    }

    fn runtime_config_with_multidisorder(ipv6: bool) -> RuntimeConfig {
        let mut config = RuntimeConfig::default();
        config.network.ipv6 = ipv6;
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::host(0)));
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::host(2)));
        config.groups = vec![group];
        config
    }

    #[test]
    fn ipfrag_capability_validation_allows_non_fragmenting_configs() {
        let config = RuntimeConfig::default();

        let requirements = raw_packet_requirements(&config);

        validate_ip_fragmentation_capabilities(&requirements, IpFragmentationCapabilities::default())
            .expect("non-ipfrag configs should skip capability gating");
    }

    #[test]
    fn ipfrag_capability_validation_requires_ipv6_raw_socket_when_enabled() {
        let config = runtime_config_with_ipfrag(false, true, true);
        let requirements = raw_packet_requirements(&config);
        let err = validate_ip_fragmentation_capabilities(
            &requirements,
            IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: false, tcp_repair: false },
        )
        .expect_err("ipv6 ipfrag should require raw ipv6");

        assert_eq!(err.kind(), ErrorKind::Unsupported);
        assert!(err.to_string().contains("raw IPv6 sockets"));
    }

    #[test]
    fn ipfrag_capability_validation_requires_tcp_repair_for_tcp_steps() {
        let config = runtime_config_with_ipfrag(true, false, false);
        let requirements = raw_packet_requirements(&config);
        let err = validate_ip_fragmentation_capabilities(
            &requirements,
            IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: false, tcp_repair: false },
        )
        .expect_err("tcp ipfrag should require tcp repair");

        assert_eq!(err.kind(), ErrorKind::Unsupported);
        assert!(err.to_string().contains("TCP repair"));
    }

    #[test]
    fn multidisorder_capability_validation_requires_tcp_repair_for_packet_owned_tcp() {
        let config = runtime_config_with_multidisorder(false);
        let requirements = raw_packet_requirements(&config);
        let err = validate_ip_fragmentation_capabilities(
            &requirements,
            IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: false, tcp_repair: false },
        )
        .expect_err("multidisorder should require tcp repair");

        assert_eq!(err.kind(), ErrorKind::Unsupported);
        assert!(err.to_string().contains("TCP repair"));
    }

    #[test]
    fn multidisorder_capability_validation_accepts_raw_sockets_and_tcp_repair() {
        let config = runtime_config_with_multidisorder(true);

        let requirements = raw_packet_requirements(&config);

        validate_ip_fragmentation_capabilities(
            &requirements,
            IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: true, tcp_repair: true },
        )
        .expect("multidisorder should pass when raw sockets and tcp repair are available");
    }

    #[test]
    fn raw_packet_requirements_captures_root_helper_socket_path() {
        let mut config = runtime_config_with_multidisorder(false);
        config.process.root_helper_socket_path = Some(" /data/app/root_helper.sock ".to_string());

        let requirements = raw_packet_requirements(&config);

        assert_eq!(requirements.root_helper_socket_path.as_deref(), Some(" /data/app/root_helper.sock "));
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    #[test]
    fn root_helper_probe_registration_registers_trimmed_path_until_drop() {
        use super::RootHelperProbeRegistration;

        crate::platform::root_helper::unregister_root_helper();
        {
            let _guard = RootHelperProbeRegistration::for_path(Some(" /tmp/ripdpi-probe-helper.sock "));
            let path = crate::platform::root_helper::with_root_helper(
                ripdpi_runtime_platform::root_helper_client::RootHelperClient::socket_path,
            );
            assert_eq!(path.as_deref(), Some("/tmp/ripdpi-probe-helper.sock"));
        }

        assert!(!crate::platform::root_helper::has_root_helper());
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    #[test]
    fn root_helper_probe_registration_preserves_existing_registration() {
        use super::RootHelperProbeRegistration;

        crate::platform::root_helper::unregister_root_helper();
        crate::platform::root_helper::register_root_helper("/tmp/ripdpi-existing-helper.sock".to_string());
        {
            let _guard = RootHelperProbeRegistration::for_path(Some("/tmp/ripdpi-probe-helper.sock"));
            let path = crate::platform::root_helper::with_root_helper(
                ripdpi_runtime_platform::root_helper_client::RootHelperClient::socket_path,
            );
            assert_eq!(path.as_deref(), Some("/tmp/ripdpi-existing-helper.sock"));
        }

        let path = crate::platform::root_helper::with_root_helper(
            ripdpi_runtime_platform::root_helper_client::RootHelperClient::socket_path,
        );
        assert_eq!(path.as_deref(), Some("/tmp/ripdpi-existing-helper.sock"));
        crate::platform::root_helper::unregister_root_helper();
    }

    #[test]
    fn ipfrag_capability_validation_does_not_require_ipv6_when_disabled() {
        let config = runtime_config_with_ipfrag(false, true, false);

        let requirements = raw_packet_requirements(&config);

        validate_ip_fragmentation_capabilities(
            &requirements,
            IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: false, tcp_repair: false },
        )
        .expect("ipv4-only ipfrag should not require raw ipv6");
    }

    #[test]
    fn ipfrag_capability_helpers_distinguish_tcp_and_udp_requirements() {
        let udp_only = IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: true, tcp_repair: false };
        assert!(udp_only.supports_udp_ip_fragmentation(true));
        assert!(!udp_only.supports_tcp_ip_fragmentation(true));

        let tcp_and_udp = IpFragmentationCapabilities { raw_ipv4: true, raw_ipv6: true, tcp_repair: true };
        assert!(tcp_and_udp.supports_udp_ip_fragmentation(true));
        assert!(tcp_and_udp.supports_tcp_ip_fragmentation(true));
    }
}
