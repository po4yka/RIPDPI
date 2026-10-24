# TRN-1792759596946903

## Ownership

The storage migration writer owns `ProxyGroupStores.kt`, `ProxyGroupCatalog.kt`,
`StoredHealthPolicyMigration.kt` and their catalogue upgrade regression tests in
`core/data/runtime-state/`. This lane repairs the existing local upgrade contract;
it does not change wire admission, native probes, journal replay or task closure.

## Execution

- [ ] TRN-1792760231865743 Import schema v2 quality policy and persist the device-global byte budget #feature !high @item:TRN-1792759596946903
- [ ] TRN-1792760232586356 Measure real relay and Reality transfer goodput and overlapping control latency #feature !high @item:TRN-1792759596946903
- [ ] TRN-1792760233292624 Integrate stable quality decisions and observable evidence into the existing selector #feature !high @item:TRN-1792759596946903
- [ ] TRN-1792760233980732 Present quality and scoped PMTU lower bounds in existing UI across all locales #feature !high @item:TRN-1792759596946903
- [ ] TRN-1792760234710191 Verify native positive acceptance and combined gates through protected PR and main #feature !high @item:TRN-1792759596946903
