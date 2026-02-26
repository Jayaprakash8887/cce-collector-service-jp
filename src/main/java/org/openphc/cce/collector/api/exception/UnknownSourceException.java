package org.openphc.cce.collector.api.exception;

/**
 * Exception thrown when event source is not registered or inactive.
 */
public class UnknownSourceException extends RuntimeException {

    public UnknownSourceException(String source) {
        super("Unknown or inactive source: " + source);
    }
}
