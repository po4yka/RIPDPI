use std::collections::BTreeMap;

use thiserror::Error;

pub const FRAME_HEADER_LEN: usize = 7;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Command {
    Waste = 0,
    Syn = 1,
    Psh = 2,
    Fin = 3,
    Settings = 4,
    Alert = 5,
    UpdatePaddingScheme = 6,
    SynAck = 7,
    HeartRequest = 8,
    HeartResponse = 9,
    ServerSettings = 10,
}

impl TryFrom<u8> for Command {
    type Error = FrameError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::Waste),
            1 => Ok(Self::Syn),
            2 => Ok(Self::Psh),
            3 => Ok(Self::Fin),
            4 => Ok(Self::Settings),
            5 => Ok(Self::Alert),
            6 => Ok(Self::UpdatePaddingScheme),
            7 => Ok(Self::SynAck),
            8 => Ok(Self::HeartRequest),
            9 => Ok(Self::HeartResponse),
            10 => Ok(Self::ServerSettings),
            other => Err(FrameError::UnknownCommand(other)),
        }
    }
}

impl From<Command> for u8 {
    fn from(command: Command) -> Self {
        command as Self
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum FrameError {
    #[error("unknown AnyTLS command byte {0}")]
    UnknownCommand(u8),
    #[error("AnyTLS frame payload is too long: {0}")]
    PayloadTooLong(usize),
    #[error("AnyTLS settings are not valid UTF-8")]
    InvalidSettingsUtf8,
    #[error("AnyTLS settings are missing {0}")]
    MissingSetting(&'static str),
    #[error("unsupported AnyTLS settings version {0}")]
    UnsupportedSettingsVersion(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    command: Command,
    stream_id: u32,
    data: Vec<u8>,
}

impl Frame {
    pub fn control(command: Command, stream_id: u32) -> Self {
        Self { command, stream_id, data: Vec::new() }
    }

    pub fn with_data(command: Command, stream_id: u32, data: Vec<u8>) -> Self {
        Self { command, stream_id, data }
    }

    pub fn command(&self) -> Command {
        self.command
    }

    pub fn stream_id(&self) -> u32 {
        self.stream_id
    }

    pub fn data(&self) -> &[u8] {
        &self.data
    }

    pub fn encode(&self) -> Result<Vec<u8>, FrameError> {
        let data_len = u16::try_from(self.data.len()).map_err(|_| FrameError::PayloadTooLong(self.data.len()))?;
        let mut encoded = Vec::with_capacity(FRAME_HEADER_LEN + self.data.len());
        encoded.push(self.command.into());
        encoded.extend_from_slice(&self.stream_id.to_be_bytes());
        encoded.extend_from_slice(&data_len.to_be_bytes());
        encoded.extend_from_slice(&self.data);
        Ok(encoded)
    }

    pub fn decode(input: &[u8]) -> Result<Option<(Self, usize)>, FrameError> {
        if input.len() < FRAME_HEADER_LEN {
            return Ok(None);
        }

        let command = Command::try_from(input[0])?;
        let stream_id = u32::from_be_bytes([input[1], input[2], input[3], input[4]]);
        let data_len = usize::from(u16::from_be_bytes([input[5], input[6]]));
        let frame_len = FRAME_HEADER_LEN + data_len;
        if input.len() < frame_len {
            return Ok(None);
        }

        Ok(Some((Self { command, stream_id, data: input[FRAME_HEADER_LEN..frame_len].to_vec() }, frame_len)))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClientSettings {
    client: String,
    padding_md5: String,
}

impl ClientSettings {
    pub fn new(client: impl Into<String>, padding_md5: impl Into<String>) -> Self {
        Self { client: client.into(), padding_md5: padding_md5.into() }
    }

    pub fn client(&self) -> &str {
        &self.client
    }

    pub fn padding_md5(&self) -> &str {
        &self.padding_md5
    }

    pub fn encode(&self) -> Vec<u8> {
        format!("v=2\nclient={}\npadding-md5={}", self.client, self.padding_md5).into_bytes()
    }

    pub fn decode(data: &[u8]) -> Result<Self, FrameError> {
        let settings = parse_settings(data)?;
        let version = required_setting(&settings, "v")?;
        if version != "2" {
            return Err(FrameError::UnsupportedSettingsVersion(version.to_owned()));
        }
        let client = required_setting(&settings, "client")?.to_owned();
        let padding_md5 = required_setting(&settings, "padding-md5")?.to_owned();
        Ok(Self { client, padding_md5 })
    }
}

fn parse_settings(data: &[u8]) -> Result<BTreeMap<&str, &str>, FrameError> {
    let text = std::str::from_utf8(data).map_err(|_| FrameError::InvalidSettingsUtf8)?;
    let mut settings = BTreeMap::new();
    for line in text.lines() {
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        settings.insert(key, value);
    }
    Ok(settings)
}

fn required_setting<'a>(settings: &'a BTreeMap<&str, &str>, key: &'static str) -> Result<&'a str, FrameError> {
    settings.get(key).copied().ok_or(FrameError::MissingSetting(key))
}
