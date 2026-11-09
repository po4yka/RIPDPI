use hickory_proto::op::{Header, Message, Query};
use hickory_proto::rr::rdata::svcb::{SvcParamKey, SvcParamValue};
use hickory_proto::rr::{Name, RData, Record};
use hickory_proto::serialize::binary::{BinDecodable, BinDecoder, DecodeError};

use super::dto::{HttpsRr, HttpsRrRecordType, HttpsServiceSelection, HttpsSvcbParseError};
use super::ech::parse_ech_config_list;
use crate::odoh::ODOHCONFIG_SVCB_KEY;

pub fn parse_https_service_bindings(packet: &[u8]) -> Result<Vec<HttpsRr>, HttpsSvcbParseError> {
    let (message, malformed) = parse_checked_message(packet)?;
    reject_malformed(&malformed)?;
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
    let (message, malformed) = parse_checked_message(packet)?;
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
    // A malformed record spoils only its own RRset.
    reject_malformed(
        &malformed.iter().filter(|(owner, _)| owners.contains(&owner)).cloned().collect::<Vec<MalformedRr>>(),
    )?;

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

/// An SVCB or HTTPS RR with a malformed SvcParam length: its owner name and RR type.
type MalformedRr = (Name, u16);

fn parse_checked_message(packet: &[u8]) -> Result<(Message, Vec<MalformedRr>), HttpsSvcbParseError> {
    let response_error = |error: &dyn std::fmt::Display| HttpsSvcbParseError::Response(error.to_string());
    let message = Message::from_vec(packet).map_err(|error| response_error(&error))?;
    let malformed = malformed_service_binding_records(packet).map_err(|error| response_error(&error))?;
    Ok((message, malformed))
}

fn reject_malformed(malformed: &[MalformedRr]) -> Result<(), HttpsSvcbParseError> {
    if malformed.is_empty() {
        Ok(())
    } else {
        Err(HttpsSvcbParseError::Response("SvcParam length is malformed".to_string()))
    }
}

const RR_TYPE_SVCB: u16 = 64;
const RR_TYPE_HTTPS: u16 = 65;

/// Walks the raw packet and lists the SVCB or HTTPS RRs whose `port` is not 2
/// octets or whose `ipv4hint` or `ipv6hint` list is empty or not whole
/// addresses (RFC 9460 2.2, 7.2, 7.3): the client must reject the whole RRset
/// of such an RR, and hickory accepts these values.
fn malformed_service_binding_records(packet: &[u8]) -> Result<Vec<MalformedRr>, DecodeError> {
    let mut malformed = Vec::new();
    let mut decoder = BinDecoder::new(packet);
    let header = Header::read(&mut decoder)?;
    for _ in 0..header.counts.queries {
        Query::read(&mut decoder)?;
    }
    let records =
        u32::from(header.counts.answers) + u32::from(header.counts.authorities) + u32::from(header.counts.additionals);
    for _ in 0..records {
        let owner = Name::read(&mut decoder)?;
        let record_type = decoder.read_u16()?.unverified();
        decoder.read_u16()?; // class
        decoder.read_u32()?; // ttl
        let rdata_len = usize::from(decoder.read_u16()?.unverified());
        // Hickory decodes an empty RDATA as an update record, not as a binding.
        if rdata_len == 0 || (record_type != RR_TYPE_SVCB && record_type != RR_TYPE_HTTPS) {
            decoder.read_slice(rdata_len)?;
            continue;
        }
        let rdata_end = decoder.index() + rdata_len;
        decoder.read_u16()?; // priority
        Name::read(&mut decoder)?;
        let mut valid_record = true;
        while decoder.index() < rdata_end {
            let key = decoder.read_u16()?.unverified();
            let len = usize::from(decoder.read_u16()?.unverified());
            decoder.read_slice(len)?;
            valid_record &= match key {
                3 => len == 2,
                4 => len > 0 && len % 4 == 0,
                6 => len > 0 && len % 16 == 0,
                _ => true,
            };
        }
        if !valid_record {
            malformed.push((owner, record_type));
        }
    }
    Ok(malformed)
}

fn service_params_are_compatible(params: &[(SvcParamKey, SvcParamValue)]) -> bool {
    // Hickory validates parameter order; the wire scan checks lengths; neither checks RFC 9460 self-consistency.
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
