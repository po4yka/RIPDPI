# Mock Relay

This directory contains the Dockerized mock relay used by the local network
lab. It implements a deliberately small newline-delimited JSON handshake on
port `10080`:

```json
{"auth":"ok"}
```

Successful response:

```json
{"ok":true,"code":"READY","message":"mock relay ready"}
```

Set `MOCK_RELAY_MODE` to exercise failure surfaces:

- `ok` - valid handshake response
- `auth_fail` - typed auth failure response
- `malformed` - invalid JSON response

This is not a production relay protocol implementation. It exists so lab and
automation checks can distinguish relay readiness, auth failure, and malformed
response handling before the reference relay contract is wired into the app.
