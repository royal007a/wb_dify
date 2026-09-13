package com.hify.runtime;

public enum TerminalReason {
    COMPLETED,
    MAX_TURNS,
    CANCELLED,
    TIMEOUT,
    TOKEN_BUDGET_EXCEEDED,
    TOOL_BUDGET_EXCEEDED,
    PERMISSION_DENIED,
    MODEL_ERROR,
    FATAL_TOOL_ERROR
}
