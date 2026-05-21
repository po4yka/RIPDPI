mod client;
mod framing;
mod normalize;
mod util;
mod wire;

pub(crate) use client::{build_client_config, build_doh_client};
pub(crate) use framing::{read_length_prefixed_frame_async, write_length_prefixed_frame_async};
pub(crate) use normalize::normalize_endpoint;
pub(crate) use util::{format_error_chain, resolve_socket_addr, unix_time_secs};
pub(crate) use wire::build_dns_query;
pub use wire::{extract_ip_answer_records, extract_ip_answers, IpAnswerFamily, IpAnswerRecord};

use std::time::Duration;

pub(crate) const DNS_MESSAGE_MEDIA_TYPE: &str = "application/dns-message";
pub(crate) const DEFAULT_TIMEOUT: Duration = Duration::from_secs(4);
