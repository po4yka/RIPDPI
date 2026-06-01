use std::collections::BTreeMap;

use thiserror::Error;

pub const DEFAULT_PADDING_SCHEME: &str = "stop=8\n0=30-30\n1=100-400\n2=400-500,c,500-1000,c,500-1000,c,500-1000,c,500-1000\n3=9-9,500-1000\n4=500-1000\n5=500-1000\n6=500-1000\n7=500-1000";

const WASTE_FRAME_HEADER_LEN: usize = 7;

#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum PaddingError {
    #[error("padding scheme is not valid UTF-8")]
    InvalidUtf8,
    #[error("padding scheme is missing stop")]
    MissingStop,
    #[error("padding scheme stop must be a positive integer")]
    InvalidStop,
    #[error("padding scheme contains an invalid packet index: {0}")]
    InvalidPacketIndex(String),
    #[error("padding scheme contains an invalid range: {0}")]
    InvalidRange(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PaddingStep {
    PayloadSize(usize),
    CheckIfNoMoreData,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PaddingAction {
    Payload(Vec<u8>),
    Waste { len: usize },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PaddingPlan {
    steps: Vec<PaddingStep>,
}

impl PaddingPlan {
    pub fn direct() -> Self {
        Self { steps: Vec::new() }
    }

    pub fn steps(&self) -> &[PaddingStep] {
        &self.steps
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum SizeRule {
    Fixed(usize),
    Range { min: usize, max: usize },
    CheckIfNoMoreData,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PaddingScheme {
    raw_scheme: Vec<u8>,
    stop: u32,
    padding_md5: String,
    packet_rules: BTreeMap<u32, Vec<SizeRule>>,
}

impl PaddingScheme {
    pub fn parse(raw_scheme: &[u8]) -> Result<Self, PaddingError> {
        let text = std::str::from_utf8(raw_scheme).map_err(|_| PaddingError::InvalidUtf8)?;
        let mut stop = None;
        let mut packet_rules = BTreeMap::new();

        for line in text.lines().map(str::trim).filter(|line| !line.is_empty()) {
            let Some((key, value)) = line.split_once('=') else {
                continue;
            };
            let key = key.trim();
            let value = value.trim();
            if key == "stop" {
                stop = Some(value.parse::<u32>().map_err(|_| PaddingError::InvalidStop)?);
                continue;
            }

            let packet_index = key.parse::<u32>().map_err(|_| PaddingError::InvalidPacketIndex(key.to_owned()))?;
            packet_rules.insert(packet_index, parse_packet_rule(value)?);
        }

        let stop = stop.ok_or(PaddingError::MissingStop)?;
        if stop == 0 {
            return Err(PaddingError::InvalidStop);
        }

        Ok(Self {
            raw_scheme: raw_scheme.to_vec(),
            stop,
            padding_md5: format!("{:x}", md5::compute(raw_scheme)),
            packet_rules,
        })
    }

    pub fn stop(&self) -> u32 {
        self.stop
    }

    pub fn raw_scheme(&self) -> &[u8] {
        &self.raw_scheme
    }

    pub fn padding_md5(&self) -> &str {
        &self.padding_md5
    }

    pub fn plan_for_packet(&self, packet_index: u32, selector: u64) -> Result<PaddingPlan, PaddingError> {
        if packet_index >= self.stop {
            return Ok(PaddingPlan::direct());
        }
        let Some(rules) = self.packet_rules.get(&packet_index) else {
            return Ok(PaddingPlan::direct());
        };

        let steps = rules
            .iter()
            .map(|rule| match *rule {
                SizeRule::Fixed(size) => PaddingStep::PayloadSize(size),
                SizeRule::Range { min, max } => PaddingStep::PayloadSize(select_size(min, max, selector)),
                SizeRule::CheckIfNoMoreData => PaddingStep::CheckIfNoMoreData,
            })
            .collect();

        Ok(PaddingPlan { steps })
    }

    pub fn auth_padding0_len(&self, selector: u64) -> Result<usize, PaddingError> {
        let plan = self.plan_for_packet(0, selector)?;
        Ok(plan
            .steps()
            .iter()
            .find_map(|step| match *step {
                PaddingStep::PayloadSize(size) => Some(size),
                PaddingStep::CheckIfNoMoreData => None,
            })
            .unwrap_or(0))
    }

    pub fn write_plan_for_packet(
        &self,
        packet_index: u32,
        payload: &[u8],
        selector: u64,
    ) -> Result<Vec<PaddingAction>, PaddingError> {
        let plan = self.plan_for_packet(packet_index, selector)?;
        if plan.steps().is_empty() {
            return Ok(vec![PaddingAction::Payload(payload.to_vec())]);
        }

        let mut actions = Vec::new();
        let mut cursor = 0;

        for step in plan.steps() {
            match *step {
                PaddingStep::CheckIfNoMoreData => {
                    if cursor == payload.len() {
                        return Ok(actions);
                    }
                }
                PaddingStep::PayloadSize(target_size) => {
                    let remaining = &payload[cursor..];
                    if remaining.len() > target_size {
                        actions.push(PaddingAction::Payload(remaining[..target_size].to_vec()));
                        cursor += target_size;
                    } else if !remaining.is_empty() {
                        actions.push(PaddingAction::Payload(remaining.to_vec()));
                        cursor = payload.len();
                        if let Some(waste_len) = target_size.checked_sub(remaining.len() + WASTE_FRAME_HEADER_LEN)
                            && waste_len > 0
                        {
                            actions.push(PaddingAction::Waste { len: waste_len });
                        }
                    } else if target_size > 0 {
                        actions.push(PaddingAction::Waste { len: target_size });
                    }
                }
            }
        }

        if cursor < payload.len() {
            actions.push(PaddingAction::Payload(payload[cursor..].to_vec()));
        }

        Ok(actions)
    }
}

fn parse_packet_rule(value: &str) -> Result<Vec<SizeRule>, PaddingError> {
    let mut rules = Vec::new();
    for part in value.split(',').map(str::trim).filter(|part| !part.is_empty()) {
        if part == "c" {
            rules.push(SizeRule::CheckIfNoMoreData);
            continue;
        }

        let Some((min, max)) = part.split_once('-') else {
            return Err(PaddingError::InvalidRange(part.to_owned()));
        };
        let min = min.trim().parse::<usize>().map_err(|_| PaddingError::InvalidRange(part.to_owned()))?;
        let max = max.trim().parse::<usize>().map_err(|_| PaddingError::InvalidRange(part.to_owned()))?;
        if min == 0 || max == 0 {
            return Err(PaddingError::InvalidRange(part.to_owned()));
        }
        let (min, max) = if min <= max { (min, max) } else { (max, min) };
        if min == max {
            rules.push(SizeRule::Fixed(min));
        } else {
            rules.push(SizeRule::Range { min, max });
        }
    }
    Ok(rules)
}

fn select_size(min: usize, max: usize, selector: u64) -> usize {
    let span = max - min + 1;
    min + usize::try_from(selector % u64::try_from(span).expect("usize span fits u64"))
        .expect("selector modulo span fits usize")
}
