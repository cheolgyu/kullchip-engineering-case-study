//! Small, dependency-free policies for a bounded multi-device sync protocol.
//!
//! This crate intentionally contains no network, database, or product-specific
//! code. Adapters can persist [`DeviceCursor`] values and transport the returned
//! [`CatchUpPlan`] with any stack.

/// A client may request fewer events, but never more than this amount per page.
pub const MAX_EVENT_PAGE_SIZE: usize = 500;

/// Keeps event pages non-empty and caps work per request.
pub fn bounded_event_limit(requested: usize) -> usize {
    requested.clamp(1, MAX_EVENT_PAGE_SIZE)
}

/// Independent version lanes avoid refreshing unrelated entity state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VersionLane {
    Profile,
    Activity,
    Live,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct VersionVector {
    pub profile: u64,
    pub activity: u64,
    pub live: u64,
}

impl VersionVector {
    /// Returns remote lanes that are newer than the caller's local state.
    pub fn stale_lanes_since(self, local: Self) -> Vec<VersionLane> {
        let mut stale = Vec::with_capacity(3);
        if self.profile > local.profile {
            stale.push(VersionLane::Profile);
        }
        if self.activity > local.activity {
            stale.push(VersionLane::Activity);
        }
        if self.live > local.live {
            stale.push(VersionLane::Live);
        }
        stale
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SnapshotReason {
    InitialSync,
    RetentionGap,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CatchUpPlan {
    /// Fetch events strictly after this sequence.
    Delta { from_exclusive_seq: u64 },
    /// Rebuild current state because a safe delta is unavailable.
    Snapshot { reason: SnapshotReason },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceCursor {
    pub device_id: String,
    pub last_seen_event_seq: u64,
}

impl DeviceCursor {
    pub fn new(device_id: impl Into<String>, last_seen_event_seq: u64) -> Self {
        Self {
            device_id: device_id.into(),
            last_seen_event_seq,
        }
    }

    /// Records an acknowledgement without allowing a cursor rewind.
    ///
    /// A client also cannot acknowledge beyond the newest event it is allowed
    /// to see. Persisting adapters should apply the same max operation inside a
    /// transaction or atomic upsert.
    pub fn acknowledge(
        &mut self,
        requested_event_seq: u64,
        latest_accessible_event_seq: u64,
    ) -> AckOutcome {
        let previous = self.last_seen_event_seq;
        let capped_request = requested_event_seq.min(latest_accessible_event_seq);
        self.last_seen_event_seq = previous.max(capped_request);

        AckOutcome {
            previous_event_seq: previous,
            requested_event_seq,
            accepted_event_seq: self.last_seen_event_seq,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AckOutcome {
    pub previous_event_seq: u64,
    pub requested_event_seq: u64,
    pub accepted_event_seq: u64,
}

/// Chooses a bounded delta when history is available, otherwise a snapshot.
pub fn choose_catch_up_plan(cursor: &DeviceCursor, oldest_retained_event_seq: u64) -> CatchUpPlan {
    if cursor.last_seen_event_seq == 0 {
        return CatchUpPlan::Snapshot {
            reason: SnapshotReason::InitialSync,
        };
    }

    if oldest_retained_event_seq > 0 && cursor.last_seen_event_seq < oldest_retained_event_seq {
        return CatchUpPlan::Snapshot {
            reason: SnapshotReason::RetentionGap,
        };
    }

    CatchUpPlan::Delta {
        from_exclusive_seq: cursor.last_seen_event_seq,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn event_limit_is_bounded_to_one_through_five_hundred() {
        assert_eq!(bounded_event_limit(0), 1);
        assert_eq!(bounded_event_limit(1), 1);
        assert_eq!(bounded_event_limit(499), 499);
        assert_eq!(bounded_event_limit(500), 500);
        assert_eq!(bounded_event_limit(501), 500);
        assert_eq!(bounded_event_limit(usize::MAX), 500);
    }

    #[test]
    fn version_vector_reports_only_remote_lanes_that_are_newer() {
        let remote = VersionVector {
            profile: 2,
            activity: 7,
            live: 3,
        };
        let local = VersionVector {
            profile: 2,
            activity: 5,
            live: 1,
        };

        assert_eq!(
            remote.stale_lanes_since(local),
            vec![VersionLane::Activity, VersionLane::Live]
        );
    }

    #[test]
    fn a_new_device_starts_with_a_snapshot() {
        let cursor = DeviceCursor::new("device-alpha", 0);

        assert_eq!(
            choose_catch_up_plan(&cursor, 100),
            CatchUpPlan::Snapshot {
                reason: SnapshotReason::InitialSync
            }
        );
    }

    #[test]
    fn a_cursor_behind_retention_uses_a_snapshot() {
        let cursor = DeviceCursor::new("device-alpha", 99);

        assert_eq!(
            choose_catch_up_plan(&cursor, 100),
            CatchUpPlan::Snapshot {
                reason: SnapshotReason::RetentionGap
            }
        );
    }

    #[test]
    fn a_cursor_inside_retention_uses_a_delta() {
        let cursor = DeviceCursor::new("device-alpha", 100);

        assert_eq!(
            choose_catch_up_plan(&cursor, 100),
            CatchUpPlan::Delta {
                from_exclusive_seq: 100
            }
        );
    }

    #[test]
    fn acknowledgement_is_monotonic() {
        let mut cursor = DeviceCursor::new("device-alpha", 40);

        let advanced = cursor.acknowledge(80, 100);
        assert_eq!(advanced.accepted_event_seq, 80);

        let rewind_attempt = cursor.acknowledge(20, 100);
        assert_eq!(rewind_attempt.accepted_event_seq, 80);
        assert_eq!(cursor.last_seen_event_seq, 80);
    }

    #[test]
    fn acknowledgement_cannot_jump_beyond_accessible_history() {
        let mut cursor = DeviceCursor::new("device-alpha", 40);

        let outcome = cursor.acknowledge(9_999, 120);

        assert_eq!(outcome.requested_event_seq, 9_999);
        assert_eq!(outcome.accepted_event_seq, 120);
        assert_eq!(cursor.last_seen_event_seq, 120);
    }
}
