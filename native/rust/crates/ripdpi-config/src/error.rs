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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn config_error_display_with_value() {
        let err = ConfigError::invalid("--ttl", Some("abc"));
        assert_eq!(err.to_string(), "invalid value for --ttl: abc");
    }

    #[test]
    fn config_error_display_without_value() {
        let err = ConfigError::invalid("--unknown", None::<String>);
        assert_eq!(err.to_string(), "invalid option: --unknown");
    }

    #[test]
    fn config_error_implements_std_error() {
        let err = ConfigError::invalid("test", Some("val"));
        let _: &dyn std::error::Error = &err;
    }
}
