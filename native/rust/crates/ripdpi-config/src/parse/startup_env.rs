use std::path::Path;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct StartupEnv {
    pub ss_local_port: Option<String>,
    pub ss_plugin_options: Option<String>,
    pub protect_path_present: bool,
}

impl StartupEnv {
    pub fn from_env_and_cwd(cwd: &Path) -> Self {
        Self {
            ss_local_port: std::env::var("SS_LOCAL_PORT").ok(),
            ss_plugin_options: std::env::var("SS_PLUGIN_OPTIONS").ok(),
            protect_path_present: cwd.join("protect_path").exists(),
        }
    }
}
