# RST-1794499062183726

## Objective

Repair F1–F6 and prevent late-error replay with separate fix commits.

## Ownership

Use serialized lanes in docs/tasks/issues/repair-hostfake-audit.md. Parent owns task state and combined gates.

## Execution

- [x] RST-1794499244045896 Correct fallback receipts and state raw requirements in settings #bug !high @item:RST-1794499062183726
- [x] RST-1794499244635496 Apply MD5 only to fake ordered segments #bug !high @item:RST-1794499062183726
- [x] RST-1794499245130397 Wrap ordered TCP sequence arithmetic #bug !high @item:RST-1794499062183726
- [x] RST-1794499245610183 Preserve TLS record gaps in fake SNI regions #bug !high @item:RST-1794499062183726
- [x] RST-1794499246094525 Align native and Kotlin hostname validation #bug !high @item:RST-1794499062183726
- [x] RST-1794499246590676 Preserve random HostFake mode in Android configuration #bug !high @item:RST-1794499062183726
- [x] RST-1794499247075214 Keep late ordered send errors terminal #bug !high @item:RST-1794499062183726
- [x] RST-1794499247594825 Review independent fixes and pass combined source gates #bug !high @item:RST-1794499062183726

## Verification

Owner crate/Kotlin tests, independent review, staticAnalysis, ci-gates, architecture health and locked metadata. Nonroot Linux tests do not imply live raw acceptance.
