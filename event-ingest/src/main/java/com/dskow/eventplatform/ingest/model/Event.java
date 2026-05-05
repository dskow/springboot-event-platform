package com.dskow.eventplatform.ingest.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Event(
    @Size(max = 128) String id,
    @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String assetId,
    Instant timestamp,
    @DecimalMin("-90.0") @DecimalMax("90.0") double latitude,
    @DecimalMin("-180.0") @DecimalMax("180.0") double longitude,
    @NotBlank @Size(max = 32) @Pattern(regexp = "^[a-z][a-z0-9-]*$") String status
) {}
