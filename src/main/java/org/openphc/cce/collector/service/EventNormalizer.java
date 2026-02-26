package org.openphc.cce.collector.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes event metadata — standardizes event types, fills defaults, extracts metadata.
 */
@Component
@Slf4j
public class EventNormalizer {

    private static final Pattern CCE_ACTION_PATTERN =
            Pattern.compile("^cce\\.([a-z]+)\\.(?:created|updated|deleted)$");

    /**
     * Normalize event type to org.openphc.cce.* pattern.
     */
    public String normalizeEventType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return rawType;
        }
        if (rawType.startsWith("org.openphc.cce.")) {
            return rawType; // Already normalized
        }
        // cce.observation.created → org.openphc.cce.observation
        Matcher m = CCE_ACTION_PATTERN.matcher(rawType);
        if (m.matches()) {
            String normalized = "org.openphc.cce." + m.group(1);
            log.debug("Normalized event type '{}' → '{}'", rawType, normalized);
            return normalized;
        }
        // Unknown pattern — pass through
        log.debug("Event type '{}' does not match known patterns, passing through", rawType);
        return rawType;
    }

    /**
     * Ensure correlation ID is present — generate one if absent.
     */
    public String ensureCorrelationId(String existing) {
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String generated = "corr-" + UUID.randomUUID();
        log.debug("Generated correlation ID: {}", generated);
        return generated;
    }

    /**
     * Ensure event time is present — fill with server received timestamp if absent.
     */
    public OffsetDateTime ensureEventTime(String rawTime) {
        if (rawTime != null && !rawTime.isBlank()) {
            try {
                return OffsetDateTime.parse(rawTime);
            } catch (Exception e) {
                log.warn("Failed to parse event time '{}', using server time", rawTime);
            }
        }
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
