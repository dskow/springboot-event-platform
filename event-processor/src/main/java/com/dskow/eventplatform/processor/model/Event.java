package com.dskow.eventplatform.processor.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Event(
    String id,
    String assetId,
    Instant timestamp,
    double latitude,
    double longitude,
    String status
) {}
