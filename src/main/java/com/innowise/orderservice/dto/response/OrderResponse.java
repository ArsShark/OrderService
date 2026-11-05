package com.innowise.orderservice.dto.response;

import com.innowise.orderservice.entity.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * An order as the order service itself knows it.
 *
 * <p>This is the answer of {@code OrderService} to the layer above, not the body of an HTTP
 * answer: {@code userEmail} is the key for the lookup in the User Service and must not be sent
 * to the client as a separate field. The facade turns this record into the answer with the user.
 */
public record OrderResponse(
  Long id,
  Long userId,
  String userEmail,
  OrderStatus status,
  BigDecimal totalPrice,
  List<OrderItemResponse> items,
  Instant createdAt,
  Instant updatedAt) {

  /** Hides the email: it must not reach a log line by accident. */
  @Override
  public String toString() {
    return String.format("OrderResponse[id=%d, userId=%d, status=%s, totalPrice=%s]",
      id, userId, status, totalPrice);
  }
}
