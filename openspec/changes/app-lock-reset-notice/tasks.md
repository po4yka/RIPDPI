# DAT-1793534707148297: Persist and show the app lock reset notice

## Objective

Persist and show the app lock reset notice

## Ownership

`core/data/model` proto, the serializer, the settings repository, the backup path, and Settings notice code. `*.proto` and locale resources are serialized lanes with one writer.

## Execution

- [ ] DAT-1793534730084253 Add the proto field, the serializer default, and the contract documentation #bug @item:DAT-1793534707148297
- [ ] DAT-1793534730552835 Set the flag in the PIN reset update and clear it on acknowledgement or a new PIN #bug @item:DAT-1793534707148297
- [ ] DAT-1793534731008449 Show the persisted notice on the Settings list #bug @item:DAT-1793534707148297
- [ ] DAT-1793534731479020 Apply the backup and restore decision #bug @item:DAT-1793534707148297
- [ ] DAT-1793534731937574 Add the old-bytes migration test and the reset and clear tests #bug @item:DAT-1793534707148297

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
