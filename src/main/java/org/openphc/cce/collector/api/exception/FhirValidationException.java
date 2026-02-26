package org.openphc.cce.collector.api.exception;

import lombok.Getter;

import java.util.List;

/**
 * Exception thrown when FHIR payload validation fails.
 */
@Getter
public class FhirValidationException extends RuntimeException {

    private final List<String> errors;

    public FhirValidationException(String message, List<String> errors) {
        super(message);
        this.errors = errors;
    }
}
