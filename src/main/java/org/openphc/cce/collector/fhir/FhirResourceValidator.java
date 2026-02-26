package org.openphc.cce.collector.fhir;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validates FHIR R4 resources structurally (not clinical profile conformance).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FhirResourceValidator {

    private final FhirResourceParser fhirResourceParser;

    /**
     * Validate a FHIR resource from CloudEvents data field.
     *
     * @param data       the FHIR resource as a Map
     * @param subject    the CloudEvents subject (patient UPID) for cross-check
     * @return validation result
     */
    public ValidationResult validate(Map<String, Object> data, String subject) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Check resourceType is present
        Optional<String> resourceType = fhirResourceParser.extractResourceType(data);
        if (resourceType.isEmpty()) {
            errors.add("data.resourceType is missing or empty");
            return new ValidationResult(false, errors, warnings);
        }

        // Attempt HAPI FHIR parse
        Optional<IBaseResource> parsed = fhirResourceParser.parse(data);
        if (parsed.isEmpty()) {
            errors.add("FHIR R4 parse failed: data is not a valid FHIR resource");
            return new ValidationResult(false, errors, warnings);
        }

        // Cross-check subject reference (warning only)
        Object subjectRef = data.get("subject");
        if (subjectRef instanceof Map<?, ?> m) {
            Object ref = m.get("reference");
            if (ref instanceof String refStr && subject != null) {
                // e.g., "Patient/260225-0002-5501" should contain subject "260225-0002-5501"
                if (!refStr.contains(subject)) {
                    warnings.add("data.subject.reference '" + refStr +
                            "' does not contain CloudEvents subject '" + subject + "'");
                }
            }
        }

        return new ValidationResult(true, errors, warnings);
    }

    /**
     * Result of FHIR validation.
     */
    public record ValidationResult(
            boolean valid,
            List<String> errors,
            List<String> warnings
    ) {}
}
