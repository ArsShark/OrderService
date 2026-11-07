package com.innowise.orderservice.dto.request;

import com.innowise.orderservice.validation.UniqueItems;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Payload for creating an order and for replacing the lines of an existing one.
 *
 * <p>The owner, the status, the prices and the total are never in the body: the owner comes from
 * the token, the prices from the catalog and the total is calculated by the server. At most 50
 * lines with at most 1000 pieces each keep the total inside the column.
 */
public record OrderRequest(
  @NotEmpty @Size(max = 50) @UniqueItems List<@NotNull @Valid OrderItemRequest> items) {
}
