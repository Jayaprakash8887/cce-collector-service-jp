package org.openphc.cce.collector.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Response for a single event ingestion request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventIngestionResponse {

    private String eventId;
    private String status;          // "accepted" or "duplicate"
    private String correlationId;
    private String publishedTopic;
    private OffsetDateTime receivedAt;
    private String reason;          // only set for rejected events in batch
    private String details;         // only set for rejected events in batch

    public boolean isDuplicate() {
        return "duplicate".equals(status);
    }
}
