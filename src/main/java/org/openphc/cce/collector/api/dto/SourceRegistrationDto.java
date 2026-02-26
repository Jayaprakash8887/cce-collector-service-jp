package org.openphc.cce.collector.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request/response DTO for source registration management.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceRegistrationDto {

    @NotBlank(message = "sourceUri is required")
    private String sourceUri;

    @NotBlank(message = "displayName is required")
    private String displayName;

    private String description;
    private boolean active;
    private String apiKey;           // plaintext on create/update; never returned
    private List<String> allowedTypes;
}
