package com.taxagent.harness.tool;

public enum ErrorCode {
    INVALID_ARGUMENT, AUTHENTICATION_REQUIRED, PERMISSION_DENIED, FIELD_NOT_ALLOWED,
    UNSUPPORTED_SCOPE, CONFIRMATION_REQUIRED, NOT_FOUND, CONFLICT, STORAGE_ERROR,
    /** T31: the tool exists but the agent running this turn was not given it. */
    CAPABILITY_NOT_GRANTED,
    /** T31: the tool is switched off globally; no agent can use it until it is switched back on. */
    CAPABILITY_DISABLED
}
