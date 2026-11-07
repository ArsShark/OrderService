package com.innowise.orderservice.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** One line of an order request: an item and how many pieces of it. There is no price here. */
public record OrderItemRequest(
  @NotNull @Positive Long itemId,
  @NotNull @Min(1) @Max(1000) Integer quantity) {
}
