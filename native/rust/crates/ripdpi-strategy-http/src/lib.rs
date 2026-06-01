//! HTTP-layer desync strategy helpers.

use std::ops::Range;

use ripdpi_strategy_trait::{
    CapabilityTier, DesyncAction, DesyncPlan, DesyncStrategy, FlowId, L7Protocol, StrategyContext, StrategyDescriptor,
    StrategyError, StrategyStepDescriptor, StrategyStepFactory, StrategyStepRegistration, StrategyVerdict,
};

/// Alternates HTTP Host value casing using the zapret2 `domcase` pattern.
pub fn apply_domcase(input: &[u8]) -> Vec<u8> {
    let Some(host) = find_host_value(input) else {
        return input.to_vec();
    };
    let mut output = input.to_vec();
    let mut alpha_index = 0usize;
    for byte in &mut output[host] {
        if byte.is_ascii_alphabetic() {
            *byte = if alpha_index.is_multiple_of(2) { byte.to_ascii_lowercase() } else { byte.to_ascii_uppercase() };
            alpha_index += 1;
        }
    }
    output
}

/// Randomizes HTTP Host value casing deterministically for a flow.
pub fn apply_hostcase(input: &[u8], flow_id: FlowId) -> Vec<u8> {
    let Some(host) = find_host_value(input) else {
        return input.to_vec();
    };
    let mut output = input.to_vec();
    let mut state = flow_id.0 ^ 0x9e37_79b9_7f4a_7c15;
    let mut changed = false;
    for byte in &mut output[host] {
        if byte.is_ascii_alphabetic() {
            state = xorshift64(state);
            let next = if state & 1 == 0 { byte.to_ascii_lowercase() } else { byte.to_ascii_uppercase() };
            changed |= next != *byte;
            *byte = next;
        }
    }
    if changed {
        output
    } else {
        let Some(host) = find_host_value(input) else {
            return input.to_vec();
        };
        output[host.start] = output[host.start].to_ascii_uppercase();
        output
    }
}

/// Inserts an extra CR after the HTTP/1.x request line.
pub fn apply_methodeol(input: &[u8]) -> Vec<u8> {
    if find_host_value(input).is_none() {
        return input.to_vec();
    }
    let Some(line_end) = http1_request_line_end(input) else {
        return input.to_vec();
    };
    let mut output = Vec::with_capacity(input.len() + 1);
    output.extend_from_slice(&input[..line_end]);
    output.push(b'\r');
    output.extend_from_slice(&input[line_end..]);
    output
}

/// Replaces CRLF delimiters with LF delimiters in the HTTP header section only.
pub fn apply_unixeol(input: &[u8]) -> Vec<u8> {
    if find_host_value(input).is_none() {
        return input.to_vec();
    }
    let Some((header_end, separator_len)) = header_body_separator(input) else {
        return input.to_vec();
    };
    if separator_len != 4 {
        return input.to_vec();
    }
    let mut output = Vec::with_capacity(input.len());
    let mut cursor = 0usize;
    while cursor < header_end {
        if input.get(cursor..cursor + 2) == Some(b"\r\n") {
            output.push(b'\n');
            cursor += 2;
        } else {
            output.push(input[cursor]);
            cursor += 1;
        }
    }
    output.extend_from_slice(b"\n\n");
    output.extend_from_slice(&input[header_end + separator_len..]);
    output
}

/// HTTP `domcase` strategy.
#[derive(Clone, Copy, Debug, Default)]
pub struct DomcaseStrategy;

/// HTTP `hostcase` strategy.
#[derive(Clone, Copy, Debug, Default)]
pub struct HostcaseStrategy;

/// HTTP `methodeol` strategy.
#[derive(Clone, Copy, Debug, Default)]
pub struct MethodeolStrategy;

/// HTTP `unixeol` strategy.
#[derive(Clone, Copy, Debug, Default)]
pub struct UnixeolStrategy;

macro_rules! impl_http_strategy {
    ($type:ty, $id:literal, $label:literal, $apply:expr_2021) => {
        impl DesyncStrategy for $type {
            fn id(&self) -> &str {
                $id
            }

            fn matches(&self, ctx: &StrategyContext<'_>) -> bool {
                matches!(&ctx.dissect.proto, L7Protocol::Http(http) if http.is_request)
            }

            fn plan(&self, ctx: &StrategyContext<'_>, plan: &mut DesyncPlan) -> Result<(), StrategyError> {
                plan.actions.push(DesyncAction::Write($apply(ctx)));
                plan.verdict = StrategyVerdict::Apply;
                Ok(())
            }

            fn describe(&self) -> StrategyDescriptor {
                StrategyDescriptor {
                    id: $id.to_owned(),
                    label: $label.to_owned(),
                    supported_protocols: vec![L7Protocol::Http(Default::default())],
                    required_tier: CapabilityTier::Tier0,
                    required_capabilities: Vec::new(),
                }
            }
        }
    };
}

impl_http_strategy!(DomcaseStrategy, "http_domcase", "HTTP domain case", |ctx: &StrategyContext<'_>| {
    apply_domcase(ctx.payload)
});
impl_http_strategy!(HostcaseStrategy, "http_hostcase", "HTTP host random case", |ctx: &StrategyContext<'_>| {
    apply_hostcase(ctx.payload, ctx.flow_id)
});
impl_http_strategy!(MethodeolStrategy, "http_methodeol", "HTTP method EOL", |ctx: &StrategyContext<'_>| {
    apply_methodeol(ctx.payload)
});
impl_http_strategy!(UnixeolStrategy, "http_unixeol", "HTTP Unix EOL", |ctx: &StrategyContext<'_>| {
    apply_unixeol(ctx.payload)
});

/// Returns a boxed built-in HTTP strategy for a stable ID.
pub fn strategy_by_id(id: &str) -> Option<Box<dyn DesyncStrategy>> {
    match id {
        "http_domcase" => Some(Box::new(DomcaseStrategy)),
        "http_hostcase" => Some(Box::new(HostcaseStrategy)),
        "http_methodeol" => Some(Box::new(MethodeolStrategy)),
        "http_unixeol" => Some(Box::new(UnixeolStrategy)),
        _ => None,
    }
}

fn make_domcase_strategy() -> Box<dyn DesyncStrategy> {
    Box::new(DomcaseStrategy)
}

fn make_hostcase_strategy() -> Box<dyn DesyncStrategy> {
    Box::new(HostcaseStrategy)
}

fn make_methodeol_strategy() -> Box<dyn DesyncStrategy> {
    Box::new(MethodeolStrategy)
}

fn make_unixeol_strategy() -> Box<dyn DesyncStrategy> {
    Box::new(UnixeolStrategy)
}

/// Builds an HTTP strategy [`StrategyStepRegistration`] — all four are
/// stateless, `Tier0`, and need no runtime capability.
const fn http_registration(
    id: &'static str,
    label: &'static str,
    aliases: &'static [&'static str],
    make: fn() -> Box<dyn DesyncStrategy>,
) -> StrategyStepRegistration {
    StrategyStepRegistration {
        descriptor: StrategyStepDescriptor {
            id,
            label,
            aliases,
            required_tier: CapabilityTier::Tier0,
            required_capabilities: &[],
            needs_parameters: false,
            parameter_schema: "",
        },
        factory: StrategyStepFactory::Stateless(make),
    }
}

#[linkme::distributed_slice(ripdpi_strategy_trait::STRATEGY_STEP_REGISTRATIONS)]
static HTTP_DOMCASE_REGISTRATION: StrategyStepRegistration =
    http_registration("http_domcase", "HTTP domain case", &["httpDomcase"], make_domcase_strategy);

#[linkme::distributed_slice(ripdpi_strategy_trait::STRATEGY_STEP_REGISTRATIONS)]
static HTTP_HOSTCASE_REGISTRATION: StrategyStepRegistration =
    http_registration("http_hostcase", "HTTP host random case", &["httpHostcase"], make_hostcase_strategy);

#[linkme::distributed_slice(ripdpi_strategy_trait::STRATEGY_STEP_REGISTRATIONS)]
static HTTP_METHODEOL_REGISTRATION: StrategyStepRegistration =
    http_registration("http_methodeol", "HTTP method EOL", &["httpMethodeol"], make_methodeol_strategy);

#[linkme::distributed_slice(ripdpi_strategy_trait::STRATEGY_STEP_REGISTRATIONS)]
static HTTP_UNIXEOL_REGISTRATION: StrategyStepRegistration =
    http_registration("http_unixeol", "HTTP Unix EOL", &["httpUnixeol"], make_unixeol_strategy);

fn find_host_value(input: &[u8]) -> Option<Range<usize>> {
    let (header_end, _) = header_body_separator(input)?;
    let mut cursor = 0usize;
    while cursor < header_end {
        let newline = input[cursor..header_end].iter().position(|&byte| byte == b'\n');
        let line_end = newline.map_or(header_end, |offset| cursor + offset);
        let trimmed_end = line_end.checked_sub(1).filter(|&end| input[end] == b'\r').unwrap_or(line_end);
        if trimmed_end >= cursor + 5
            && input[cursor..cursor + 4].eq_ignore_ascii_case(b"host")
            && input[cursor + 4] == b':'
        {
            let mut value_start = cursor + 5;
            while value_start < trimmed_end && matches!(input[value_start], b' ' | b'\t') {
                value_start += 1;
            }
            let mut value_end = trimmed_end;
            while value_end > value_start && matches!(input[value_end - 1], b' ' | b'\t') {
                value_end -= 1;
            }
            return (value_start < value_end).then_some(value_start..value_end);
        }
        cursor = newline.map_or(header_end, |offset| cursor + offset + 1);
    }
    None
}

fn header_body_separator(input: &[u8]) -> Option<(usize, usize)> {
    if let Some(index) = input.windows(4).position(|window| window == b"\r\n\r\n") {
        Some((index, 4))
    } else {
        input.windows(2).position(|window| window == b"\n\n").map(|index| (index, 2))
    }
}

fn http1_request_line_end(input: &[u8]) -> Option<usize> {
    let newline = input.iter().position(|&byte| byte == b'\n')?;
    let line_end = newline.checked_sub(1).filter(|&end| input[end] == b'\r').unwrap_or(newline);
    let line = &input[..line_end];
    const METHODS: &[&[u8]] = &[b"GET", b"POST", b"PUT", b"DELETE", b"HEAD", b"OPTIONS", b"PATCH", b"CONNECT"];
    let method_matches = METHODS
        .iter()
        .any(|method| line.starts_with(method) && line.get(method.len()).is_some_and(|byte| *byte == b' '));
    let http1_matches = line.windows(b" HTTP/1.".len()).any(|window| window == b" HTTP/1.");
    (method_matches && http1_matches).then_some(line_end)
}

fn xorshift64(mut value: u64) -> u64 {
    if value == 0 {
        value = 0xa076_1d64_78bd_642f;
    }
    value ^= value << 13;
    value ^= value >> 7;
    value ^= value << 17;
    value
}
