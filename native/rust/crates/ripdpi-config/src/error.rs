use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConfigError {
    pub option: String,
    pub value: Option<String>,
}

impl ConfigError {
    pub(crate) fn invalid(option: impl Into<String>, value: Option<impl Into<String>>) -> Self {
        Self { option: option.into(), value: value.map(Into::into) }
    }
}

impl fmt::Display for ConfigError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match &self.value {
            Some(value) => write!(f, "invalid value for {}: {}", self.option, value),
            None => write!(f, "invalid option: {}", self.option),
        }
    }
}

impl std::error::Error for ConfigError {}
