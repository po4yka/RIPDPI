use std::{fmt, mem};

use ripdpi_config::{DesyncMode, OffsetExpr};
use serde::de::{Deserializer, IgnoredAny, MapAccess, Visitor};

use crate::types::{ProxyConfigError, ProxyConfigPayload};

const GROUPED_UI_KEYS: &[&str] = &[
    "listen",
    "protocols",
    "chains",
    "fakePackets",
    "parserEvasions",
    "adaptiveFallback",
    "quic",
    "hosts",
    "upstreamRelay",
    "warp",
    "hostAutolearn",
    "wsTunnel",
];

const LEGACY_FLAT_UI_KEYS: &[&str] = &[
    "ip",
    "port",
    "maxConnections",
    "bufferSize",
    "tcpFastOpen",
    "defaultTtl",
    "customTtl",
    "noDomain",
    "desyncHttp",
    "desyncHttps",
    "desyncUdp",
    "desyncMethod",
    "splitMarker",
    "tcpChainSteps",
    "groupActivationFilter",
    "splitPosition",
    "splitAtHost",
    "fakeTtl",
    "adaptiveFakeTtlEnabled",
    "adaptiveFakeTtlDelta",
    "adaptiveFakeTtlMin",
    "adaptiveFakeTtlMax",
    "adaptiveFakeTtlFallback",
    "fakeSni",
    "httpFakeProfile",
    "fakeTlsUseOriginal",
    "fakeTlsRandomize",
    "fakeTlsDupSessionId",
    "fakeTlsPadEncap",
    "fakeTlsSize",
    "fakeTlsSniMode",
    "tlsFakeProfile",
    "oobChar",
    "hostMixedCase",
    "domainMixedCase",
    "hostRemoveSpaces",
    "httpMethodEol",
    "httpMethodSpace",
    "httpUnixEol",
    "httpHostPad",
    "tlsRecordSplit",
    "tlsRecordSplitMarker",
    "tlsRecordSplitPosition",
    "tlsRecordSplitAtSni",
    "hostsMode",
    "udpFakeCount",
    "udpChainSteps",
    "udpFakeProfile",
    "dropSack",
    "fakeOffsetMarker",
    "fakeOffset",
    "quicInitialMode",
    "quicSupportV1",
    "quicSupportV2",
    "quicFakeProfile",
    "quicFakeHost",
    "hostAutolearnEnabled",
    "hostAutolearnPenaltyTtlSecs",
    "hostAutolearnPenaltyTtlHours",
    "hostAutolearnMaxHosts",
    "hostAutolearnStorePath",
    "networkScopeKey",
    "adaptiveFallbackEnabled",
    "adaptiveFallbackTorst",
    "adaptiveFallbackTlsErr",
    "adaptiveFallbackHttpRedirect",
    "adaptiveFallbackConnectFailure",
    "adaptiveFallbackAutoSort",
    "adaptiveFallbackCacheTtlSeconds",
    "adaptiveFallbackCachePrefixV4",
];

pub fn parse_proxy_config_json(json: &str) -> Result<ProxyConfigPayload, ProxyConfigError> {
    validate_ui_payload_shape(json)?;
    serde_json::from_str::<ProxyConfigPayload>(json)
        .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid proxy config JSON: {err}")))
}

pub fn parse_desync_mode(value: &str) -> Result<DesyncMode, ProxyConfigError> {
    match value {
        "none" => Ok(DesyncMode::None),
        "split" => Ok(DesyncMode::Split),
        "disorder" => Ok(DesyncMode::Disorder),
        "fake" => Ok(DesyncMode::Fake),
        "oob" => Ok(DesyncMode::Oob),
        "disoob" => Ok(DesyncMode::Disoob),
        _ => Err(ProxyConfigError::InvalidConfig("Unknown desyncMethod".to_string())),
    }
}

pub(crate) fn parse_offset_expr_field(
    marker: Option<&str>,
    legacy_spec: &str,
    field_name: &str,
) -> Result<OffsetExpr, ProxyConfigError> {
    let spec = marker.map(str::trim).filter(|value| !value.is_empty()).unwrap_or(legacy_spec);
    ripdpi_config::parse_offset_expr(spec).map_err(|_| ProxyConfigError::InvalidConfig(format!("Invalid {field_name}")))
}

fn validate_ui_payload_shape(json: &str) -> Result<(), ProxyConfigError> {
    let shape = serde_json::Deserializer::from_str(json)
        .deserialize_any(UiPayloadShapeVisitor)
        .map_err(|err| ProxyConfigError::InvalidConfig(format!("Invalid proxy config JSON: {err}")))?;

    if !shape.is_ui {
        return Ok(());
    }
    if let Some(legacy_key) = shape.legacy_key {
        return Err(ProxyConfigError::InvalidConfig(format!(
            "Legacy flat UI config JSON is not supported: {legacy_key}"
        )));
    }
    if !shape.has_grouped_key {
        return Err(ProxyConfigError::InvalidConfig(
            "Grouped UI config JSON must include at least one nested section".to_string(),
        ));
    }

    Ok(())
}

#[derive(Default)]
struct UiPayloadShape {
    is_ui: bool,
    has_grouped_key: bool,
    legacy_key: Option<String>,
}

struct UiPayloadShapeVisitor;

impl<'de> Visitor<'de> for UiPayloadShapeVisitor {
    type Value = UiPayloadShape;

    fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("a proxy config JSON object")
    }

    fn visit_map<A>(self, mut map: A) -> Result<Self::Value, A::Error>
    where
        A: MapAccess<'de>,
    {
        let mut shape = UiPayloadShape::default();
        while let Some(mut key) = map.next_key::<String>()? {
            if key == "kind" {
                let kind = map.next_value::<serde_json::Value>()?;
                if kind.as_str() == Some("ui") {
                    shape.is_ui = true;
                }
                continue;
            }

            if GROUPED_UI_KEYS.contains(&key.as_str()) {
                shape.has_grouped_key = true;
            }
            if shape.legacy_key.is_none() && LEGACY_FLAT_UI_KEYS.contains(&key.as_str()) {
                shape.legacy_key = Some(mem::take(&mut key));
            }

            map.next_value::<IgnoredAny>()?;
        }

        Ok(shape)
    }
}
