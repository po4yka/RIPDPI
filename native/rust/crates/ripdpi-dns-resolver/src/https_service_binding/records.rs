use hickory_proto::op::Message;
use hickory_proto::rr::rdata::svcb::{SvcParamKey, SvcParamValue};
use hickory_proto::rr::{Name, RData, Record};

use super::dto::{HttpsRr, HttpsRrRecordType, HttpsServiceSelection, HttpsSvcbParseError};
use super::ech::parse_ech_config_list;
use crate::odoh::ODOHCONFIG_SVCB_KEY;

pub fn parse_https_service_bindings(packet: &[u8]) -> Result<Vec<HttpsRr>, HttpsSvcbParseError> {
    let message = Message::from_vec(packet).map_err(|error| HttpsSvcbParseError::Response(error.to_string()))?;
    let mut bindings = Vec::new();

    for record in message.answers.iter().chain(message.authorities.iter()).chain(message.additionals.iter()) {
        match &record.data {
            RData::HTTPS(https) => bindings.push(parse_service_binding_record(
                record,
                HttpsRrRecordType::Https,
                https.svc_priority,
                https.target_name.to_ascii(),
                &https.svc_params,
            )?),
            RData::SVCB(svcb) => bindings.push(parse_service_binding_record(
                record,
                HttpsRrRecordType::Svcb,
                svcb.svc_priority,
                svcb.target_name.to_ascii(),
                &svcb.svc_params,
            )?),
            _ => {}
        }
    }

    Ok(bindings)
}

/// Selects the service bindings an HTTPS or SVCB answer gives for its query
/// name (RFC 9460 2.4 and 3): only answer-section records owned by the query
/// name or a name on its CNAME chain count. An AliasMode record makes the
/// ServiceMode records in the set ignored and asks the caller to query its
/// TargetName; ServiceMode records come back in ascending SvcPriority order.
pub fn select_https_service_bindings(packet: &[u8]) -> Result<HttpsServiceSelection, HttpsSvcbParseError> {
    let message = Message::from_vec(packet).map_err(|error| HttpsSvcbParseError::Response(error.to_string()))?;
    let Some(question) = message.queries.first() else {
        return Ok(HttpsServiceSelection::Service(Vec::new()));
    };
    let mut owners: Vec<&Name> = vec![&question.name];
    while let Some(target) = message.answers.iter().find_map(|record| match &record.data {
        RData::CNAME(target) if owners.contains(&&record.name) && !owners.contains(&&target.0) => Some(&target.0),
        _ => None,
    }) {
        owners.push(target);
    }

    let mut bindings = Vec::new();
    for record in message.answers.iter().filter(|record| owners.contains(&&record.name)) {
        let (record_type, svcb) = match &record.data {
            RData::HTTPS(https) => (HttpsRrRecordType::Https, &https.0),
            RData::SVCB(svcb) => (HttpsRrRecordType::Svcb, svcb),
            _ => continue,
        };
        if svcb.svc_priority == 0 {
            // AliasMode: its SvcParams are ignored (2.4.2). TargetName "." means
            // the service is not available.
            return Ok(if svcb.target_name.is_root() {
                HttpsServiceSelection::Service(Vec::new())
            } else {
                HttpsServiceSelection::Alias(svcb.target_name.to_ascii())
            });
        }
        if !service_params_are_compatible(&svcb.svc_params) {
            continue;
        }
        bindings.push(parse_service_binding_record(
            record,
            record_type,
            svcb.svc_priority,
            svcb.target_name.to_ascii(),
            &svcb.svc_params,
        )?);
    }
    // shortcut: equal priorities keep wire order instead of RFC 9460's random order.
    bindings.sort_by_key(|binding| binding.service_priority);
    Ok(HttpsServiceSelection::Service(bindings))
}

fn service_params_are_compatible(params: &[(SvcParamKey, SvcParamValue)]) -> bool {
    // Hickory validates parameter order and wire formats, but not RFC 9460 self-consistency.
    let has_key = |key| params.binary_search_by_key(&key, |(key, _)| *key).is_ok();
    if has_key(SvcParamKey::NoDefaultAlpn) && !has_key(SvcParamKey::Alpn) {
        return false;
    }
    params.iter().all(|(_, value)| {
        let SvcParamValue::Mandatory(mandatory) = value else {
            return true;
        };
        mandatory.0.windows(2).all(|keys| keys[0] < keys[1])
            && mandatory.0.iter().all(|key| matches!(u16::from(*key), 1..=6 | ODOHCONFIG_SVCB_KEY) && has_key(*key))
    })
}

fn parse_service_binding_record(
    record: &Record,
    record_type: HttpsRrRecordType,
    service_priority: u16,
    target_name: String,
    svc_params: &[(SvcParamKey, SvcParamValue)],
) -> Result<HttpsRr, HttpsSvcbParseError> {
    let mut alpn = Vec::new();
    let mut no_default_alpn = false;
    let mut port = None;
    let mut ipv4_hints = Vec::new();
    let mut ipv6_hints = Vec::new();
    let mut ech_config = None;
    let mut odoh_config = None;

    for (key, param) in svc_params {
        match param {
            SvcParamValue::Alpn(value) => alpn = value.0.clone(),
            SvcParamValue::NoDefaultAlpn => no_default_alpn = true,
            SvcParamValue::Port(value) => port = Some(*value),
            SvcParamValue::Ipv4Hint(value) => ipv4_hints.extend(value.0.iter().map(|addr| addr.0)),
            SvcParamValue::Ipv6Hint(value) => ipv6_hints.extend(value.0.iter().map(|addr| addr.0)),
            SvcParamValue::EchConfigList(value) => ech_config = Some(parse_ech_config_list(&value.0)?),
            SvcParamValue::Unknown(value)
                if matches!(key, SvcParamKey::Key(ODOHCONFIG_SVCB_KEY) | SvcParamKey::Unknown(ODOHCONFIG_SVCB_KEY)) =>
            {
                odoh_config = Some(value.0.clone());
            }
            _ => {}
        }
    }

    Ok(HttpsRr {
        owner_name: record.name.to_ascii(),
        record_type,
        service_priority,
        target_name,
        ttl_secs: record.ttl,
        alpn,
        no_default_alpn,
        port,
        ipv4_hints,
        ipv6_hints,
        ech_capable: ech_config.is_some(),
        ech_config,
        odoh_capable: odoh_config.is_some(),
        odoh_config,
    })
}
