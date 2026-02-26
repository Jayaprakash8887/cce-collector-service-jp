package org.openphc.cce.collector.service;

import lombok.extern.slf4j.Slf4j;
import org.openphc.cce.collector.api.dto.EventIngestionRequest;
import org.openphc.cce.collector.api.exception.CloudEventValidationException;
import org.springframework.stereotype.Component;

/**
 * Validates CloudEvents v1.0 envelope — mandatory fields, specversion, format constraints.
 */
@Component
@Slf4j
public class CloudEventValidator {

    private static final String REQUIRED_SPEC_VERSION = "1.0";
    private static final int MAX_ID_LENGTH = 256;

    /**
     * Validate the CloudEvents envelope. Throws CloudEventValidationException on failure.
     */
    public void validate(EventIngestionRequest request) {
        // specversion must be "1.0"
        if (request.getSpecversion() == null || request.getSpecversion().isBlank()) {
            throw new CloudEventValidationException("Missing required CloudEvents field: 'specversion'", "specversion");
        }
        if (!REQUIRED_SPEC_VERSION.equals(request.getSpecversion())) {
            throw new CloudEventValidationException(
                    "specversion must be '1.0', got '" + request.getSpecversion() + "'", "specversion");
        }

        // id — required, non-empty, max 256 chars
        if (request.getId() == null || request.getId().isBlank()) {
            throw new CloudEventValidationException("Missing required CloudEvents field: 'id'", "id");
        }
        if (request.getId().length() > MAX_ID_LENGTH) {
            throw new CloudEventValidationException(
                    "CloudEvents 'id' exceeds max length of " + MAX_ID_LENGTH + " characters", "id");
        }

        // source — required, non-empty
        if (request.getSource() == null || request.getSource().isBlank()) {
            throw new CloudEventValidationException("Missing required CloudEvents field: 'source'", "source");
        }

        // type — required, non-empty
        if (request.getType() == null || request.getType().isBlank()) {
            throw new CloudEventValidationException("Missing required CloudEvents field: 'type'", "type");
        }

        // subject — required by CCE (patient UPID)
        if (request.getSubject() == null || request.getSubject().isBlank()) {
            throw new CloudEventValidationException(
                    "Missing required CloudEvents field: 'subject' (patient UPID required by CCE)", "subject");
        }

        // data — required by CCE
        if (request.getData() == null || request.getData().isEmpty()) {
            throw new CloudEventValidationException("Missing required CloudEvents field: 'data'", "data");
        }

        log.debug("CloudEvents envelope validation passed for event id={}", request.getId());
    }
}
