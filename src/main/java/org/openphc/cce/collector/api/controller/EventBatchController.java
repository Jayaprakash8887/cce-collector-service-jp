package org.openphc.cce.collector.api.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openphc.cce.collector.api.dto.ApiResponse;
import org.openphc.cce.collector.api.dto.BatchIngestionRequest;
import org.openphc.cce.collector.api.dto.BatchIngestionResponse;
import org.openphc.cce.collector.service.EventIngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * POST /v1/events/batch — batch event ingestion endpoint.
 */
@RestController
@RequestMapping("/v1/events/batch")
@RequiredArgsConstructor
@Slf4j
public class EventBatchController {

    private final EventIngestionService ingestionService;

    @PostMapping
    public ResponseEntity<ApiResponse<BatchIngestionResponse>> ingestBatch(
            @Valid @RequestBody BatchIngestionRequest request) {

        log.info("Received batch of {} events", request.getEvents().size());

        BatchIngestionResponse response = ingestionService.ingestBatch(request.getEvents());

        log.info("Batch processed: total={}, accepted={}, rejected={}",
                response.getTotal(), response.getAccepted(), response.getRejected());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(response));
    }
}
