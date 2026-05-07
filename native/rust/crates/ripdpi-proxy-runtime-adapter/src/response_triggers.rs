use ripdpi_config::{RuntimeConfig, DETECT_CONNECT, DETECT_HTTP_LOCAT, DETECT_TLS_HANDSHAKE_FAILURE, DETECT_TORST};
use ripdpi_session::TriggerEvent;

pub fn response_trigger_flag(trigger: TriggerEvent) -> u32 {
    match trigger {
        TriggerEvent::Redirect => DETECT_HTTP_LOCAT,
        TriggerEvent::SslErr => DETECT_TLS_HANDSHAKE_FAILURE,
        TriggerEvent::Connect => DETECT_CONNECT,
        TriggerEvent::Torst => DETECT_TORST,
    }
}

pub fn response_trigger_supported(config: &RuntimeConfig, trigger: TriggerEvent) -> bool {
    let flag = response_trigger_flag(trigger);
    config.groups.iter().any(|group| group.matches.detect & flag != 0)
}
