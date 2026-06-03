/// Use this trait to handle a custom authentication on your end.
///
/// The `authenticate` future is declared `+ Send` so it preserves the Send
/// guarantee that `#[async_trait]`'s boxed future used to provide: the SOCKS5
/// server `.await`s it inside the per-connection task, which is spawned onto a
/// multi-thread runtime. Native `async fn` in traits (RPITIT) is stable since
/// 1.75; the trait is only ever used via generic `A: Authentication` bounds
/// (never `dyn Authentication`), so it stays object-safe-free by design.
pub trait Authentication: Send + Sync {
    type Item;

    fn authenticate(
        &self,
        credentials: Option<(String, String)>,
    ) -> impl Future<Output = Option<Self::Item>> + Send;
}

async fn authenticate_callback<T: AsyncRead + AsyncWrite + Unpin, A: Authentication>(
    auth_callback: &A,
    auth: StandardAuthenticationStarted<T>,
) -> Result<(Socks5ServerProtocol<T, states::Authenticated>, A::Item), SocksServerError> {
    match auth {
        StandardAuthenticationStarted::NoAuthentication(auth) => {
            match auth_callback.authenticate(None).await { Some(credentials) => {
                Ok((auth.finish_auth(), credentials))
            } _ => {
                Err(SocksServerError::AuthenticationRejected)
            }}
        }
        StandardAuthenticationStarted::PasswordAuthentication(auth) => {
            let (username, password, auth) = auth.read_username_password().await?;
            match auth_callback.authenticate(Some((username, password))).await { Some(credentials) => {
                Ok((auth.accept().await?.finish_auth(), credentials))
            } _ => {
                auth.reject().await?;
                Err(SocksServerError::AuthenticationRejected)
            }}
        }
    }
}

/// Basic user/pass auth method provided.
pub struct SimpleUserPassword {
    pub username: String,
    pub password: String,
}

/// The struct returned when the user has successfully authenticated
pub struct AuthSucceeded {
    pub username: String,
}

/// This is an example to auth via simple credentials.
/// If the auth succeed, we return the username authenticated with, for further uses.
impl Authentication for SimpleUserPassword {
    type Item = AuthSucceeded;

    async fn authenticate(&self, credentials: Option<(String, String)>) -> Option<Self::Item> {
        if let Some((username, password)) = credentials {
            // Client has supplied credentials
            if username == self.username && password == self.password {
                // Some() will allow the authentication and the credentials
                // will be forwarded to the socket
                Some(AuthSucceeded { username })
            } else {
                // Credentials incorrect, we deny the auth
                None
            }
        } else {
            // The client hasn't supplied any credentials, which only happens
            // when `Config::allow_no_auth()` is set as `true`
            None
        }
    }
}

/// This will simply return Option::None, which denies the authentication
#[derive(Copy, Clone, Default)]
pub struct DenyAuthentication {}

impl Authentication for DenyAuthentication {
    type Item = ();

    async fn authenticate(&self, _credentials: Option<(String, String)>) -> Option<Self::Item> {
        None
    }
}

/// While this one will always allow the user in.
#[derive(Copy, Clone, Default)]
pub struct AcceptAuthentication {}

impl Authentication for AcceptAuthentication {
    type Item = ();

    async fn authenticate(&self, _credentials: Option<(String, String)>) -> Option<Self::Item> {
        Some(())
    }
}
