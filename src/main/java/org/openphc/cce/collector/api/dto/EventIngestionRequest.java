package org.openphc.cce.collector.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Inbound CloudEvents v1.0 envelope DTO.
 * Field names use lowercase per CloudEvents spec (e.g., specversion, datacontenttype).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventIngestionRequest {

    @NotBlank(message = "specversion is required")
    private String specversion;

    @NotBlank(message = "id is required")
    private String id;

    @NotBlank(message = "source is required")
    private String source;

    @NotBlank(message = "type is required")
    private String type;

    @NotBlank(message = "subject is required (patient UPID)")
    private String subject;

    private String time;

    private String datacontenttype;

    // CCE extension attributes (lowercase per CloudEvents spec)
    private String facilityid;
    private String correlationid;
    private String sourceeventid;
    private String protocolinstanceid;
    private String protocoldefinitionid;
    private String actionid;

    @NotNull(message = "data is required")
    private Map<String, Object> data;

    /**
     * Capture any additional extension attributes not explicitly modeled.
     */
    @Builder.Default
    private Map<String, Object> extensions = new HashMap<>();

    @JsonAnySetter
    public void setExtension(String key, Object value) {
        extensions.put(key, value);
    }

    /**
     * Build a full map representation of the raw request (for raw_payload storage).
     */
    public Map<String, Object> toRawPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("specversion", specversion);
        payload.put("id", id);
        payload.put("source", source);
        payload.put("type", type);
        payload.put("subject", subject);
        if (time != null) payload.put("time", time);
        if (datacontenttype != null) payload.put("datacontenttype", datacontenttype);
        if (facilityid != null) payload.put("facilityid", facilityid);
        if (correlationid != null) payload.put("correlationid", correlationid);
        if (sourceeventid != null) payload.put("sourceeventid", sourceeventid);
        if (protocolinstanceid != null) payload.put("protocolinstanceid", protocolinstanceid);
        if (protocoldefinitionid != null) payload.put("protocoldefinitionid", protocoldefinitionid);
        if (actionid != null) payload.put("actionid", actionid);
        payload.put("data", data);
        payload.putAll(extensions);
        return payload;
    }
}
