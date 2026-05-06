#[derive(Clone)]
pub struct Config<A: Authentication = DenyAuthentication> {
    /// Timeout of the command request
    request_timeout: Duration,
    /// Avoid useless roundtrips if we don't need the Authentication layer
    skip_auth: bool,
    /// Enable dns-resolving
    dns_resolve: bool,
    /// Enable command execution
    execute_command: bool,
    /// Enable UDP support
    allow_udp: bool,
    /// For some complex scenarios, we may want to either accept Username/Password configuration
    /// or IP Whitelisting, in case the client send only 1-2 auth methods (no auth) rather than 3 (with auth)
    allow_no_auth: bool,
    /// Contains the authentication trait to use the user against with
    auth: Option<Arc<A>>,
    /// Disables Nagle's algorithm for TCP
    nodelay: bool,
}

impl<A: Authentication> Default for Config<A> {
    fn default() -> Self {
        Config {
            request_timeout: Duration::from_secs(10),
            skip_auth: false,
            dns_resolve: true,
            execute_command: true,
            allow_udp: false,
            allow_no_auth: false,
            auth: None,
            nodelay: false,
        }
    }
}

impl<A: Authentication> Config<A> {
    /// How much time it should wait until the request timeout.
    pub fn set_request_timeout(&mut self, d: Duration) -> &mut Self {
        self.request_timeout = d;
        self
    }

    /// Skip the entire auth/handshake part, which means the server will directly wait for
    /// the command request.
    pub fn set_skip_auth(&mut self, value: bool) -> &mut Self {
        self.skip_auth = value;
        self.auth = None;
        self
    }

    /// Enable authentication
    /// 'static lifetime for Authentication avoid us to use `dyn Authentication`
    /// and set the Arc before calling the function.
    pub fn with_authentication<T: Authentication + 'static>(self, authentication: T) -> Config<T> {
        Config {
            request_timeout: self.request_timeout,
            skip_auth: self.skip_auth,
            dns_resolve: self.dns_resolve,
            execute_command: self.execute_command,
            allow_udp: self.allow_udp,
            allow_no_auth: self.allow_no_auth,
            auth: Some(Arc::new(authentication)),
            nodelay: self.nodelay,
        }
    }

    /// For some complex scenarios, we may want to either accept Username/Password configuration
    /// or IP Whitelisting, in case the client send only 2 auth methods rather than 3 (with auth)
    pub fn set_allow_no_auth(&mut self, value: bool) -> &mut Self {
        self.allow_no_auth = value;
        self
    }

    /// Set whether or not to execute commands
    pub fn set_execute_command(&mut self, value: bool) -> &mut Self {
        self.execute_command = value;
        self
    }

    /// Will the server perform dns resolve
    pub fn set_dns_resolve(&mut self, value: bool) -> &mut Self {
        self.dns_resolve = value;
        self
    }

    /// Set whether or not to allow udp traffic
    pub fn set_udp_support(&mut self, value: bool) -> &mut Self {
        self.allow_udp = value;
        self
    }
}
