# TRN-1786299802611226: Consume versioned AmneziaWG revisions and stage interoperability

## Objective

Carry explicit AWG revision identity from import through activation, preserve
current behavior, and prove later semantics only through staged evidence.

## Ownership

Own vendored contracts, parser/storage migration, native revision selection,
typed diagnostics, conformance, and arm64 acceptance. Serialize shared contracts.

## Execution

- [ ] DAT-1786299812200578 Parse, validate, migrate, persist, export, and restore canonical AWG revision identity #feature !high @item:TRN-1786299802611226
- [ ] RST-1786299812220622 Add closed native revision selection with current-equivalence and unsupported-revision refusal #feature !high @item:TRN-1786299802611226 @blocked_by:DAT-1786299812200578
- [ ] TRN-1786299812241122 Surface typed pre-activation compatibility outcomes through service and UI #feature !high @item:TRN-1786299802611226 @blocked_by:RST-1786299812220622
- [ ] TST-1786299812261451 Add cross-repository, upstream-pinned, cross-stack, and negative revision fixtures #feature !high @item:TRN-1786299802611226 @blocked_by:TRN-1786299812241122
- [ ] AND-1786299812282130 Prove the staged later revision on physical arm64 and keep production eligibility fail closed #feature !high @item:TRN-1786299802611226 @blocked_by:TST-1786299812261451

## Verification

Run cross-repository drift, parser/storage, native/JNI, UI, and task gates before staging and physical-device evidence.
