package org.openphc.cce.collector.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Parses FHIR R4 resources from JSON using HAPI FHIR.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FhirResourceParser {

    private final FhirContext fhirContext;
    private final ObjectMapper objectMapper;

    /**
     * Parse a Map (from CloudEvents data) into a HAPI FHIR IBaseResource.
     *
     * @param data the FHIR resource as a Map
     * @return parsed resource, or empty if parsing fails
     */
    public Optional<IBaseResource> parse(Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            IParser parser = fhirContext.newJsonParser();
            parser.setPrettyPrint(false);
            IBaseResource resource = parser.parseResource(json);
            return Optional.of(resource);
        } catch (DataFormatException e) {
            log.warn("FHIR parse error: {}", e.getMessage());
            return Optional.empty();
        } catch (JsonProcessingException e) {
            log.warn("JSON serialization error for FHIR data: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Extract the resourceType from a Map representation.
     */
    public Optional<String> extractResourceType(Map<String, Object> data) {
        Object rt = data.get("resourceType");
        if (rt instanceof String s && !s.isBlank()) {
            return Optional.of(s);
        }
        return Optional.empty();
    }
}
