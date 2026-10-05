use bounded_sync_core::{bounded_event_limit, choose_catch_up_plan, DeviceCursor, VersionVector};

fn main() {
    let remote = VersionVector {
        profile: 4,
        activity: 9,
        live: 3,
    };
    let local = VersionVector {
        profile: 4,
        activity: 7,
        live: 1,
    };
    println!("stale lanes: {:?}", remote.stale_lanes_since(local));

    let mut cursor = DeviceCursor::new("device-demo", 80);
    println!("plan: {:?}", choose_catch_up_plan(&cursor, 50));
    println!("page size: {}", bounded_event_limit(10_000));
    println!("ack: {:?}", cursor.acknowledge(150, 120));
}
