# RLY-1794477185649013

## Execution

- [x] RLY-1794477279590881 Reject standalone obfs4 relay and verify Tor remains available #bug !high @item:RLY-1794477185649013
- [x] RLY-1794477280143697 Preserve valid Tor identities through permission preflight #bug !high @item:RLY-1794477185649013
- [x] RLY-1794477280711169 Complete managed PT startup negotiation and failure checks #bug !high @item:RLY-1794477185649013
- [x] RLY-1794477281462969 Close PT socket admission during stop and verify lifecycle #bug !high @item:RLY-1794477185649013
- [x] RLY-1794477282071499 Escape all PT argument separators and verify encoding #bug !high @item:RLY-1794477185649013
- [ ] RLY-1794477282626394 Verify artifacts and combined gates then integrate reviewed fixes #bug !high @item:RLY-1794477185649013

## Ownership and gates

The portfolio record owns the exact parallel file lanes. Each worker must preserve that assignment and use a dedicated worktree. Coordinator owns this file.

Run the owning service/app unit suites, affected module ktlint/detekt checks, and just ci-gates on the combined tree. Use the shared build gate for compilation. Do not bless UI fixtures or change baselines. Record real-helper evidence separately from tests with local doubles. Independent review precedes implementation commits.
- [x] RLY-1794478726909442 Reject malformed obfs4 certificates before Tor activation #bug !high @item:RLY-1794477185649013
- [x] RLY-1794478845733549 Isolate stopped helper output from later process runs #bug !high @item:RLY-1794477185649013
- [x] RLY-1794479241816365 Retain helper errors through local SOCKS readiness failure #bug !high @item:RLY-1794477185649013
