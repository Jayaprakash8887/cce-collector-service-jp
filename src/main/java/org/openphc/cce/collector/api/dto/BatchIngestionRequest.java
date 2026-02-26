package org.openphc.cce.collector.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request for batch event ingestion.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchIngestionRequest {

    @NotEmpty(message = "events list must not be empty")
    @Size(max = 100, message = "batch may contain at most 100 events")
    @Valid
    private List<EventIngestionRequest> events;
}
