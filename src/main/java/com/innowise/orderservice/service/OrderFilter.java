package com.innowise.orderservice.service;

import com.innowise.orderservice.entity.OrderStatus;
import java.time.Instant;
import java.util.Set;

/**
 * Filters of a list of orders; every part is optional. The creation range is half-open,
 * {@code [createdFrom, createdBefore)}.
 *
 * @param createdFrom   earliest creation time, inclusive
 * @param createdBefore latest creation time, exclusive
 * @param statuses      allowed statuses; {@code null} or empty for any
 * @param userId        owner of the orders
 */
public record OrderFilter(
  Instant createdFrom, Instant createdBefore, Set<OrderStatus> statuses, Long userId) {

  /** Returns the same filter restricted to one owner. */
  public OrderFilter withUserId(Long owner) {
    return new OrderFilter(createdFrom, createdBefore, statuses, owner);
  }
}
