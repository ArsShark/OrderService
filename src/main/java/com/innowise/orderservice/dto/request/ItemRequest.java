package com.innowise.orderservice.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Payload for creating an item or fully replacing an existing one.
 *
 * <p>The name limit mirrors the column. The price may be zero, as the CHECK constraint allows,
 * has at most two decimals and at most five integer digits: with at most 1000 pieces per line
 * and 50 lines per order the total of an order always fits into {@code NUMERIC(12,2)}.
 */
public record ItemRequest(
  @NotBlank @Size(max = 255) String name,
  @NotNull @DecimalMin("0.00") @Digits(integer = 5, fraction = 2) BigDecimal price) {
}
