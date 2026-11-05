package com.innowise.orderservice.exception;

import com.innowise.orderservice.entity.OrderStatus;

/** Thrown when the lines of an order are replaced after the order was paid or cancelled. */
public class OrderNotEditableException extends RuntimeException {

  public OrderNotEditableException(Long id, OrderStatus status) {
    super(String.format("Order with id %d is %s and cannot be changed", id, status));
  }
}
