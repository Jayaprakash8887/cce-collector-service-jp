package org.openphc.cce.collector.api.exception;

/**
 * Exception thrown when a duplicate event is detected.
 */
public class DuplicateEventException extends RuntimeException {

    public DuplicateEventException(String cloudeventsId, String source) {
        super("Duplicate event: id=" + cloudeventsId + ", source=" + source);
    }
}
