# EPC-1795085162356362: Deliver opt-in anonymous field analytics for network, blocking, and server health

## Objective

Coordinate R1-R4 so the program invariants hold end to end; this epic owns no code.

## Ownership

No code paths. Child tasks own their paths. Serialized lanes: vendored contract fixtures and schema-version constants are touched by R2 then R4 only.

## Execution

- [ ] EPC-1795085247999873 Track completion of R1 through R4 and the deploy D1-D7 counterparts #epic !high @item:EPC-1795085162356362
- [ ] EPC-1795085253219069 Confirm no upload occurs without opt-in across R3 acceptance #epic !high @item:EPC-1795085162356362
- [ ] EPC-1795085254304865 Confirm report schema and forbidden-field enforcement across R2 and R3 #epic !high @item:EPC-1795085162356362
- [ ] EPC-1795085255682151 Confirm vendored contract drift pins and v2 rejection across R2 and R4 #epic !high @item:EPC-1795085162356362

## Verification

Evidence comes from the child tasks' verification records; `./taskctl validate` and `just task-check`.
