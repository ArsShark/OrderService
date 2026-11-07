package com.innowise.orderservice.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/** An item of the catalog. */
public record ItemResponse(
  Long id,
  String name,
  BigDecimal price,
  Instant createdAt,
  Instant updatedAt) {
}
