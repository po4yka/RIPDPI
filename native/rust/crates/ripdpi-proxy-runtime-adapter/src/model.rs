pub mod config {
    pub use ripdpi_config::*;
}

pub mod desync {
    pub use ripdpi_desync::{
        ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints, AdaptiveTlsRandRecProfile,
        AdaptiveUdpBurstProfile, TcpSegmentHint,
    };
}

pub mod proxy_config {
    pub use ripdpi_proxy_config::*;

    use std::sync::Mutex as StdMutex;

    pub struct NetworkReprobeTracker {
        last_identity: StdMutex<Option<String>>,
    }

    impl NetworkReprobeTracker {
        pub fn new() -> Self {
            Self { last_identity: StdMutex::new(None) }
        }

        pub fn check_snapshot(&self, snapshot: &NetworkSnapshot) -> bool {
            let identity = network_snapshot_identity(snapshot);
            let mut last = self.last_identity.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            if last.as_deref() == Some(&identity) {
                return false;
            }
            let is_initial = last.is_none();
            *last = Some(identity);
            !is_initial
        }
    }

    impl Default for NetworkReprobeTracker {
        fn default() -> Self {
            Self::new()
        }
    }

    pub fn network_snapshot_identity(snapshot: &NetworkSnapshot) -> String {
        let mut id = snapshot.transport.clone();
        if let Some(ref wifi) = snapshot.wifi {
            id.push(':');
            id.push_str(&wifi.ssid_hash);
        }
        if let Some(ref cellular) = snapshot.cellular {
            id.push(':');
            id.push_str(&cellular.operator_code);
            id.push(':');
            id.push_str(&cellular.generation);
        }
        for dns in &snapshot.dns_servers {
            id.push(',');
            id.push_str(dns);
        }
        id
    }
}

pub mod runtime_api {
    pub use ripdpi_runtime_api::*;
}

pub mod services {
    pub use ripdpi_runtime_services::{ServicesState, ServicesStateHandle};
}

pub mod session {
    pub use ripdpi_session::*;
}
