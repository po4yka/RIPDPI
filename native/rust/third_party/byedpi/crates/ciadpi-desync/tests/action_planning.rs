use ciadpi_config::{DesyncGroup, DesyncMode, OffsetExpr, PartSpec};
use ciadpi_desync::{plan_tcp, DesyncAction};

#[test]
fn fake_md5sig_plan_restores_socket_state_after_fake_write() {
    let mut group = DesyncGroup::new(0);
    group.md5sig = true;
    group.fake_data = Some(b"GET /f HTTP/1.1\r\nHost: fake.example.test\r\n\r\n".to_vec());
    group.parts.push(PartSpec { mode: DesyncMode::Fake, offset: OffsetExpr { pos: 8, flag: 0, repeats: 1, skip: 0 } });

    let plan =
        plan_tcp(&group, b"GET / HTTP/1.1\r\nHost: www.wikipedia.org\r\n\r\n", 7, 64).expect("plan should succeed");

    assert_eq!(
        plan.actions,
        vec![
            DesyncAction::SetTtl(8),
            DesyncAction::SetMd5Sig { key_len: 5 },
            DesyncAction::Write(b"GET /f H".to_vec()),
            DesyncAction::SetMd5Sig { key_len: 0 },
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::SetTtl(64),
            DesyncAction::Write(b"TP/1.1\r\nHost: www.wikipedia.org\r\n\r\n".to_vec()),
        ]
    );
}

#[test]
fn disorder_plan_waits_before_restoring_ttl() {
    let mut group = DesyncGroup::new(0);
    group
        .parts
        .push(PartSpec { mode: DesyncMode::Disorder, offset: OffsetExpr { pos: 8, flag: 0, repeats: 1, skip: 0 } });

    let plan =
        plan_tcp(&group, b"GET / HTTP/1.1\r\nHost: www.wikipedia.org\r\n\r\n", 7, 64).expect("plan should succeed");

    assert_eq!(
        plan.actions,
        vec![
            DesyncAction::SetTtl(1),
            DesyncAction::Write(b"GET / HT".to_vec()),
            DesyncAction::AwaitWritable,
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::Write(b"TP/1.1\r\nHost: www.wikipedia.org\r\n\r\n".to_vec()),
        ]
    );
}
