package org.openphc.cce.collector.api.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openphc.cce.collector.api.dto.*;
import org.openphc.cce.collector.service.EventIngestionService;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * POST /v1/events — main event ingestion endpoint.
 * Receives CloudEvents v1.0 envelopes from external systems.
 */
@RestController
@RequestMapping("/v1/events")
@RequiredArgsConstructor
@Slf4j
public class EventIngestionController {

    private final EventIngestionService ingestionService;

    @PostMapping
    public ResponseEntity<ApiResponse<EventIngestionResponse>> ingestEvent(
            @Valid @RequestBody EventIngestionRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        String effectiveCorrelationId = correlationId != null ? correlationId : request.getCorrelationid();
        MDC.put("correlationId", effectiveCorrelationId);
        MDC.put("source", request.getSource());
        MDC.put("eventId", request.getId());
        MDC.put("subject", request.getSubject());

        try {
            log.info("Received event: id={}, source={}, type={}, subject={}",
                    request.getId(), request.getSource(), request.getType(), request.getSubject());

            EventIngestionResponse response = ingestionService.ingest(request);
            HttpStatus status = response.isDuplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;

            return ResponseEntity.status(status)
                    .body(ApiResponse.success(response));
        } finally {
            MDC.clear();
        }
    }
}
