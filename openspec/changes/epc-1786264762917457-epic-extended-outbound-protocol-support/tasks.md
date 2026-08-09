# EPC-1786264762917457: Epic - Extended outbound protocol support

## Objective

Close the remaining SSH trust, Mieru carrier/interoperability, and AnyTLS interoperability gaps without reopening completed protocol scaffolding.

## Ownership

- Child tasks `OUT-1786264762917254`, `OUT-1786264762917513`, and `OUT-1786264762917551`
- serialized relay registry and profile-schema lanes remain child-owned

## Execution

- [ ] EPC-1786266573979241 Complete the SSH, Mieru, and AnyTLS child acceptance gates #epic @item:EPC-1786264762917457
- [ ] EPC-1786266573979087 Verify bounded lifecycle and truthful selectable capabilities for all three protocols #epic @item:EPC-1786264762917457 @blocked_by:EPC-1786266573979241

## Verification

- each child OpenSpec verification matrix
- combined relay/profile regression suite after all children land
