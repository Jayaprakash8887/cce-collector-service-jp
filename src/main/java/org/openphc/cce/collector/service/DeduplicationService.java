package org.openphc.cce.collector.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openphc.cce.collector.domain.repository.InboundEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Two-layer deduplication: Redis fast-path (24h TTL) + PostgreSQL unique constraints (permanent).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeduplicationService {

    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final InboundEventRepository inboundEventRepository;

    @Value("${cce.collector.dedup.redis-enabled:true}")
    private boolean redisEnabled;

    /**
     * Check if event is a duplicate via Redis fast-path.
     *
     * @return true if duplicate detected in Redis
     */
    public boolean isDuplicateViaRedis(String source, String cloudeventsId) {
        if (!redisEnabled) {
            return false;
        }
        try {
            String key = buildKey(source, cloudeventsId);
            Boolean exists = redisTemplate.hasKey(key);
            if (Boolean.TRUE.equals(exists)) {
                log.info("Duplicate detected via Redis: source={}, id={}", source, cloudeventsId);
                return true;
            }
        } catch (Exception e) {
            log.warn("Redis dedup check failed, falling back to DB: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Check if event is a duplicate via PostgreSQL (authoritative).
     */
    public boolean isDuplicateViaDb(String source, String cloudeventsId) {
        boolean exists = inboundEventRepository.existsByCloudeventsIdAndSource(cloudeventsId, source);
        if (exists) {
            log.info("Duplicate detected via DB: source={}, id={}", source, cloudeventsId);
        }
        return exists;
    }

    /**
     * Mark an event as processed in Redis (set idempotency key with 24h TTL).
     */
    public void markAsProcessed(String source, String cloudeventsId) {
        if (!redisEnabled) {
            return;
        }
        try {
            String key = buildKey(source, cloudeventsId);
            redisTemplate.opsForValue().set(key, "1", DEFAULT_TTL);
            log.debug("Set Redis idempotency key: {}", key);
        } catch (Exception e) {
            log.warn("Failed to set Redis idempotency key: {}", e.getMessage());
        }
    }

    private String buildKey(String source, String cloudeventsId) {
        return KEY_PREFIX + source + ":" + cloudeventsId;
    }
}
