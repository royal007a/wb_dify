package com.hify.runtime.plan;

public enum ReplanAction {
    RETRY,
    LOCAL_REPLAN,
    SUFFIX_REPLAN,
    FULL_REPLAN,
    ASK_HUMAN,
    STOP
}
