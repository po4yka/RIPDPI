use std::collections::BTreeSet;
use std::net::IpAddr;

use hickory_proto::op::{Message, MessageType, OpCode, Query};
use hickory_proto::rr::{Name, RData, RecordType};

use crate::types::EncryptedDnsError;

pub(crate) fn build_dns_query(name: &str, record_type: RecordType) -> Result<Vec<u8>, EncryptedDnsError> {
    let id = random_dns_id()?;
    let mut message = Message::new(id, MessageType::Query, OpCode::Query);
    message.metadata.recursion_desired = true;
    message.add_query(Query::query(
        Name::from_ascii(name).map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))?,
        record_type,
    ));
    message.to_vec().map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))
}

pub fn extract_ip_answers(packet: &[u8]) -> Result<Vec<String>, EncryptedDnsError> {
    let message = Message::from_vec(packet).map_err(|err| EncryptedDnsError::DnsParse(err.to_string()))?;
    let mut answers = BTreeSet::new();
    for record in &message.answers {
        match &record.data {
            RData::A(address) => {
                answers.insert(IpAddr::V4(address.0).to_string());
            }
            RData::AAAA(address) => {
                answers.insert(IpAddr::V6(address.0).to_string());
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
