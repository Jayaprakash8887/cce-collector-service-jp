package org.openphc.cce.collector.api.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openphc.cce.collector.api.dto.ApiResponse;
import org.openphc.cce.collector.api.dto.SourceRegistrationDto;
import org.openphc.cce.collector.domain.model.SourceRegistration;
import org.openphc.cce.collector.service.SourceRegistrationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * CRUD endpoints for registered event sources.
 */
@RestController
@RequestMapping("/v1/sources")
@RequiredArgsConstructor
@Slf4j
public class SourceController {

    private final SourceRegistrationService sourceRegistrationService;

    @PostMapping
    public ResponseEntity<ApiResponse<SourceRegistration>> registerSource(
            @Valid @RequestBody SourceRegistrationDto request) {

        SourceRegistration source = sourceRegistrationService.register(
                request.getSourceUri(),
                request.getDisplayName(),
                request.getDescription(),
                request.getApiKey(),
                request.getAllowedTypes());

        log.info("Registered new source: uri={}", request.getSourceUri());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(source));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<SourceRegistration>>> listSources() {
        List<SourceRegistration> sources = sourceRegistrationService.findAll();
        return ResponseEntity.ok(ApiResponse.success(sources));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SourceRegistration>> getSource(@PathVariable UUID id) {
        return sourceRegistrationService.findAll().stream()
                .filter(s -> s.getId().equals(id))
                .findFirst()
                .map(s -> ResponseEntity.ok(ApiResponse.success(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SourceRegistration>> updateSource(
            @PathVariable UUID id,
            @Valid @RequestBody SourceRegistrationDto request) {

        return sourceRegistrationService.update(id,
                        request.getDisplayName(),
                        request.getDescription(),
                        request.isActive(),
                        request.getApiKey(),
                        request.getAllowedTypes())
                .map(source -> {
                    log.info("Updated source: id={}", id);
                    return ResponseEntity.ok(ApiResponse.success(source));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deactivateSource(@PathVariable UUID id) {
        boolean deactivated = sourceRegistrationService.deactivate(id);
        if (deactivated) {
            log.info("Deactivated source: id={}", id);
            return ResponseEntity.ok(ApiResponse.success("Source deactivated"));
        }
        return ResponseEntity.notFound().build();
    }
}
