package com.yunhwan.auth.error.usecase.autherror.dto;

public enum DecisionType {
    PROCESS,  // PROCESSED로 확정
    RETRY,    // RETRY로 전환(재시도 흐름으로)
    IGNORE,   // IGNORED로 확정
    RESOLVE,  // RESOLVED로 확정
    FAIL      // FAILED로 확정
}
