package com.innowise.orderservice.dto.response;

import java.math.BigDecimal;

/** A line of an order with the price that was valid when the order was made. */
public record OrderItemResponse(
  Long itemId,
  String itemName,
  int quantity,
  BigDecimal unitPrice,
  BigDecimal lineTotal) {
}
