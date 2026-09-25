# DNS-1790339241109329: Edit DoQ and ODoH DNS resolvers

## Objective

DoQ and ODoH settings open their matching forms without activating invalid or unsupported DNS.

## Ownership

DNS editor lane owns app DNS screen/state/actions, home VPN start guard, focused tests, task and this change. Shared locale resources and generated board serialize at integration.

## Execution

- [x] DNS-1790339324021183 Implement DoQ and ODoH editor routing and persistence #bug @item:DNS-1790339241109329

## Verification

Run targeted app unit and Compose tests, app lint and OpenSpec/task validation. Record device and hosted CI separately after integration.
