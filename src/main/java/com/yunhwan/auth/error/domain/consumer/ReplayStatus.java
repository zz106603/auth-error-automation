package com.yunhwan.auth.error.domain.consumer;

public enum ReplayStatus {
    NOT_REPLAYABLE,
    REPLAYABLE,
    REPLAY_REQUESTED,
    REPLAYING,
    REPLAYED,
    REPLAY_FAILED,
    DISCARDED,
    BLOCKED
}
