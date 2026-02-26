package org.openphc.cce.collector.service;

import lombok.extern.slf4j.Slf4j;
import org.openphc.cce.collector.api.dto.EventIngestionRequest;
import org.openphc.cce.collector.api.exception.FhirValidationException;
import org.openphc.cce.collector.fhir.FhirResourceValidator;
import org.openphc.cce.collector.fhir.FhirResourceValidator.ValidationResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validates FHIR R4 payloads when datacontenttype is application/fhir+json.
 */
@Component
@Slf4j
public class FhirPayloadValidator {

    private static final String FHIR_CONTENT_TYPE = "application/fhir+json";

    private final FhirResourceValidator fhirResourceValidator;
    private final boolean fhirValidationEnabled;
    private final boolean strictMode;

    public FhirPayloadValidator(
            FhirResourceValidator fhirResourceValidator,
            @Value("${cce.collector.fhir-validation.enabled:true}") boolean fhirValidationEnabled,
            @Value("${cce.collector.fhir-validation.strict-mode:false}") boolean strictMode) {
        this.fhirResourceValidator = fhirResourceValidator;
        this.fhirValidationEnabled = fhirValidationEnabled;
        this.strictMode = strictMode;
    }

    /**
     * Validate FHIR payload if applicable.
     * Throws FhirValidationException if validation fails.
     */
    public void validate(EventIngestionRequest request) {
        if (!fhirValidationEnabled) {
            log.debug("FHIR validation disabled, skipping for event id={}", request.getId());
            return;
        }

        String contentType = request.getDatacontenttype();
        if (contentType == null || !FHIR_CONTENT_TYPE.equals(contentType)) {
            log.debug("datacontenttype is not FHIR, skipping FHIR validation for event id={}", request.getId());
            return;
        }

        ValidationResult result = fhirResourceValidator.validate(request.getData(), request.getSubject());

        if (!result.valid()) {
            throw new FhirValidationException(
                    "FHIR R4 payload validation failed", result.errors());
        }

        if (!result.warnings().isEmpty()) {
            if (strictMode) {
                throw new FhirValidationException(
                        "FHIR R4 payload validation warnings (strict mode)", result.warnings());
            }
            result.warnings().forEach(w ->
                    log.warn("FHIR validation warning for event id={}: {}", request.getId(), w));
        }

        log.debug("FHIR payload validation passed for event id={}", request.getId());
    }
}
