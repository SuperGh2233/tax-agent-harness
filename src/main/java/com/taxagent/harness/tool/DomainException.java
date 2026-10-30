package com.taxagent.harness.tool;

public final class DomainException extends RuntimeException {
    private final ErrorCode code;

    public DomainException(ErrorCode code, String message) { this(code, message, null); }

    public DomainException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = java.util.Objects.requireNonNull(code);
    }

    public ErrorCode code() { return code; }
}
