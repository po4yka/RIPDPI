# DNS-1791554915587312

## Objective

Keep resolver and tunnel identity during ambiguous proxy DNS failure, with real peer recovery evidence.

## Ownership

Android writer: core:service failover policy and tests, XrayProviderE2ETest, and this specification. Coordinator: combined-source gates and publication. No shared schema, lockfile, locale, or baseline changes.

## Execution

- [ ] DNS-1791555372666613 Reproduce real DNS and peer recovery failures before policy changes #bug !high @item:DNS-1791554915587312
- [ ] DNS-1791555373614341 Guard proxy DNS failure attribution and verify direct failure compatibility #bug !high @item:DNS-1791554915587312
- [ ] DNS-1791555374480525 Repeat full Android and routed Xray acceptance with independent review #bug !high @item:DNS-1791554915587312

## Verification

Run :core:service:testDebugUnitTest, :core:service:lintDebug, staticAnalysis, full Android local acceptance, repeated peer-loss acceptance, and separate routed Android Xray acceptance. Verify each report. Observe remote CI on the published commit.
