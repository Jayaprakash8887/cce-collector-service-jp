package org.openphc.cce.collector.api.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openphc.cce.collector.api.dto.ApiResponse;
import org.openphc.cce.collector.api.dto.DeadLetterDto;
import org.openphc.cce.collector.domain.model.DeadLetterEvent;
import org.openphc.cce.collector.domain.model.enums.RejectionReason;
import org.openphc.cce.collector.service.DeadLetterService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Dead letter management controller — view, query, and retry rejected events.
 */
@RestController
@RequestMapping("/v1/dead-letters")
@RequiredArgsConstructor
@Slf4j
public class DeadLetterController {

    private final DeadLetterService deadLetterService;

    @GetMapping
    public ResponseEntity<ApiResponse<Page<DeadLetterDto>>> listDeadLetters(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String source) {

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt"));

        Page<DeadLetterEvent> deadLetters;
        if (reason != null && !reason.isBlank()) {
            deadLetters = deadLetterService.findByReason(RejectionReason.valueOf(reason.toUpperCase()), pageRequest);
        } else if (source != null && !source.isBlank()) {
            deadLetters = deadLetterService.findBySource(source, pageRequest);
        } else {
            deadLetters = deadLetterService.listUnresolved(pageRequest);
        }

        Page<DeadLetterDto> dtoPage = deadLetters.map(this::toDto);
        return ResponseEntity.ok(ApiResponse.success(dtoPage));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DeadLetterDto>> getDeadLetter(@PathVariable UUID id) {
        return deadLetterService.findById(id)
                .map(dle -> ResponseEntity.ok(ApiResponse.success(toDto(dle))))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<String>> retryDeadLetter(@PathVariable UUID id) {
        return deadLetterService.resolve(id)
                .map(dle -> {
                    log.info("Dead-letter event {} marked as resolved for retry", id);
                    return ResponseEntity.ok(ApiResponse.success("Dead-letter event marked for retry"));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private DeadLetterDto toDto(DeadLetterEvent dle) {
        return DeadLetterDto.builder()
                .id(dle.getId())
                .inboundEventId(dle.getInboundEventId())
                .cloudeventsId(dle.getCloudeventsId())
                .source(dle.getSource())
                .type(dle.getType())
                .subject(dle.getSubject())
                .rawPayload(dle.getRawPayload())
                .rejectionReason(dle.getRejectionReason().name())
                .failureStage(dle.getFailureStage().name())
                .errorDetails(dle.getErrorDetails())
                .correlationId(dle.getCorrelationId())
                .facilityId(dle.getFacilityId())
                .receivedAt(dle.getReceivedAt())
                .retryCount(dle.getRetryCount())
                .nextRetryAt(dle.getNextRetryAt())
                .resolved(dle.isResolved())
                .resolvedAt(dle.getResolvedAt())
                .build();
    }
}
