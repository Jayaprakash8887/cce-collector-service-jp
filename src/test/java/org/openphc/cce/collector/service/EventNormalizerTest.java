package org.openphc.cce.collector.service;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EventNormalizer.
 */
class EventNormalizerTest {

    private final EventNormalizer normalizer = new EventNormalizer();

    // --- Event Type Normalization ---

    @Test
    void shouldPassThroughAlreadyNormalizedType() {
        assertEquals("org.openphc.cce.encounter",
                normalizer.normalizeEventType("org.openphc.cce.encounter"));
    }

    @Test
    void shouldNormalizeCceCreatedPattern() {
        assertEquals("org.openphc.cce.observation",
                normalizer.normalizeEventType("cce.observation.created"));
    }

    @Test
    void shouldNormalizeCceUpdatedPattern() {
        assertEquals("org.openphc.cce.encounter",
                normalizer.normalizeEventType("cce.encounter.updated"));
    }

    @Test
    void shouldNormalizeCceDeletedPattern() {
        assertEquals("org.openphc.cce.condition",
                normalizer.normalizeEventType("cce.condition.deleted"));
    }

    @Test
    void shouldPassThroughUnknownPatterns() {
        assertEquals("custom.event.type",
                normalizer.normalizeEventType("custom.event.type"));
    }

    @Test
    void shouldHandleNullType() {
        assertNull(normalizer.normalizeEventType(null));
    }

    @Test
    void shouldHandleBlankType() {
        assertEquals("", normalizer.normalizeEventType(""));
    }

    // --- Correlation ID ---

    @Test
    void shouldReturnExistingCorrelationId() {
        assertEquals("corr-existing-id",
                normalizer.ensureCorrelationId("corr-existing-id"));
    }

    @Test
    void shouldGenerateCorrelationIdWhenNull() {
        String generated = normalizer.ensureCorrelationId(null);
        assertNotNull(generated);
        assertTrue(generated.startsWith("corr-"));
    }

    @Test
    void shouldGenerateCorrelationIdWhenBlank() {
        String generated = normalizer.ensureCorrelationId("  ");
        assertNotNull(generated);
        assertTrue(generated.startsWith("corr-"));
    }

    // --- Event Time ---

    @Test
    void shouldParseValidTime() {
        OffsetDateTime result = normalizer.ensureEventTime("2026-02-25T08:00:00Z");
        assertEquals(2026, result.getYear());
        assertEquals(2, result.getMonthValue());
        assertEquals(25, result.getDayOfMonth());
    }

    @Test
    void shouldGenerateTimeWhenNull() {
        OffsetDateTime result = normalizer.ensureEventTime(null);
        assertNotNull(result);
        assertEquals(ZoneOffset.UTC, result.getOffset());
    }

    @Test
    void shouldGenerateTimeWhenBlank() {
        OffsetDateTime result = normalizer.ensureEventTime("  ");
        assertNotNull(result);
    }

    @Test
    void shouldFallbackOnInvalidTimeFormat() {
        OffsetDateTime result = normalizer.ensureEventTime("not-a-date");
        assertNotNull(result);
    }
}
