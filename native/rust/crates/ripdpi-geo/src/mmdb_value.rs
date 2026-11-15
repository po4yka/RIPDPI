use std::fmt;

use serde::de::{self, Deserialize, DeserializeSeed, Deserializer, MapAccess, SeqAccess, Visitor};

pub(super) struct ValueWork(pub(super) usize);

impl<'de> Deserialize<'de> for ValueWork {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        ValueSeed { depth: 0 }.deserialize(deserializer)
    }
}

struct ValueSeed {
    depth: u16,
}

impl<'de> DeserializeSeed<'de> for ValueSeed {
    type Value = ValueWork;

    fn deserialize<D: Deserializer<'de>>(self, deserializer: D) -> Result<ValueWork, D::Error> {
        if self.depth == 64 {
            return Err(de::Error::custom("MMDB value nesting exceeds the validation limit"));
        }
        deserializer.deserialize_any(ValueVisitor { depth: self.depth })
    }
}

struct ValueVisitor {
    depth: u16,
}

impl<'de> Visitor<'de> for ValueVisitor {
    type Value = ValueWork;

    fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("a valid MMDB value")
    }

    fn visit_bool<E: de::Error>(self, _value: bool) -> Result<ValueWork, E> {
        Ok(ValueWork(1))
    }

    fn visit_i64<E: de::Error>(self, _value: i64) -> Result<ValueWork, E> {
        Ok(ValueWork(1))
    }

    fn visit_u64<E: de::Error>(self, _value: u64) -> Result<ValueWork, E> {
        Ok(ValueWork(1))
    }

    fn visit_u128<E: de::Error>(self, _value: u128) -> Result<ValueWork, E> {
        Ok(ValueWork(1))
    }

    fn visit_f64<E: de::Error>(self, _value: f64) -> Result<ValueWork, E> {
        Ok(ValueWork(1))
    }

    fn visit_str<E: de::Error>(self, value: &str) -> Result<ValueWork, E> {
        Ok(ValueWork(value.len().saturating_add(1)))
    }

    fn visit_bytes<E: de::Error>(self, value: &[u8]) -> Result<ValueWork, E> {
        Ok(ValueWork(value.len().saturating_add(1)))
    }

    fn visit_seq<A: SeqAccess<'de>>(self, mut sequence: A) -> Result<ValueWork, A::Error> {
        let mut work = 1usize;
        while let Some(value) = sequence.next_element_seed(ValueSeed { depth: self.depth + 1 })? {
            work = work.saturating_add(value.0);
        }
        Ok(ValueWork(work))
    }

    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<ValueWork, A::Error> {
        let mut work = 1usize;
        while let Some(key) = map.next_key::<&str>()? {
            let value = map.next_value_seed(ValueSeed { depth: self.depth + 1 })?;
            work = work.saturating_add(key.len().saturating_add(1)).saturating_add(value.0);
        }
        Ok(ValueWork(work))
    }
}
