use serde::{Deserialize, Deserializer, Serialize, Serializer};

pub(crate) const REDACTED_CREDENTIALS: &str = "<redacted>";

macro_rules! impl_redacted_debug {
    ($config:ident { $($field:ident),* $(,)? }) => {
        impl std::fmt::Debug for $config {
            fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                let mut debug = formatter.debug_struct(stringify!($config));
                $(debug.field(stringify!($field), &self.$field);)*
                debug.field("credentials", &crate::config::REDACTED_CREDENTIALS);
                debug.finish_non_exhaustive()
            }
        }
    };
}

mod backend;
mod conversions;
mod finalmask;
mod flat;
mod kind;
mod runtime;
mod shadowtls_inner;

pub use backend::*;
pub use finalmask::*;
pub(crate) use flat::*;
pub(crate) use kind::*;
pub use runtime::*;
pub use shadowtls_inner::*;
