use ripdpi_config::{
    EntropyMode, FakePacketSource, IpIdMode, TcpChainStepKind, WsizeConfig, FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND,
    FM_RNDSNI,
};
use ripdpi_packets::{HttpFakeProfile, TlsFakeProfile, UdpFakeProfile};

use crate::types::{
    ProxyConfigError, ProxyUiFakePacketConfig, ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK, FAKE_TLS_SNI_MODE_FIXED,
    FAKE_TLS_SNI_MODE_RANDOMIZED, FAKE_TLS_SOURCE_CAPTURED_CLIENT_HELLO, FAKE_TLS_SOURCE_PROFILE, IP_ID_MODE_RND,
    IP_ID_MODE_SEQ, IP_ID_MODE_SEQGROUP, IP_ID_MODE_ZERO,
};

use super::legacy_payload_adapter::parse_offset_expr_field;

pub(crate) fn apply_fake_packet_section(
    group: &mut ripdpi_config::DesyncGroup,
    fake_packets: &ProxyUiFakePacketConfig,
) -> Result<(), ProxyConfigError> {
    apply_ttl_settings(group, fake_packets)?;

    group.actions.http_fake_profile = parse_http_fake_profile(&fake_packets.http_fake_profile)?;
    group.actions.tls_fake_profile = parse_tls_fake_profile(&fake_packets.tls_fake_profile)?;
    group.actions.udp_fake_profile = parse_udp_fake_profile(&fake_packets.udp_fake_profile)?;
    group.actions.fake_tls_source = match fake_packets.fake_tls_source.trim().to_ascii_lowercase().as_str() {
        FAKE_TLS_SOURCE_PROFILE => FakePacketSource::Profile,
        FAKE_TLS_SOURCE_CAPTURED_CLIENT_HELLO => FakePacketSource::CapturedClientHello,
        _ => {
            return Err(ProxyConfigError::InvalidConfig("Invalid fakePackets.fakeTlsSource".to_string()));
        }
    };
    group.actions.fake_tls_secondary_profile = if fake_packets.fake_tls_secondary_profile.trim().is_empty() {
        None
    } else {
        Some(parse_tls_fake_profile(&fake_packets.fake_tls_secondary_profile)?)
    };
    group.actions.fake_tcp_timestamp_enabled = fake_packets.fake_tcp_timestamp_enabled;
    group.actions.fake_tcp_timestamp_delta_ticks = fake_packets.fake_tcp_timestamp_delta_ticks;
    group.actions.drop_sack = fake_packets.drop_sack;
    group.actions.window_clamp = fake_packets.window_clamp;
    group.actions.wsize = fake_packets.wsize_window.filter(|&window| window > 0).map(|window| WsizeConfig {
        window,
        scale: fake_packets.wsize_scale.and_then(|value| if value >= 0 { Some(value as u8) } else { None }),
    });
    group.actions.strip_timestamps = fake_packets.strip_timestamps;
    group.actions.ip_id_mode = parse_ip_id_mode(&fake_packets.ip_id_mode)?;
    group.actions.quic_bind_low_port = fake_packets.quic_bind_low_port;
    group.actions.quic_migrate_after_handshake = fake_packets.quic_migrate_after_handshake;
    if let Some(version) = fake_packets.quic_fake_version {
        group.actions.quic_fake_version = version;
    }

    group.actions.entropy_mode = match fake_packets.entropy_mode.as_str() {
        "popcount" => EntropyMode::Popcount,
        "shannon" => EntropyMode::Shannon,
        "combined" => EntropyMode::Combined,
        _ => EntropyMode::Disabled,
    };
    if let Some(value) = fake_packets.entropy_padding_target_permil {
        if value > 0 {
            group.actions.entropy_padding_target_permil = Some(value);
        }
    }
    if let Some(value) = fake_packets.entropy_padding_max {
        if value > 0 {
            group.actions.entropy_padding_max = value;
        }
    }
    if let Some(value) = fake_packets.shannon_entropy_target_permil {
        if value > 0 {
            group.actions.shannon_entropy_target_permil = Some(value);
        }
    }

    let has_fake_step = group.effective_tcp_chain().iter().any(|step| {
        matches!(step.kind, TcpChainStepKind::Fake | TcpChainStepKind::FakeSplit | TcpChainStepKind::FakeDisorder)
    });
    let has_oob_step = group
        .effective_tcp_chain()
        .iter()
        .any(|step| matches!(step.kind, TcpChainStepKind::Oob | TcpChainStepKind::Disoob));

    if has_fake_step {
        let fake_tls_sni_mode = normalize_fake_tls_sni_mode(&fake_packets.fake_tls_sni_mode);
        let fake_offset = parse_offset_expr_field(
            Some(fake_packets.fake_offset_marker.as_str()),
            "0",
            "fakePackets.fakeOffsetMarker",
        )?;
        if !fake_offset.supports_fake_offset() {
            return Err(ProxyConfigError::InvalidConfig("Invalid fakePackets.fakeOffsetMarker".to_string()));
        }

        group.actions.fake_offset = Some(fake_offset);
        if fake_packets.fake_tls_use_original {
            group.actions.fake_mod |= FM_ORIG;
        }
        if fake_packets.fake_tls_randomize {
            group.actions.fake_mod |= FM_RAND;
        }
        if fake_packets.fake_tls_dup_session_id {
            group.actions.fake_mod |= FM_DUPSID;
        }
        if fake_packets.fake_tls_pad_encap {
            group.actions.fake_mod |= FM_PADENCAP;
        }
        if fake_tls_sni_mode == FAKE_TLS_SNI_MODE_RANDOMIZED {
            group.actions.fake_mod |= FM_RNDSNI;
        } else {
            group.actions.fake_sni_list.push(fake_packets.fake_sni.clone());
        }
        if fake_packets.fake_tls_size < -65535 || fake_packets.fake_tls_size > 65535 {
            return Err(ProxyConfigError::InvalidConfig("fakeTlsSize must be in -65535..=65535".to_string()));
        }
        group.actions.fake_tls_size = fake_packets.fake_tls_size;
    }

    if has_oob_step {
        group.actions.oob_data = Some(fake_packets.oob_char);
    }

    Ok(())
}

pub fn normalize_fake_tls_sni_mode(value: &str) -> &'static str {
    match value.trim().to_ascii_lowercase().as_str() {
        FAKE_TLS_SNI_MODE_RANDOMIZED => FAKE_TLS_SNI_MODE_RANDOMIZED,
        _ => FAKE_TLS_SNI_MODE_FIXED,
    }
}

pub fn parse_http_fake_profile(value: &str) -> Result<HttpFakeProfile, ProxyConfigError> {
    ripdpi_config::parse_http_fake_profile(value)
        .map_err(|_| ProxyConfigError::InvalidConfig(format!("Unknown httpFakeProfile: {value}")))
}

pub fn parse_tls_fake_profile(value: &str) -> Result<TlsFakeProfile, ProxyConfigError> {
    ripdpi_config::parse_tls_fake_profile(value)
        .map_err(|_| ProxyConfigError::InvalidConfig(format!("Unknown tlsFakeProfile: {value}")))
}

pub fn parse_udp_fake_profile(value: &str) -> Result<UdpFakeProfile, ProxyConfigError> {
    ripdpi_config::parse_udp_fake_profile(value)
        .map_err(|_| ProxyConfigError::InvalidConfig(format!("Unknown udpFakeProfile: {value}")))
}

fn apply_ttl_settings(
    group: &mut ripdpi_config::DesyncGroup,
    fake_packets: &ProxyUiFakePacketConfig,
) -> Result<(), ProxyConfigError> {
    if fake_packets.adaptive_fake_ttl_enabled {
        let delta = i8::try_from(fake_packets.adaptive_fake_ttl_delta)
            .map_err(|_| ProxyConfigError::InvalidConfig("Invalid adaptiveFakeTtlDelta".to_string()))?;
        let min_ttl = u8::try_from(fake_packets.adaptive_fake_ttl_min)
            .map_err(|_| ProxyConfigError::InvalidConfig("Invalid adaptiveFakeTtlMin".to_string()))?;
        let max_ttl = u8::try_from(fake_packets.adaptive_fake_ttl_max)
            .map_err(|_| ProxyConfigError::InvalidConfig("Invalid adaptiveFakeTtlMax".to_string()))?;
        if min_ttl == 0 || max_ttl == 0 || min_ttl > max_ttl {
            return Err(ProxyConfigError::InvalidConfig("Invalid adaptive fake TTL window".to_string()));
        }

        group.actions.auto_ttl = Some(ripdpi_config::AutoTtlConfig { delta, min_ttl, max_ttl });
        let fallback_ttl = if fake_packets.adaptive_fake_ttl_fallback > 0 {
            fake_packets.adaptive_fake_ttl_fallback
        } else if fake_packets.fake_ttl > 0 {
            fake_packets.fake_ttl
        } else {
            ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK
        };
        group.actions.ttl = Some(
            u8::try_from(fallback_ttl)
                .map_err(|_| ProxyConfigError::InvalidConfig("Invalid adaptiveFakeTtlFallback".to_string()))?,
        );
    } else if fake_packets.fake_ttl > 0 {
        group.actions.ttl = Some(
            u8::try_from(fake_packets.fake_ttl)
                .map_err(|_| ProxyConfigError::InvalidConfig("Invalid fakeTtl".to_string()))?,
        );
    }

    Ok(())
}

fn parse_ip_id_mode(value: &str) -> Result<Option<IpIdMode>, ProxyConfigError> {
    match value.trim().to_ascii_lowercase().as_str() {
        "" => Ok(None),
        IP_ID_MODE_SEQ => Ok(Some(IpIdMode::Seq)),
        IP_ID_MODE_SEQGROUP => Ok(Some(IpIdMode::SeqGroup)),
        IP_ID_MODE_RND => Ok(Some(IpIdMode::Rnd)),
        IP_ID_MODE_ZERO => Ok(Some(IpIdMode::Zero)),
        _ => Err(ProxyConfigError::InvalidConfig(
            "fakePackets.ipIdMode must be seq, seqgroup, rnd, zero, or empty".to_string(),
        )),
    }
}
