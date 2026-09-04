/// Wrap TcpStream and contains Socks5 protocol implementation.
pub struct Socks5Socket<T: AsyncRead + AsyncWrite + Unpin, A: Authentication> {
    inner: T,
    config: Arc<Config<A>>,
    auth: AuthenticationMethod,
    target_addr: Option<TargetAddr>,
    cmd: Option<Socks5Command>,
    /// Socket address which will be used in the reply message.
    reply_ip: Option<IpAddr>,
    /// If the client has been authenticated, that's where we store his credentials
    /// to be accessed from the socket
    credentials: Option<A::Item>,
}

pub mod states {
    pub struct Opened;
    pub struct Authenticated;
    pub struct CommandRead;
}

pub struct Socks5ServerProtocol<T, S> {
    inner: T,
    // Variance: invariant over `S` (typestate marker). The state types
    // (`Opened`/`Authenticated`/`CommandRead`) are zero-sized phantoms
    // with no instances; `PhantomData<S>` carries the typestate at the
    // type level without owning anything. No Send/Sync impact -- those
    // come from `inner: T`.
    _state: PhantomData<S>,
}

impl<T, S> Socks5ServerProtocol<T, S> {
    fn new(inner: T) -> Self {
        Socks5ServerProtocol { inner, _state: PhantomData }
    }
}

impl<T> Socks5ServerProtocol<T, states::Opened> {
    /// Start handling the SOCKS5 protocol flow, wrapping a client socket.
    pub fn start(inner: T) -> Self {
        Self::new(inner)
    }
}

pub trait CheckResult {
    fn is_good(&self) -> bool;
}

impl CheckResult for bool {
    fn is_good(&self) -> bool {
        *self
    }
}

impl<T> CheckResult for Option<T> {
    fn is_good(&self) -> bool {
        self.is_some()
    }
}

impl<T, E> CheckResult for Result<T, E> {
    fn is_good(&self) -> bool {
        self.is_ok()
    }
}

impl<T> Socks5ServerProtocol<T, states::Authenticated> {
    /// Finish handling the authentication method-specific part of the protocol,
    /// returning back to the overall SOCKS5 flow.
    pub fn finish_auth<A: AuthMethodSuccessState<T>>(auth: A) -> Self {
        Self::new(auth.into_inner())
    }

    /// Wrap a socket in a SOCKS5 flow handler that's already marked as authenticated.
    ///
    /// This is not actually part of the official SOCKS5 protocol, but allows you to
    /// only use the post-authentication subset of it.
    pub fn skip_auth_this_is_not_rfc_compliant(inner: T) -> Self {
        Self::new(inner)
    }

    /// Handle the SOCKS5 auth negotiation supporting only the `NoAuthentication` method.
    pub async fn accept_no_auth(inner: T) -> Result<Self, SocksServerError>
    where
        T: AsyncWrite + AsyncRead + Unpin,
    {
        Ok(Socks5ServerProtocol::start(inner).negotiate_auth(&[NoAuthentication]).await?.finish_auth())
    }

    /// Handle the SOCKS5 auth negotiation supporting only the `PasswordAuthentication` method,
    /// and verify the provided username and password using the provided closure.
    ///
    /// The closure can mutate state variables and/or return a result as `Option`/`Result`.
    pub async fn accept_password_auth<F, R>(inner: T, mut check: F) -> Result<(Self, R), SocksServerError>
    where
        T: AsyncWrite + AsyncRead + Unpin,
        F: FnMut(String, String) -> R,
        R: CheckResult,
    {
        let (user, pass, auth) = Socks5ServerProtocol::start(inner)
            .negotiate_auth(&[PasswordAuthentication])
            .await?
            .read_username_password()
            .await?;
        let check_result = check(user, pass);
        if check_result.is_good() {
            Ok((auth.accept().await?.finish_auth(), check_result))
        } else {
            auth.reject().await?;
            Err(SocksServerError::AuthenticationRejected)
        }
    }
}

/// A trait for the final successful state of an authentication method's implementation.
///
/// This allows `Socks5ServerProtocol<T, states::Authenticated>::finish_authentication` to
/// let the user continue with the protocol after the socket has been handed off to the
/// authentication method.
pub trait AuthMethodSuccessState<T> {
    fn into_inner(self) -> T;

    fn finish_auth(self) -> Socks5ServerProtocol<T, states::Authenticated>
    where
        Self: Sized,
    {
        Socks5ServerProtocol::finish_auth(self)
    }
}

/// A metadata trait for authentication methods, essentially binding an ID value
/// (as used in the method negotiation) to an actual implementation of the method.
///
/// Use blank structs for individual protocol implementations and
/// enums for sets of supported protocols (you'll need a matching enum for the `Impl`).
pub trait AuthMethod<T>: Copy {
    type StartingState;
    fn method_id(self) -> u8;
    fn new(self, inner: T) -> Self::StartingState;
}

pub struct NoAuthenticationImpl<T>(T);

impl<T> AuthMethodSuccessState<T> for NoAuthenticationImpl<T> {
    fn into_inner(self) -> T {
        self.0
    }
}

/// The "NO AUTHENTICATION REQUIRED" auth method, ID 00h as specifed by RFC 1928.
///
/// As the dummy no-auth method, it only has one state. Once it's been negotiated,
/// you can immediately continue with `finish_authentication`.
///
/// Or not so immediately: if you want to use no-authentication with e.g. IP address
/// allowlisting or TLS client certificate auth for TLS-wrapped SOCKS5, this is your
/// opportunity to reject the no-authentication by dropping the connection!
#[derive(Debug, Clone, Copy)]
pub struct NoAuthentication;

impl<T> AuthMethod<T> for NoAuthentication {
    type StartingState = NoAuthenticationImpl<T>;

    fn method_id(self) -> u8 {
        0x00
    }

    fn new(self, inner: T) -> Self::StartingState {
        NoAuthenticationImpl(inner)
    }
}

mod password_states {
    pub struct Started;
    pub struct Received;
    pub struct Finished;
}

pub struct PasswordAuthenticationImpl<T, S> {
    inner: T,
    // Variance: invariant over `S` (typestate marker). The state types
    // (`password_states::Started`/`Received`/`Finished`) are zero-sized
    // phantoms; `PhantomData<S>` carries the typestate at the type
    // level without owning anything. Send/Sync come from `inner: T`.
    _state: PhantomData<S>,
}

pub type PasswordAuthenticationStarted<T> = PasswordAuthenticationImpl<T, password_states::Started>;

impl<T, S> PasswordAuthenticationImpl<T, S> {
    fn new(inner: T) -> Self {
        PasswordAuthenticationImpl { inner, _state: PhantomData }
    }
}

impl<T: AsyncRead + Unpin> PasswordAuthenticationImpl<T, password_states::Started> {
    /// Handle the username and password sent by the client.
    pub async fn read_username_password(
        self,
    ) -> Result<(String, String, PasswordAuthenticationImpl<T, password_states::Received>), SocksServerError> {
        let mut socket = self.inner;
        trace!("PasswordAuthenticationStarted: read_username_password()");
        let [version, user_len] = read_exact!(socket, [0u8; 2]).err_when("reading user len")?;
        debug!("Auth: [version: {version}, user len: {len}]", version = version, len = user_len,);

        if user_len < 1 {
            return Err(SocksServerError::EmptyUsername);
        }

        let username = read_exact!(socket, vec![0u8; user_len as usize]).err_when("reading username")?;

        let [pass_len] = read_exact!(socket, [0u8; 1]).err_when("reading password len")?;
        debug!("Auth: [pass len: {len}]", len = pass_len,);

        if pass_len < 1 {
            return Err(SocksServerError::EmptyPassword);
        }

        let password = read_exact!(socket, vec![0u8; pass_len as usize]).err_when("reading password")?;

        let username = String::from_utf8(username).err_when("converting username")?;
        let password = String::from_utf8(password).err_when("converting password")?;

        Ok((username, password, PasswordAuthenticationImpl::new(socket)))
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn password_auth_logs_do_not_include_credential_bytes() {
        let source = include_str!("protocol.rs");
        let forbidden = ["username ".to_owned() + "bytes", "password ".to_owned() + "bytes"];

        for phrase in forbidden {
            assert!(!source.contains(&phrase), "secret-bearing log phrase must not appear: {phrase}");
        }
    }
}

impl<T: AsyncWrite + Unpin> PasswordAuthenticationImpl<T, password_states::Received> {
    /// Notify the client with a "SUCCEEDED" reply and proceed to finish the authentication.
    pub async fn accept(
        mut self,
    ) -> Result<PasswordAuthenticationImpl<T, password_states::Finished>, SocksServerError> {
        self.inner.write_all(&[1, consts::SOCKS5_REPLY_SUCCEEDED]).await.err_when("replying auth success")?;

        debug!("Password authentication accepted.");
        Ok(PasswordAuthenticationImpl::new(self.inner))
    }

    /// Notify the client with a "NOT_ACCEPTABLE" reply and drop the socket.
    pub async fn reject(mut self) -> Result<(), SocksServerError> {
        self.inner
            .write_all(&[1, consts::SOCKS5_AUTH_METHOD_NOT_ACCEPTABLE])
            .await
            .err_when("replying with auth method not acceptable")?;

        debug!("Password authentication rejected.");
        Ok(())
    }
}

impl<T> AuthMethodSuccessState<T> for PasswordAuthenticationImpl<T, password_states::Finished> {
    fn into_inner(self) -> T {
        self.inner
    }
}

/// The "USERNAME/PASSWORD" auth method, ID 02h as specified by RFC 1928.
#[derive(Debug, Clone, Copy)]
pub struct PasswordAuthentication;

impl<T> AuthMethod<T> for PasswordAuthentication {
    type StartingState = PasswordAuthenticationImpl<T, password_states::Started>;

    fn method_id(self) -> u8 {
        0x02
    }

    fn new(self, inner: T) -> Self::StartingState {
        PasswordAuthenticationImpl::new(inner)
    }
}

#[macro_export]
macro_rules! auth_method_enums {
    (
        $(#[$enum_meta:meta])*
        $vis:vis enum $enum:ident / $(#[$state_enum_meta:meta])* $state_enum:ident<$state_enum_par:ident> {
            $($method:ident($state:ty)),+ $(,)?
        }
    ) => {
        $(#[$state_enum_meta])*
        $vis enum $state_enum<$state_enum_par> {
            $($method($state)),+
        }

        #[derive(Clone, Copy)]
        $(#[$enum_meta])*
        $vis enum $enum {
            $($method($method)),+
        }

        impl<T> AuthMethod<T> for $enum {
            type StartingState = $state_enum<T>;

            fn method_id(self) -> u8 {
                match self {
                    $($enum::$method(auth) => AuthMethod::<T>::method_id(auth)),+
                }
            }

            fn new(self, inner: T) -> Self::StartingState {
                match self {
                    $($enum::$method(auth) => $state_enum::$method(auth.new(inner))),+
                }
            }
        }
    };
}

auth_method_enums! {
    /// The combination of all authentication methods supported by this crate out of the box,
    /// as an enum appropriate for static dispatch.
    ///
    /// If you want to add your own custom methods, you can generate a similar enum using the `auth_method_enums` macro.
    pub enum StandardAuthentication / StandardAuthenticationStarted<T> {
        NoAuthentication(NoAuthenticationImpl<T>),
        PasswordAuthentication(PasswordAuthenticationImpl<T, password_states::Started>),
    }
}

impl StandardAuthentication {
    /// Return a slice containing either both supported methods or only `PasswordAuthentication`.
    pub fn allow_no_auth(allow: bool) -> &'static [StandardAuthentication] {
        if allow {
            &[
                // The order of authentication methods can be tested by clients in sequence,
                // so list more secure or preferred methods first
                StandardAuthentication::PasswordAuthentication(PasswordAuthentication),
                StandardAuthentication::NoAuthentication(NoAuthentication),
            ]
        } else {
            &[StandardAuthentication::PasswordAuthentication(PasswordAuthentication)]
        }
    }
}

#[allow(deprecated)]
impl<T: AsyncRead + AsyncWrite + Unpin, A: Authentication> Socks5Socket<T, A> {
    pub fn new(socket: T, config: Arc<Config<A>>) -> Self {
        Socks5Socket {
            inner: socket,
            config,
            auth: AuthenticationMethod::None,
            target_addr: None,
            cmd: None,
            reply_ip: None,
            credentials: None,
        }
    }

    /// Set the bind IP address in Socks5Reply.
    ///
    /// Only the inner socket owner knows the correct reply bind addr, so leave this field to be
    /// populated. For those strict clients, users can use this function to set the correct IP
    /// address.
    ///
    /// Most popular SOCKS5 clients [1] [2] ignore BND.ADDR and BND.PORT the reply of command
    /// CONNECT, but this field could be useful in some other command, such as UDP ASSOCIATE.
    ///
    /// [1]: https://github.com/chromium/chromium/blob/bd2c7a8b65ec42d806277dd30f138a673dec233a/net/socket/socks5_client_socket.cc#L481
    /// [2]: https://github.com/curl/curl/blob/d15692ebbad5e9cfb871b0f7f51a73e43762cee2/lib/socks.c#L978
    pub fn set_reply_ip(&mut self, addr: IpAddr) {
        self.reply_ip = Some(addr);
    }

    /// Process clients SOCKS requests
    /// This is the entry point where a whole request is processed.
    pub async fn upgrade_to_socks5(mut self) -> Result<Socks5Socket<T, A>, SocksError> {
        trace!("upgrading to socks5...");

        // NOTE: this cannot be split in two without making self.inner an Option

        // Handshake
        let proto = match self.config.auth.as_ref() {
            _ if self.config.skip_auth => {
                debug!("skipping auth");
                Socks5ServerProtocol::skip_auth_this_is_not_rfc_compliant(self.inner)
            }
            None => Socks5ServerProtocol::start(self.inner).negotiate_auth(&[NoAuthentication]).await?.finish_auth(),
            Some(auth_callback) => {
                let methods = StandardAuthentication::allow_no_auth(self.config.allow_no_auth);
                let auth = Socks5ServerProtocol::start(self.inner).negotiate_auth(methods).await?;
                let (proto, creds) = authenticate_callback(auth_callback.as_ref(), auth).await?;
                self.credentials = Some(creds);
                proto
            }
        };

        let (proto, cmd, target_addr) = {
            let triple = proto.read_command().await?;

            if self.config.dns_resolve {
                triple.resolve_dns().await?
            } else {
                debug!("Domain won't be resolved because `dns_resolve`'s config has been turned off.");
                triple
            }
        };

        match cmd {
            cmd if !self.config.execute_command => {
                self.cmd = Some(cmd);
                self.inner = proto.inner;
            }
            Socks5Command::TCPConnect => {
                self.inner =
                    run_tcp_proxy(proto, &target_addr, self.config.request_timeout, self.config.nodelay).await?;
            }
            Socks5Command::UDPAssociate if self.config.allow_udp => {
                self.inner =
                    run_udp_proxy(proto, &target_addr, None, self.reply_ip.context("invalid reply ip")?, None).await?;
            }
            _ => {
                proto.reply_error(&ReplyError::CommandNotSupported).await?;
                return Err(ReplyError::CommandNotSupported.into());
            }
        };

        self.target_addr = Some(target_addr); /* legacy API leaves it exported */
        Ok(self)
    }

    /// Consumes the `Socks5Socket`, returning the wrapped stream.
    pub fn into_inner(self) -> T {
        self.inner
    }

    /// This function is public, it can be call manually on your own-willing
    /// if config flag has been turned off: `Config::dns_resolve == false`.
    pub async fn resolve_dns(&mut self) -> Result<(), SocksError> {
        trace!("resolving dns");
        if let Some(target_addr) = self.target_addr.take() {
            // decide whether we have to resolve DNS or not
            self.target_addr = match target_addr {
                TargetAddr::Domain(_, _) => Some(target_addr.resolve_dns().await?),
                TargetAddr::Ip(_) => Some(target_addr),
            };
        }

        Ok(())
    }

    pub fn target_addr(&self) -> Option<&TargetAddr> {
        self.target_addr.as_ref()
    }

    pub fn auth(&self) -> &AuthenticationMethod {
        &self.auth
    }

    pub fn cmd(&self) -> &Option<Socks5Command> {
        &self.cmd
    }

    /// Borrow the credentials of the user has authenticated with
    pub fn get_credentials(&self) -> Option<&<<A as Authentication>::Item as Deref>::Target>
    where
        <A as Authentication>::Item: Deref,
    {
        self.credentials.as_deref()
    }

    /// Get the credentials of the user has authenticated with
    pub fn take_credentials(&mut self) -> Option<A::Item> {
        self.credentials.take()
    }
}

impl<T: AsyncRead + AsyncWrite + Unpin> Socks5ServerProtocol<T, states::Opened> {
    /// Negotiate an authentication method from a list of supported ones and initialize it.
    ///
    /// Internally, this reads the list of authentication methods provided by the client, and
    /// picks the first one for which there exists an implementation in `server_methods`.
    ///
    /// If none of the auth methods requested by the client are in `server_methods`,
    /// returns a `SocksServerError::AuthMethodUnacceptable`.
    pub async fn negotiate_auth<M: AuthMethod<T>>(
        mut self,
        server_methods: &[M],
    ) -> Result<M::StartingState, SocksServerError> {
        trace!("Socks5ServerProtocol: negotiate_auth()");
        let [version, methods_len] = read_exact!(self.inner, [0u8; 2]).err_when("reading methods")?;
        debug!("Handshake headers: [version: {version}, methods len: {len}]", version = version, len = methods_len,);

        if version != consts::SOCKS5_VERSION {
            return Err(SocksServerError::UnsupportedSocksVersion(version));
        }

        // {METHODS available from the client}
        // eg. (non-auth) {0, 1}
        // eg. (auth)     {0, 1, 2}
        let methods = read_exact!(self.inner, vec![0u8; methods_len as usize]).err_when("reading methods")?;
        debug!("methods supported sent by the client: {:?}", methods);

        // server_methods order matter!
        // the server could choose to prioritize methods
        for server_method in server_methods {
            for client_method_id in methods.iter() {
                if server_method.method_id() == *client_method_id {
                    debug!("Reply with method {}", *client_method_id);
                    self.inner
                        .write_all(&[consts::SOCKS5_VERSION, *client_method_id])
                        .await
                        .err_when("replying with auth method")?;
                    return Ok(server_method.new(self.inner));
                }
            }
        }

        debug!("No auth method supported by both client and server, reply with (0xff)");
        self.inner
            .write_all(&[consts::SOCKS5_VERSION, consts::SOCKS5_AUTH_METHOD_NOT_ACCEPTABLE])
            .await
            .err_when("replying with method not acceptable")?;
        Err(SocksServerError::AuthMethodUnacceptable(methods))
    }
}

impl<T: AsyncRead + AsyncWrite + Unpin> Socks5ServerProtocol<T, states::CommandRead> {
    /// Reply success to the client according to the RFC.
    /// This consumes the wrapper as after this message actual proxying should begin.
    pub async fn reply_success(mut self, sock_addr: SocketAddr) -> Result<T, SocksServerError> {
        self.inner.write(&new_reply(&ReplyError::Succeeded, sock_addr)).await.err_when("writing successful reply")?;

        self.inner.flush().await.err_when("flushing auth reply")?;

        debug!("Wrote success");
        Ok(self.inner)
    }

    /// Reply error to the client with the reply code according to the RFC.
    pub async fn reply_error(mut self, error: &ReplyError) -> Result<(), SocksServerError> {
        let reply = new_reply(error, "0.0.0.0:0".parse().unwrap());
        debug!("reply error to be written: {:?}", reply);

        self.inner.write(&reply).await.err_when("writing unsuccessful reply")?;

        self.inner.flush().await.err_when("flushing auth reply")?;

        Ok(())
    }
}

macro_rules! try_notify {
    ($proto:expr_2021, $e:expr_2021) => {
        match $e {
            Ok(res) => res,
            Err(err) => {
                if let Err(_) = $proto.reply_error(&err.to_reply_error()).await {
                    error!("SOCKS reply-error write failed");
                }
                return Err(err.into());
            }
        }
    };
}

impl<T: AsyncRead + AsyncWrite + Unpin> Socks5ServerProtocol<T, states::Authenticated> {
    /// Decide to whether or not, accept the authentication method.
    /// Don't forget that the methods list sent by the client, contains one or more methods.
    ///
    /// # Request
    /// ```text
    ///          +----+-----+-------+------+----------+----------+
    ///          |VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
    ///          +----+-----+-------+------+----------+----------+
    ///          | 1  |  1  |   1   |  1   | Variable |    2     |
    ///          +----+-----+-------+------+----------+----------+
    /// ```
    ///
    /// It the request is correct, it should returns a ['SocketAddr'].
    ///
    pub async fn read_command(
        mut self,
    ) -> Result<(Socks5ServerProtocol<T, states::CommandRead>, Socks5Command, TargetAddr), SocksServerError> {
        let [version, cmd, rsv, address_type] = read_exact!(self.inner, [0u8; 4]).err_when("reading command")?;
        debug!(
            "Request: [version: {version}, command: {cmd}, rev: {rsv}, address_type: {address_type}]",
            version = version,
            cmd = cmd,
            rsv = rsv,
            address_type = address_type,
        );

        if version != consts::SOCKS5_VERSION {
            return Err(SocksServerError::UnsupportedSocksVersion(version));
        }

        let mut proto = Socks5ServerProtocol::new(self.inner);

        // Guess address type
        let target_addr = try_notify!(proto, read_address(&mut proto.inner, address_type).await);

        debug!("SOCKS request target_kind={}", target_addr.logging_kind());

        let cmd = try_notify!(proto, Socks5Command::from_u8(cmd).ok_or(SocksServerError::UnknownCommand(cmd)));

        Ok((proto, cmd, target_addr))
    }
}
