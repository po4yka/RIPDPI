mod adaptive_runtime_context;
mod chain;
mod fake_packet;
mod legacy_payload_adapter;
mod listen;
mod protocol;
mod relay;
mod shared;
mod warp;

use ripdpi_config::{RuntimeConfig, StartupEnv};

use crate::presets;
use crate::types::{ProxyConfigError, ProxyConfigPayload, ProxyUiConfig, RuntimeConfigEnvelope};

pub use chain::{parse_tcp_chain_step_kind, parse_udp_chain_step_kind};
pub use fake_packet::{
    normalize_fake_tls_sni_mode, parse_http_fake_profile, parse_tls_fake_profile, parse_udp_fake_profile,
};
pub use legacy_payload_adapter::{parse_desync_mode, parse_proxy_config_json};
pub use protocol::{parse_quic_fake_profile, parse_quic_initial_mode};

pub fn runtime_config_from_payload(payload: ProxyConfigPayload) -> Result<RuntimeConfig, ProxyConfigError> {
    runtime_config_envelope_from_payload(payload).map(|envelope| envelope.config)
}

pub fn runtime_config_envelope_from_payload(
    payload: ProxyConfigPayload,
) -> Result<RuntimeConfigEnvelope, ProxyConfigError> {
    match payload {
        ProxyConfigPayload::CommandLine {
            args,
            host_autolearn_store_path,
            runtime_context,
            log_context,
            session_overrides,
        } => {
            let mut config = runtime_config_from_command_line(args)?;
            config.host_autolearn.store_path = host_autolearn_store_path
                .as_deref()
                .map(str::trim)
                .filter(|value| !value.is_empty())
                .map(ToOwned::to_owned);
            adaptive_runtime_context::apply_session_overrides(&mut config, session_overrides)?;
            Ok(RuntimeConfigEnvelope {
                config,
                runtime_context: adaptive_runtime_context::sanitize_runtime_context(runtime_context),
                log_context: adaptive_runtime_context::sanitize_log_context(log_context),
                native_log_level: None,
            })
        }
        ProxyConfigPayload::Ui { strategy_preset, mut config, runtime_context, log_context, session_overrides } => {
            let preset_id_opt = strategy_preset.as_deref().map(str::to_owned);
            if let Some(ref preset_id) = preset_id_opt {
                presets::apply_preset(preset_id, &mut config)?;
            }

            let native_log_level = config.native_log_level.clone();
            let mut runtime_config = runtime_config_from_ui(config)?;
            let runtime_preset_id = preset_id_opt.as_deref().unwrap_or("ripdpi_default");
            presets::apply_runtime_preset(runtime_preset_id, &mut runtime_config)?;
            adaptive_runtime_context::apply_session_overrides(&mut runtime_config, session_overrides)?;

            Ok(RuntimeConfigEnvelope {
                config: runtime_config,
                runtime_context: adaptive_runtime_context::sanitize_runtime_context(runtime_context),
                log_context: adaptive_runtime_context::sanitize_log_context(log_context),
                native_log_level,
            })
        }
    }
}

pub fn runtime_config_from_command_line(mut args: Vec<String>) -> Result<RuntimeConfig, ProxyConfigError> {
    if args.first().is_some_and(|value| !value.starts_with('-')) {
        args.remove(0);
    }

    let parsed = ripdpi_config::parse_cli(&args, &StartupEnv::default())
        .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid command-line proxy config: {}", err.option)))?;

    match parsed {
        ripdpi_config::ParseResult::Run(config) => Ok(*config),
        _ => Err(ProxyConfigError::InvalidConfig(
            "Command-line proxy config must resolve to a runnable config".to_string(),
        )),
    }
}

pub fn runtime_config_from_ui(payload: ProxyUiConfig) -> Result<RuntimeConfig, ProxyConfigError> {
    let ProxyUiConfig {
        listen,
        protocols,
        chains,
        fake_packets,
        parser_evasions,
        adaptive_fallback,
        quic,
        hosts,
        upstream_relay,
        warp,
        host_autolearn,
        ws_tunnel,
        native_log_level: _,
        root_mode,
        root_helper_socket_path,
        environment_kind,
    } = payload;

    let mut config = RuntimeConfig::default();
    listen::apply_listen_section(&mut config, &listen)?;
    protocol::apply_protocol_section(&mut config, &protocols, &quic)?;
    adaptive_runtime_context::apply_runtime_section(&mut config, &adaptive_fallback, &host_autolearn, &ws_tunnel);

    let mut groups = Vec::new();
    warp::append_control_plane_group(&mut groups, &warp)?;
    protocol::append_whitelist_group(&mut groups, &hosts)?;
    warp::append_routed_group(&mut groups, &warp)?;

    let relay_upstream = relay::build_upstream(&upstream_relay)?;
    relay::attach_upstream_to_existing_groups(&mut groups, relay_upstream);

    let (mut primary_group, group_protocol_state) =
        protocol::build_primary_group(groups.len(), &hosts, &protocols, &quic, &parser_evasions)?;
    chain::apply_chain_section(&mut primary_group, &chains)?;
    fake_packet::apply_fake_packet_section(&mut primary_group, &fake_packets)?;
    relay::attach_upstream_to_group(&mut primary_group, relay_upstream);

    let primary_group_snapshot = primary_group.clone();
    groups.push(primary_group);
    protocol::append_udp_group(&mut groups, &primary_group_snapshot, group_protocol_state.udp_enabled);
    adaptive_runtime_context::append_fallback_groups(
        &mut groups,
        &config,
        group_protocol_state.tcp_proto,
        group_protocol_state.udp_enabled,
        &adaptive_fallback,
    );

    adaptive_runtime_context::finalize_ui_config(
        config,
        groups,
        &listen,
        &host_autolearn,
        root_mode,
        root_helper_socket_path,
        environment_kind.as_deref(),
    )
}
