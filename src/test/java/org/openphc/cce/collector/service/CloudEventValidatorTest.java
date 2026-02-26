package org.openphc.cce.collector.service;

import org.junit.jupiter.api.Test;
import org.openphc.cce.collector.api.dto.EventIngestionRequest;
import org.openphc.cce.collector.api.exception.CloudEventValidationException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CloudEventValidator.
 */
class CloudEventValidatorTest {

    private final CloudEventValidator validator = new CloudEventValidator();

    @Test
    void shouldAcceptValidCloudEvent() {
        EventIngestionRequest request = EventIngestionRequest.builder()
                .specversion("1.0")
                .id("evt-001")
                .source("rhie-mediator")
                .type("org.openphc.cce.encounter")
                .subject("260225-0002-5501")
                .data(Map.of("resourceType", "Encounter"))
                .build();

        assertDoesNotThrow(() -> validator.validate(request));
    }

    @Test
    void shouldRejectMissingSpecVersion() {
        EventIngestionRequest request = EventIngestionRequest.builder()
                .id("evt-001")
                .source("rhie-mediator")
                .type("org.openphc.cce.encounter")
                .subject("260225-0002-5501")
                .data(Map.of("resourceType", "Encounter"))
                .build();

        CloudEventValidationException ex = assertThrows(
                CloudEventValidationException.class,
                () -> validator.validate(request));
        assertEquals("specversion", ex.getField());
    }

    @Test
    void shouldRejectWrongSpecVersion() {
        EventIngestionRequest request = EventIngestionRequest.builder()
                .specversion("2.0")
                .id("evt-001")
                .source("rhie-mediator")
                .type("org.openphc.cce.encounter")
                .subject("260225-0002-5501")
                .data(Map.of("resourceType", "Encounter"))
                .build();

        CloudEventValidationException ex = assertThrows(
                CloudEventValidationException.class,
                () -> validator.validate(request));
        assertEquals("specversion", ex.getField());
    }

    @Test
    void shouldRejectMissingId() {
        EventIngestionRequest request = EventIngestionRequest.builder()
                .specversion("1.0")
                .source("rhie-mediator")
                .type("org.openphc.cce.encounter")
                .subject("260225-0002-5501")
                .data(Map.of("resourceType", "Encounter"))
                .build();

        CloudEventValidationException ex = assertThrows(
                CloudEventValidationException.class,
                () -> validator.validate(request));
        assertEquals("id", ex.getField());
    }

    @Test
    void shouldRejectMissingSource() {
        EventIngestionRequest request = EventIngestionRequest.builder()
                .specversion("1.0")
                .id("evt-001")
                .type("org.openphc.cce.encounter")
                .subject("260225-0002-5501")
                .data(Map.of("resourceType", "Encounter"))
                .build();

        CloudEventValidationException ex = assertThrows(
                CloudEventValidationException.class,
                () -> validator.validate(request));
        assertEquals("source", ex.getField());
    }

    @Test
    void shouldRejectMissingType() {
        EventIngestionRequest request = EventIngestionRequest.builder()
                .specversion("1.0")
                .id("evt-001")
                .source("rhie-mediator")
                .subject("260225-0002-5501")
                .data(Map.of("resourceType", "Encounter"))
                .build();

        CloudEventValidationException ex = assertThrows(
                CloudEventValidationException.class,
                () -> validator.validate(request));
        assertEquals("type", ex.getField());
    }

    @Test
    void shouldRejectMissingSubject() {
        EventIngestionRequest request = EventIngestionRequest.builder()
                .specversion("1.0")
                .id("evt-001")
                .source("rhie-mediator")
                .type("org.openphc.cce.encounter")
                .data(Map.of("resourceType", "Encounter"))
                .build();

        CloudEventValidationException ex = assertThrows(
                CloudEventValidationException.class,
                () -> validator.validate(request));
        assertEquals("subject", ex.getField());
    }

    @Test
    void shouldRejectMissingData() {
        EventIngestionRequest request = EventIngestionRequest.builder()
                .specversion("1.0")
                .id("evt-001")
                .source("rhie-mediator")
                .type("org.openphc.cce.encounter")
                .subject("260225-0002-5501")
                .build();

        CloudEventValidationException ex = assertThrows(
                CloudEventValidationException.class,
                () -> validator.validate(request));
        assertEquals("data", ex.getField());
    }

    @Test
    void shouldRejectIdExceedingMaxLength() {
        String longId = "x".repeat(257);
        EventIngestionRequest request = EventIngestionRequest.builder()
                .specversion("1.0")
                .id(longId)
                .source("rhie-mediator")
                .type("org.openphc.cce.encounter")
                .subject("260225-0002-5501")
                .data(Map.of("resourceType", "Encounter"))
                .build();

        CloudEventValidationException ex = assertThrows(
                CloudEventValidationException.class,
                () -> validator.validate(request));
        assertEquals("id", ex.getField());
    }
}
