use crate::candidates::prelude::*;

pub fn build_parser_only_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = strategy_probe_base(base);
    config.parser_evasions.host_mixed_case = true;
    config.parser_evasions.domain_mixed_case = true;
    config.parser_evasions.host_remove_spaces = true;
    config
}

pub fn build_parser_unixeol_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_parser_only_candidate(base);
    config.parser_evasions.http_unix_eol = true;
    config
}

pub fn build_parser_methodeol_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_parser_only_candidate(base);
    config.parser_evasions.http_method_eol = true;
    config
}

pub fn build_parser_methodspace_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_parser_only_candidate(base);
    config.parser_evasions.http_method_space = true;
    config
}

pub fn build_parser_hostpad_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_parser_only_candidate(base);
    config.parser_evasions.http_host_pad = true;
    config
}

pub fn build_parser_host_extra_space_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_parser_only_candidate(base);
    config.parser_evasions.http_host_extra_space = true;
    config
}

pub fn build_parser_host_tab_candidate(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = build_parser_only_candidate(base);
    config.parser_evasions.http_host_tab = true;
    config
}
