package org.openphc.cce.collector.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openphc.cce.collector.domain.model.SourceRegistration;
import org.openphc.cce.collector.domain.repository.SourceRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages registered event sources — for source allowlisting and API key validation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourceRegistrationService {

    private final SourceRegistrationRepository sourceRegistrationRepository;

    /**
     * Check if a source is registered and active.
     */
    public boolean isSourceAllowed(String sourceUri) {
        return sourceRegistrationRepository.findBySourceUriAndActiveTrue(sourceUri).isPresent();
    }

    /**
     * Validate an API key against registered sources.
     *
     * @return true if the API key matches an active source registration
     */
    public boolean validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        String hash = hashApiKey(apiKey);
        return sourceRegistrationRepository.findAll().stream()
                .filter(SourceRegistration::isActive)
                .anyMatch(s -> hash.equals(s.getApiKeyHash()));
    }

    /**
     * Find source registration by URI.
     */
    public Optional<SourceRegistration> findByUri(String sourceUri) {
        return sourceRegistrationRepository.findBySourceUri(sourceUri);
    }

    /**
     * Get all registered sources.
     */
    public List<SourceRegistration> findAll() {
        return sourceRegistrationRepository.findAll();
    }

    /**
     * Get all active sources.
     */
    public List<SourceRegistration> findAllActive() {
        return sourceRegistrationRepository.findByActiveTrue();
    }

    /**
     * Register a new source.
     */
    @Transactional
    public SourceRegistration register(String sourceUri, String displayName, String description,
                                        String apiKey, List<String> allowedTypes) {
        SourceRegistration source = SourceRegistration.builder()
                .sourceUri(sourceUri)
                .displayName(displayName)
                .description(description)
                .active(true)
                .apiKeyHash(apiKey != null ? hashApiKey(apiKey) : null)
                .allowedTypes(allowedTypes != null ? allowedTypes : List.of())
                .build();
        return sourceRegistrationRepository.save(source);
    }

    /**
     * Update an existing source registration.
     */
    @Transactional
    public Optional<SourceRegistration> update(UUID id, String displayName, String description,
                                                boolean active, String apiKey, List<String> allowedTypes) {
        return sourceRegistrationRepository.findById(id).map(source -> {
            source.setDisplayName(displayName);
            source.setDescription(description);
            source.setActive(active);
            if (apiKey != null && !apiKey.isBlank()) {
                source.setApiKeyHash(hashApiKey(apiKey));
            }
            if (allowedTypes != null) {
                source.setAllowedTypes(allowedTypes);
            }
            source.setUpdatedAt(OffsetDateTime.now());
            return sourceRegistrationRepository.save(source);
        });
    }

    /**
     * Deactivate a source.
     */
    @Transactional
    public boolean deactivate(UUID id) {
        return sourceRegistrationRepository.findById(id).map(source -> {
            source.setActive(false);
            source.setUpdatedAt(OffsetDateTime.now());
            sourceRegistrationRepository.save(source);
            return true;
        }).orElse(false);
    }

    /**
     * Hash an API key using SHA-256.
     */
    private String hashApiKey(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
