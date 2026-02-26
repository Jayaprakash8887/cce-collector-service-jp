package org.openphc.cce.collector.service;

import lombok.extern.slf4j.Slf4j;
import org.openphc.cce.collector.domain.repository.InboundEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Deduplication via PostgreSQL.
 * Checks the inbound_event table for existing records within a configurable lookback window.
 */
@Service
@Slf4j
public class DeduplicationService {

    private final InboundEventRepository inboundEventRepository;
    private final int lookbackDays;

    public DeduplicationService(
            InboundEventRepository inboundEventRepository,
            @Value("${cce.collector.dedup.lookback-days:30}") int lookbackDays) {
        this.inboundEventRepository = inboundEventRepository;
        this.lookbackDays = lookbackDays;
        log.info("Deduplication configured with lookback window of {} days", lookbackDays);
    }

    /**
     * Check if an event with the same (cloudeventsId, source) already exists
     * within the configured lookback window.
     *
     * @return true if a duplicate is found in the database
     */
    public boolean isDuplicate(String source, String cloudeventsId) {
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(lookbackDays);
        boolean exists = inboundEventRepository.existsByCloudeventsIdAndSourceAndReceivedAtAfter(
                cloudeventsId, source, since);
        if (exists) {
            log.info("Duplicate detected: source={}, id={}, lookbackDays={}", source, cloudeventsId, lookbackDays);
        }
        return exists;
    }
}
