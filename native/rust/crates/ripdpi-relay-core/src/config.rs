use serde::{Deserialize, Deserializer, Serialize, Serializer};

const REDACTED_CREDENTIALS: &str = "<redacted>";

macro_rules! impl_redacted_debug {
    ($config:ident { $($field:ident),* $(,)? }) => {
        impl std::fmt::Debug for $config {
            fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                let mut debug = formatter.debug_struct(stringify!($config));
                $(debug.field(stringify!($field), &self.$field);)*
                debug.field("credentials", &REDACTED_CREDENTIALS);
                debug.finish_non_exhaustive()
            }
        }
    };
}

include!("config/finalmask.rs");
include!("config/kind.rs");
include!("config/runtime.rs");
include!("config/backend.rs");
include!("config/flat.rs");
include!("config/conversions.rs");
include!("config/shadowtls_inner.rs");
