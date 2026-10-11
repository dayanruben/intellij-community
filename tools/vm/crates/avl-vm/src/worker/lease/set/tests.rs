//! The typed seam. The command's two reply shapes are pinned in the lease suite, because those are a wire contract;
//! what is pinned here is that nothing inside this process has to read that wire to hold or free a worker.

use avl_base::GuestOs;
use pretty_assertions::assert_eq;

use super::*;
use crate::worker::hypervisor::SlotState;
#[cfg(unix)]
use crate::worker::lease::receipt::receipt_files;
use crate::worker::lease::receipt_for_path;
#[cfg(unix)]
use avl_host_testkit::FakeProbe;

use crate::worker::lease::tests::{ctx, pool, request};

#[tokio::test]
async fn acquired_workers_carry_the_handle_that_frees_them() {
    let fixture = pool("air-macos-1,air-macos-2");
    let held = acquire_workers(&ctx(), &fixture.manager, &request("shardable", 2, false))
        .await
        .unwrap();
    assert_eq!(held.len(), 2);
    for (index, item) in held.iter().enumerate() {
        // Every value the caller needs is here: the lease it holds, and the receipt that is its only handle. A receipt
        // that does not validate is a worker nobody can use and nobody can free.
        let (path, validated) = receipt_for_path(&fixture.settings, &item.receipt).unwrap();
        assert_eq!((path, &validated), (item.receipt.clone(), &item.lease));
        assert!(!item.recovered);
        // Each shard is its own holder, which is what lets each of them recover independently.
        assert_eq!(item.lease.holder, format!("shardable#{}", index + 1));
        // The acquisition time is a millisecond UTC stamp.
        assert!(
            jiff::fmt::strtime::parse("%Y-%m-%dT%H:%M:%S%.3fZ", &item.lease.acquired_at).is_ok()
                && item.lease.acquired_at.len() == "2026-08-23T00:00:00.000Z".len(),
            "{}",
            item.lease.acquired_at
        );
    }
    // The pool lock is held for the whole survey, so a second caller finds the pool full rather than taking a worker
    // between the survey and the placements.
    let refusal = acquire_workers(&ctx(), &fixture.manager, &request("second", 1, false))
        .await
        .unwrap_err();
    assert_eq!(refusal.code, "pool_exhausted");
}

/// A count is a ceiling, and the seam says so with values rather than with two counts in a document: what came back is
/// the length of what came back.
#[tokio::test]
async fn acquire_workers_answers_fewer_rather_than_waiting() {
    let fixture = pool("air-macos-1");
    let held = acquire_workers(&ctx(), &fixture.manager, &request("shardable", 3, false))
        .await
        .unwrap();
    assert_eq!(held.len(), 1);

    // `exact` is the measurement run's escape hatch, and it unwinds what it placed rather than leaving a partly-owned
    // set behind.
    let fixture = pool("air-macos-1");
    let refusal = acquire_workers(&ctx(), &fixture.manager, &request("measurement", 2, true))
        .await
        .unwrap_err();
    assert_eq!(refusal.code, "pool_exhausted");
    assert_eq!(read_lease(&fixture.settings.lease_path(fixture.worker(0))).unwrap(), None);
}

/// One refusal must not hide the others. A release is best effort by the time it runs, so the loop finishes the set and
/// answers per worker, and the caller decides what to say about a worker that stayed held.
// Liveness on Tart is the pid receipt, and a Windows host has no Tart worker.
#[cfg(unix)]
#[tokio::test]
async fn release_workers_finishes_the_set_and_names_what_stayed_held() {
    let fixture = pool("air-macos-1,air-macos-2");
    let held = acquire_workers(&ctx(), &fixture.manager, &request("shardable", 2, false))
        .await
        .unwrap();
    // The first worker reads as running - liveness on Tart is the pid receipt - so its release goes through the guest,
    // and this fixture's guest answers nothing the release gate accepts.
    let stuck = held[0].lease.worker.clone();
    fixture.run_as_fake_process(&stuck).await;

    let results = release_workers(&ctx(), &fixture.manager, &held, &FakeProbe::default()).await;
    assert_eq!(results.len(), 2);
    assert_eq!(results[0].worker, stuck);
    assert!(!results[0].released && results[0].failure.is_some(), "{results:?}");
    // The answer carries the handle that still frees it.
    assert_eq!(results[0].receipt, held[0].receipt);
    assert!(results[1].released && results[1].failure.is_none(), "{results:?}");
    assert_eq!(disposition(&results), LeaseDisposition::ReleaseFailed);
    assert_eq!(disposition(&results[1..]), LeaseDisposition::Released);
    assert_eq!(serde_json::to_value(LeaseDisposition::ReleaseFailed).unwrap(), "release_failed");

    // What the answers say is what is on disk: one worker held with its receipt, one worker free with none.
    assert!(read_lease(&fixture.settings.lease_path(&stuck)).unwrap().is_some());
    assert_eq!(read_lease(&fixture.settings.lease_path(&results[1].worker)).unwrap(), None);
    assert_eq!(receipt_files(&fixture.settings), vec![held[0].receipt.clone()]);
}

/// The check is public because a caller can assemble a set this module did not place: the flake harness mixes a
/// receipt its invoker supplied with the workers it leased itself.
#[test]
fn require_one_guest_os_refuses_a_set_a_caller_assembled() {
    let fixture = pool("air-macos-1,air-macos-2");
    let given = Lease {
        guest_os: GuestOs::Linux,
        ..fixture.new_lease(fixture.worker(0), "token", "the-invoker")
    };
    let ours = fixture.new_lease(fixture.worker(1), "token", "vm-flake");
    let held = |lease: &Lease, receipt: &str| HeldWorker {
        lease: lease.clone(),
        receipt: PathBuf::from(receipt),
        recovered: false,
        slot_reason: SlotReason::Borrowed,
    };

    let refusal = require_one_guest_os(&fixture.settings, &[held(&given, "given.json"), held(&ours, "ours.json")]).unwrap_err();
    assert_eq!(refusal.code, "lease_guest_os_mismatch");
    assert!(
        refusal.message.contains(fixture.worker(0)) && refusal.message.contains("one run cannot span guests"),
        "{refusal}"
    );
    // A set of one guest is what every other caller has, and it passes.
    require_one_guest_os(&fixture.settings, &[held(&ours, "ours.json")]).unwrap();
}

// --- the slot preference --------------------------------------------------------------------------------------

fn slot(worker: &str, reason: SlotReason) -> SlotFacts {
    SlotFacts {
        worker: worker.to_owned(),
        reason,
    }
}

fn workers<'a>(ranked: &[&'a SlotFacts]) -> Vec<&'a str> {
    ranked.iter().map(|slot| slot.worker.as_str()).collect()
}

/// A free slot is classed by its state and its daemon record, and a slot of no running state keeps class 2.
#[test]
fn a_slot_is_classed_by_its_state_and_its_daemon() {
    assert_eq!(SlotReason::of(SlotState::Running, true), SlotReason::WarmDaemon);
    assert_eq!(SlotReason::of(SlotState::Running, false), SlotReason::Running);
    assert_eq!(SlotReason::of(SlotState::Unknown, true), SlotReason::Unranked);
    assert_eq!(SlotReason::of(SlotState::Stopped, true), SlotReason::Stopped);
    assert_eq!(SlotReason::of(SlotState::Absent, false), SlotReason::Absent);
}

/// The four classes come in order: the warm daemon, a running slot, a stopped slot, an absent slot.
#[test]
fn the_ranking_puts_the_four_classes_in_order() {
    let facts = [
        slot("air-docker-1", SlotReason::Absent),
        slot("air-docker-2", SlotReason::Stopped),
        slot("air-docker-3", SlotReason::Running),
        slot("air-docker-4", SlotReason::WarmDaemon),
    ];
    assert_eq!(
        workers(&rank_slots(&facts)),
        ["air-docker-4", "air-docker-3", "air-docker-2", "air-docker-1"]
    );
}

/// Within one class the pool order stays, so a pool of one class, or of no running state, keeps today's order.
#[test]
fn the_ranking_keeps_the_pool_order_within_a_class() {
    let facts = [
        slot("air-docker-1", SlotReason::Unranked),
        slot("air-docker-2", SlotReason::Running),
        slot("air-docker-3", SlotReason::Unranked),
        slot("air-docker-4", SlotReason::Stopped),
        slot("air-docker-5", SlotReason::Stopped),
    ];
    assert_eq!(
        workers(&rank_slots(&facts)),
        ["air-docker-1", "air-docker-2", "air-docker-3", "air-docker-4", "air-docker-5"]
    );
}

/// Each pending shard takes the next slot of the same ranking, so a shard of two over a mixed pool takes the two
/// warmest slots.
#[test]
fn a_shard_of_two_takes_the_two_warmest_slots() {
    let facts = [
        slot("air-docker-1", SlotReason::Absent),
        slot("air-docker-2", SlotReason::Running),
        slot("air-docker-3", SlotReason::Stopped),
        slot("air-docker-4", SlotReason::WarmDaemon),
    ];
    let ranked = rank_slots(&facts);
    assert_eq!(workers(&ranked[..2]), ["air-docker-4", "air-docker-2"]);
}

/// A lease lands on the slot the backend knows, before a slot with no machine, and the reply names the reason. The
/// fake Tart knows the second slot only, and runs none.
#[cfg(unix)]
#[tokio::test]
async fn a_lease_prefers_a_stopped_slot_to_an_absent_one() {
    let fixture = pool("air-macos-1,air-macos-2");
    fixture.fake.answer(avl_testkit::tartfake::Answer::ListQuiet, "air-macos-2\n");
    let held = acquire_workers(&ctx(), &fixture.manager, &request("warm", 1, false)).await.unwrap();
    assert_eq!(
        (held[0].lease.worker.as_str(), held[0].slot_reason),
        ("air-macos-2", SlotReason::Stopped)
    );
    // A second lease takes the absent slot that is left.
    let second = acquire_workers(&ctx(), &fixture.manager, &request("cold", 1, false)).await.unwrap();
    assert_eq!(second[0].lease.worker, "air-macos-1");
}

/// The holder-recovery path comes first: a holder that already has a lease gets it back, whatever the ranking says.
#[cfg(unix)]
#[tokio::test]
async fn the_recovery_path_comes_before_the_ranking() {
    let fixture = pool("air-macos-1,air-macos-2");
    let first = acquire_workers(&ctx(), &fixture.manager, &request("again", 1, false))
        .await
        .unwrap();
    assert_eq!(first[0].lease.worker, "air-macos-1");
    fixture.fake.answer(avl_testkit::tartfake::Answer::ListQuiet, "air-macos-2\n");
    let recovered = acquire_workers(&ctx(), &fixture.manager, &request("again", 1, false))
        .await
        .unwrap();
    assert_eq!(
        (recovered[0].lease.worker.as_str(), recovered[0].slot_reason),
        ("air-macos-1", SlotReason::Recovered)
    );
}

/// The fake Tart pool knows no slot, so every slot is of one class, and a shard over it keeps the pool order.
#[cfg(unix)]
#[tokio::test]
async fn a_pool_of_one_class_keeps_its_order() {
    let fixture = pool("air-macos-1,air-macos-2,air-macos-3");
    let held = acquire_workers(&ctx(), &fixture.manager, &request("order", 3, false))
        .await
        .unwrap();
    let placed: Vec<(&str, SlotReason)> = held.iter().map(|item| (item.lease.worker.as_str(), item.slot_reason)).collect();
    assert_eq!(
        placed,
        [
            ("air-macos-1", SlotReason::Absent),
            ("air-macos-2", SlotReason::Absent),
            ("air-macos-3", SlotReason::Absent),
        ]
    );
}
