package org.openphc.cce.collector.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * CloudEventMessage — the Kafka message published to cce.events.inbound.
 * Uses camelCase field names matching the Compliance Service consumer contract.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CloudEventMessage {

    private String id;
    private String source;
    private String type;
    private String specVersion;
    private String subject;
    private OffsetDateTime time;
    private String dataContentType;
    private String correlationId;
    private String sourceEventId;
    private String protocolInstanceId;
    private String protocolDefinitionId;
    private String actionId;
    private String facilityId;
    private Map<String, Object> data;
}
