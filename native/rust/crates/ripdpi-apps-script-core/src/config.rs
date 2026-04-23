use std::collections::HashMap;
use std::env;
use std::io;
use std::path::PathBuf;

use serde::Deserialize;

const DEFAULT_GOOGLE_IP: &str = "216.239.38.120";
const DEFAULT_FRONT_DOMAIN: &str = "www.google.com";
const DEFAULT_LISTEN_HOST: &str = "127.0.0.1";
const DEFAULT_LISTEN_PORT: u16 = 11_980;

#[derive(Debug, Clone)]
pub struct AppsScriptRuntimeConfig {
    pub kind: String,
    pub profile_id: String,
    pub listen_host: String,
    pub listen_port: u16,
    pub google_ip: String,
    pub front_domain: String,
    pub script_ids: Vec<String>,
    pub sni_hosts: Vec<String>,
    pub auth_key: String,
    pub verify_ssl: bool,
    pub parallel_relay: bool,
    pub direct_hosts: Vec<String>,
    pub data_dir: PathBuf,
    pub hosts: HashMap<String, String>,
}

impl AppsScriptRuntimeConfig {
    pub fn from_json(json: &str) -> io::Result<Self> {
        let raw: RawAppsScriptRuntimeConfig =
            serde_json::from_str(json).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;

        let listen_host = raw
            .local_socks_host
            .clone()
            .or(raw.listen_host.clone())
            .filter(|value| !value.trim().is_empty())
            .unwrap_or_else(|| DEFAULT_LISTEN_HOST.to_string());
        let listen_port =
            normalize_port(raw.local_socks_port.or(raw.listen_port).unwrap_or(i32::from(DEFAULT_LISTEN_PORT)))?;
        let google_ip = raw
            .google_ip
            .clone()
            .or(raw.server.clone())
            .filter(|value| !value.trim().is_empty())
            .unwrap_or_else(|| DEFAULT_GOOGLE_IP.to_string());
        let front_domain = raw
            .front_domain
            .clone()
            .or(raw.server_name.clone())
            .filter(|value| !value.trim().is_empty())
            .unwrap_or_else(|| DEFAULT_FRONT_DOMAIN.to_string());
        let script_ids = resolve_script_ids(&raw)?;
        let sni_hosts = normalize_string_list(raw.sni_hosts.unwrap_or_default());
        let auth_key = raw
            .auth_key
            .filter(|value| !value.trim().is_empty())
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "Apps Script authKey is required"))?;
        let direct_hosts = normalize_string_list(raw.direct_hosts.unwrap_or_default());
        let data_dir =
            raw.data_dir.filter(|value| !value.trim().is_empty()).map(PathBuf::from).unwrap_or_else(default_data_dir);
        let mut hosts = raw.hosts.unwrap_or_default();
        if hosts.is_empty() && !direct_hosts.is_empty() {
            for host in &direct_hosts {
                hosts.insert(host.to_ascii_lowercase(), google_ip.clone());
            }
        }

        Ok(Self {
            kind: raw.kind.unwrap_or_else(|| "google_apps_script".to_string()),
            profile_id: raw.profile_id.unwrap_or_default(),
            listen_host,
            listen_port,
            google_ip,
            front_domain,
            script_ids,
            sni_hosts,
            auth_key,
            verify_ssl: raw.verify_ssl.unwrap_or(true),
            parallel_relay: raw.parallel_relay.unwrap_or(false),
            direct_hosts,
            data_dir,
            hosts,
        })
    }

    pub fn listener_address(&self) -> String {
        format!("{}:{}", self.listen_host, self.listen_port)
    }

    pub fn upstream_address(&self) -> String {
        format!("{} via {}", self.google_ip, self.front_domain)
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
enum ScriptIdsField {
    One(String),
    Many(Vec<String>),
}

impl ScriptIdsField {
    fn into_vec(self) -> Vec<String> {
        match self {
            Self::One(value) => vec![value],
            Self::Many(values) => values,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawAppsScriptRuntimeConfig {
    #[serde(default, alias = "kind")]
    kind: Option<String>,
    #[serde(default, alias = "profile_id")]
    profile_id: Option<String>,
    #[serde(default, alias = "listen_host")]
    listen_host: Option<String>,
    #[serde(default, alias = "listen_port")]
    listen_port: Option<i32>,
    #[serde(default, alias = "local_socks_host")]
    local_socks_host: Option<String>,
    #[serde(default, alias = "local_socks_port")]
    local_socks_port: Option<i32>,
    #[serde(default, alias = "google_ip", alias = "appsScriptGoogleIp")]
    google_ip: Option<String>,
    #[serde(default, alias = "front_domain", alias = "appsScriptFrontDomain")]
    front_domain: Option<String>,
    #[serde(default, alias = "auth_key", alias = "appsScriptAuthKey")]
    auth_key: Option<String>,
    #[serde(default, alias = "script_id")]
    script_id: Option<ScriptIdsField>,
    #[serde(default, alias = "script_ids", alias = "appsScriptScriptIds")]
    script_ids: Option<ScriptIdsField>,
    #[serde(default, alias = "sni_hosts", alias = "appsScriptSniHosts")]
    sni_hosts: Option<Vec<String>>,
    #[serde(default, alias = "verify_ssl", alias = "appsScriptVerifySsl")]
    verify_ssl: Option<bool>,
    #[serde(default, alias = "parallel_relay", alias = "appsScriptParallelRelay")]
    parallel_relay: Option<bool>,
    #[serde(default, alias = "direct_hosts", alias = "appsScriptDirectHosts")]
    direct_hosts: Option<Vec<String>>,
    #[serde(default, alias = "data_dir")]
    data_dir: Option<String>,
    #[serde(default)]
    hosts: Option<HashMap<String, String>>,
    #[serde(default)]
    server: Option<String>,
    #[serde(default, alias = "server_name")]
    server_name: Option<String>,
    #[serde(default, alias = "xhttp_path")]
    xhttp_path: Option<String>,
}

fn resolve_script_ids(raw: &RawAppsScriptRuntimeConfig) -> io::Result<Vec<String>> {
    let mut values = Vec::new();
    if let Some(script_ids) = raw.script_ids.clone() {
        values.extend(script_ids.into_vec());
    } else if let Some(script_id) = raw.script_id.clone() {
        values.extend(script_id.into_vec());
    } else if let Some(path) = raw.xhttp_path.as_deref() {
        if let Some(script_id) = parse_script_id_from_path(path) {
            values.push(script_id);
        }
    }

    values.retain(|value| !value.trim().is_empty());
    if values.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "Apps Script scriptId/scriptIds is required"));
    }
    Ok(values)
}

fn parse_script_id_from_path(value: &str) -> Option<String> {
    let trimmed = value.trim().trim_matches('/');
    if trimmed.is_empty() {
        return None;
    }
    let parts: Vec<&str> = trimmed.split('/').collect();
    let macros_index = parts.iter().position(|part| *part == "macros")?;
    let script_marker = parts.get(macros_index + 1)?;
    let script_id = parts.get(macros_index + 2)?;
    if *script_marker != "s" || script_id.is_empty() {
        return None;
    }
    Some((*script_id).to_string())
}

fn normalize_port(value: i32) -> io::Result<u16> {
    u16::try_from(value)
        .ok()
        .filter(|port| *port != 0)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid listener port {value}")))
}

fn default_data_dir() -> PathBuf {
    env::temp_dir().join("ripdpi-apps-script-relay")
}

fn normalize_string_list(values: Vec<String>) -> Vec<String> {
    values.into_iter().map(|value| value.trim().to_string()).filter(|value| !value.is_empty()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_explicit_apps_script_fields() {
        let config = AppsScriptRuntimeConfig::from_json(
            r#"{
                "kind":"apps_script",
                "profileId":"relay-profile",
                "localSocksHost":"127.0.0.2",
                "localSocksPort":14000,
                "googleIp":"216.239.38.120",
                "frontDomain":"www.google.com",
                "scriptIds":["script-a","script-b"],
                "authKey":"secret"
            }"#,
        )
        .expect("config");

        assert_eq!(config.listen_host, "127.0.0.2");
        assert_eq!(config.listen_port, 14_000);
        assert_eq!(config.script_ids.len(), 2);
    }

    #[test]
    fn extracts_script_id_from_xhttp_path() {
        let config = AppsScriptRuntimeConfig::from_json(
            r#"{
                "server":"216.239.38.120",
                "serverName":"www.google.com",
                "xhttpPath":"/macros/s/deployment-id/exec",
                "authKey":"secret"
            }"#,
        )
        .expect("config");

        assert_eq!(config.script_ids, vec!["deployment-id".to_string()]);
    }
}
