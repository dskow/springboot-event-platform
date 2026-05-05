package com.dskow.eventplatform.ingest.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Event(
    String id,
    @NotBlank String assetId,
    Instant timestamp,
    double latitude,
    double longitude,
    String status
) {}
