package org.openphc.cce.collector.api.exception;

import lombok.Getter;

/**
 * Exception thrown when CloudEvents envelope validation fails.
 */
@Getter
public class CloudEventValidationException extends RuntimeException {

    private final String field;

    public CloudEventValidationException(String message, String field) {
        super(message);
        this.field = field;
    }
}
