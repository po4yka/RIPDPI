use std::collections::BTreeSet;
use std::net::IpAddr;

use hickory_proto::op::{Message, MessageType, OpCode, Query};
use hickory_proto::rr::{DNSClass, Name, RData, RecordType};

use crate::types::EncryptedDnsError;

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub enum IpAnswerFamily {
    Ipv4,
    Ipv6,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct IpAnswerRecord {
    pub ip: IpAddr,
    pub family: IpAnswerFamily,
}

pub(crate) fn build_dns_query(name: &str, record_type: RecordType) -> Result<Vec<u8>, EncryptedDnsError> {
    let id = random_dns_id()?;
    let mut message = Message::new(id, MessageType::Query, OpCode::Query);
    message.metadata.recursion_desired = true;
    message.add_query(Query::new(
        Name::from_ascii(name).map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))?,
        record_type,
    ));
    message.to_vec().map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))
}

/// Validate that a DNS reply answers the query that was actually sent.
pub fn validate_dns_response_for_query(query: &[u8], response: &[u8]) -> Result<(), EncryptedDnsError> {
    let request = Message::from_vec(query).map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))?;
    let reply = Message::from_vec(response).map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))?;
    if request.queries.len() != 1
        || reply.metadata.id != request.metadata.id
        || reply.metadata.message_type != MessageType::Response
        || reply.queries != request.queries
    {
        return Err(EncryptedDnsError::DnsParse("DNS response does not match query".to_string()));
    }

    let mut valid_names = vec![request.queries[0].name.clone()];
    loop {
        let previous_len = valid_names.len();
        for record in &reply.answers {
            if let RData::CNAME(target) = &record.data
                && record.dns_class == DNSClass::IN
                && valid_names.contains(&record.name)
                && !valid_names.contains(&target.0)
            {
                valid_names.push(target.0.clone());
            }
        }
        if valid_names.len() == previous_len {
            break;
        }
    }
    if reply.answers.iter().any(|record| {
        matches!(record.data, RData::A(_)) && (record.dns_class != DNSClass::IN || !valid_names.contains(&record.name))
    }) {
        return Err(EncryptedDnsError::DnsParse("DNS answer owner does not match query".to_string()));
    }
    Ok(())
}

pub fn extract_ip_answers(packet: &[u8]) -> Result<Vec<String>, EncryptedDnsError> {
    Ok(extract_ip_answer_records(packet)?.into_iter().map(|record| record.ip.to_string()).collect())
}

pub fn extract_ip_answer_records(packet: &[u8]) -> Result<Vec<IpAnswerRecord>, EncryptedDnsError> {
    let message = Message::from_vec(packet).map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))?;
    let mut answers = BTreeSet::new();
    for record in &message.answers {
        match &record.data {
            RData::A(address) => {
                answers.insert(IpAnswerRecord { ip: IpAddr::V4(address.0), family: IpAnswerFamily::Ipv4 });
            }
            RData::AAAA(address) => {
                answers.insert(IpAnswerRecord { ip: IpAddr::V6(address.0), family: IpAnswerFamily::Ipv6 });
            }
            _ => {}
        }
    }
    Ok(answers.into_iter().collect())
}

fn random_dns_id() -> Result<u16, EncryptedDnsError> {
    let rng = ring::rand::SystemRandom::new();
    let mut buf = [0u8; 2];
    ring::rand::SecureRandom::fill(&rng, &mut buf)
        .map_err(|_| EncryptedDnsError::DnsParse("RNG failure".to_string()))?;
    Ok(u16::from_ne_bytes(buf))
}
